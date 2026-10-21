/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字孪生绑定 ↔ 物模型属性的**批量解析器**（读侧自愈 + 写侧校验）。
 *
 * <p><b>为什么需要这一层</b>：绑定的权威键是 {@code (serviceIdentifier, propertyIdentifier)}，
 * 而 {@code propertyId} 只是缓存。属性行会被 TSL 重新导入**物理删除**
 * （{@code IotThingModelServiceImpl#replaceTsl}）⇒ 缓存随时可能悬空。这一层负责：</p>
 * <ol>
 *   <li>读侧：按业务标识批量重解析出当前属性行，{@code propertyId} 悬空时<b>自愈回写</b>；</li>
 *   <li>读侧：解析不到的绑定标 {@code stale=true}（<b>不抛异常</b>——历史绑定失效是正常状态，
 *       页面要能看到并提示重配，抛异常会让整页打不开）；</li>
 *   <li>写侧：校验请求里的 {@code (serviceIdentifier, propertyIdentifier)} 在指定产品下确实存在，
 *       一次列全所有不存在的组合（不让用户改一条试一次）。</li>
 * </ol>
 *
 * <p><b>全程批量</b>：无论多少个绑定，最多 2 次查询（一次 {@code iot_property}、一次
 * {@code iot_service}），不随绑定数增长（N+1 零容忍）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Component
public class TwinBindingResolver {

    private static final Logger log = LoggerFactory.getLogger(TwinBindingResolver.class);

    /** 业务键分隔符（NUL：不可能出现在标识里，避免拼接歧义）。 */
    private static final String KEY_SEPARATOR = "\u0000";

    private final IotPropertyMapper iotPropertyMapper;

    private final IotServiceMapper iotServiceMapper;

    private final IotTwinPointBindingMapper twinPointBindingMapper;

    public TwinBindingResolver(IotPropertyMapper iotPropertyMapper,
                               IotServiceMapper iotServiceMapper,
                               IotTwinPointBindingMapper twinPointBindingMapper) {
        this.iotPropertyMapper = iotPropertyMapper;
        this.iotServiceMapper = iotServiceMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
    }

    /**
     * 拼业务键（唯一口径，避免各处各拼一份）。
     *
     * @param serviceIdentifier  服务标识
     * @param propertyIdentifier 属性标识
     * @return 业务键
     */
    public static String key(String serviceIdentifier, String propertyIdentifier) {
        return serviceIdentifier + KEY_SEPARATOR + propertyIdentifier;
    }

    /**
     * 解析一组绑定（**纯读**）。
     *
     * <p><b>本方法必须保持「无任何写操作」——包括不能经由调用图到达写操作。</b>
     * 它被两个 **GET** 端点调用（点位列表、运行态快照）。此前虽然用
     * {@code if (healCache)} 把 UPDATE 挡住，但 CodeQL 的 CSRF 规则做的是**静态调用图可达性分析**
     * （判据：`GET 端点 → … → DatabaseUpdateMethod`，见 CodeQL
     * `CsrfUnprotectedRequestTypeQuery.qll`），**不追踪布尔参数** ⇒ 只要方法体里出现
     * 对 `@Update` Mapper 方法的调用，GET 端点就会被判为「疑似状态变更」并报 CSRF。
     * 所以回写必须物理隔离到 {@link #healStaleCache}（由写路径显式调用），
     * 而不是靠运行时分支规避。</p>
     *
     * <p>解析不到属性的绑定返回 {@code stale=true}，由页面提示用户重配——**不猜、不静默丢弃**。</p>
     *
     * @param bindings        绑定实体（可为空列表）
     * @param pointProductIds 点位主键 → 其设备所属产品主键（**用于收敛服务集合**；
     *                        传 null 或缺失时该绑定判 stale，绝不跨产品猜）
     * @return 绑定主键 → 解析结果
     */
    public Map<Long, ResolvedBinding> resolve(Collection<IotTwinPointBinding> bindings,
                                              Map<Long, Long> pointProductIds) {
        Map<Long, ResolvedBinding> result = new LinkedHashMap<>();
        if (bindings == null || bindings.isEmpty()) {
            return result;
        }
        // 收敛产品范围：绑定属于某个点位，点位属于某台设备，设备才关联产品。
        // 不收敛会让同一租户下「两个产品都有 Environment 服务」时互相覆盖索引
        // （实测会解析到另一个产品的属性——比 stale 更危险的静默错配）。
        Set<Long> productIds = new LinkedHashSet<>();
        for (IotTwinPointBinding binding : bindings) {
            Long productId = pointProductIds == null ? null
                : pointProductIds.get(binding.getPointId());
            if (productId != null) {
                productIds.add(productId);
            }
        }
        if (productIds.isEmpty()) {
            // 拿不到产品范围（设备已被删除 / 调用方未提供）：不做任何猜测。
            // 逐个绑定判 stale，由页面提示用户重配——错配属性远比「显示失效」更危险。
            for (IotTwinPointBinding binding : bindings) {
                result.put(binding.getId(), toResolved(binding, null, Map.of()));
            }
            return result;
        }
        // ① **按业务标识查库**（不是按主键缓存）——这一步是自愈的前提，不能省。
        //
        //    ⚠️ 曾经写成「只按 property_id 缓存查 selectBatchIds」，那是错的：
        //    属性行被 replaceTsl 物理删除后主键悬空 ⇒ 按主键查**返空** ⇒ 业务标识索引也是空
        //    ⇒ 永远解析不到 ⇒ 永远 stale ⇒ 自愈永不发生。而单测里 mock 无视入参硬返回了行，
        //    所以门禁全绿却完全不通（独立复核实测发现）。
        //    正确口径：业务标识（service.serviceId + property.identifier）才是权威键，
        //    必须真的拿它去查库；主键只作「快路径」缓冲。
        Set<String> serviceIdentifiers = new LinkedHashSet<>();
        for (IotTwinPointBinding binding : bindings) {
            if (StringUtils.hasText(binding.getServiceIdentifier())) {
                serviceIdentifiers.add(binding.getServiceIdentifier().trim());
            }
        }
        // 按业务标识批量取服务行（租户插件自动追加 tenant_id 条件）
        Map<Long, IotService> serviceById = new HashMap<>();
        if (!serviceIdentifiers.isEmpty()) {
            // 用显式的「按业务标识查」方法（而不是拼 LambdaQueryWrapper）：
            // ① 语义自明，review 时一眼看出「业务标识真的参与了查库」；
            // ② 单测可断言这个方法被调用，堵住「mock 无视入参」的假绿。
            for (IotService service : iotServiceMapper
                .selectByServiceIdentifiers(serviceIdentifiers, productIds)) {
                serviceById.put(service.getId(), service);
            }
        }
        // ② 按这些服务的全部属性批量取回（一次 IN，不逐服务查）
        List<IotProperty> properties = serviceById.isEmpty()
            ? List.of()
            : iotPropertyMapper.selectList(new LambdaQueryWrapper<IotProperty>()
                .in(IotProperty::getServiceId, serviceById.keySet()));

        // ③ 建「(产品, 服务业务标识, 属性标识) → 属性」索引。
        //
        //    ⚠️ 键里**必须带产品主键**：service_id 只在产品内唯一，同租户两个产品都定义
        //    `Environment` 是常态。若键只用 (serviceId, identifier)，两个产品的同名属性
        //    会互相覆盖，绑定就被解析到**另一个产品的属性** —— 页面显示错误的值，
        //    比 stale 更危险（用户以为数据正常）。独立复核实测发现过该错配。
        Map<BindingKeyWithProduct, IotProperty> propertyByKey = new HashMap<>();
        for (IotProperty property : properties) {
            IotService service = serviceById.get(property.getServiceId());
            if (service != null && service.getProductId() != null) {
                propertyByKey.put(
                    new BindingKeyWithProduct(service.getProductId(), service.getServiceId(),
                        property.getIdentifier()),
                    property);
            }
        }

        for (IotTwinPointBinding binding : bindings) {
            Long productId = pointProductIds == null ? null
                : pointProductIds.get(binding.getPointId());
            IotProperty matched = productId == null ? null
                : propertyByKey.get(new BindingKeyWithProduct(productId,
                    binding.getServiceIdentifier(), binding.getPropertyIdentifier()));
            result.put(binding.getId(), toResolved(binding, matched, serviceById));
        }
        return result;
    }

    /**
     * 解析索引键：产品 + 服务业务标识 + 属性标识（三者共同唯一确定一个属性）。
     *
     * <p>用 record 而不是拼字符串：避免分隔符出现在标识里造成的键碰撞。</p>
     *
     * @param productId          产品主键
     * @param serviceIdentifier  服务业务标识
     * @param propertyIdentifier 属性标识
     */
    private record BindingKeyWithProduct(Long productId, String serviceIdentifier,
                                         String propertyIdentifier) {
    }

    /**
     * 校验请求里的绑定在指定产品下确实存在（写侧）。
     *
     * <p>一次列全所有不存在的组合再抛错：用户改一条存一次、再报下一条，是这块配置最常见的
     * 折磨点（一次绑定通常 3~8 条，逐条试错要 8 次往返）。</p>
     *
     * @param productId 产品主键（设备绑定的产品；null 表示设备未绑定产品）
     * @param keys      请求里的绑定业务键
     */
    public void validateBindings(Long productId, List<BindingKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (productId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备未绑定产品，无法配置属性绑定：请先在设备台账里为该设备选择产品");
        }
        List<Long> serviceIds = iotServiceMapper.selectList(new LambdaQueryWrapper<IotService>()
                .eq(IotService::getProductId, productId))
            .stream().map(IotService::getId).toList();
        if (serviceIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备绑定的产品还没有任何物模型服务，无法配置属性绑定");
        }
        Map<Long, IotService> serviceById = loadServices(new LinkedHashSet<>(serviceIds));
        Map<String, IotProperty> propertyByKey = new HashMap<>();
        for (IotProperty property : iotPropertyMapper.selectList(
            new LambdaQueryWrapper<IotProperty>().in(IotProperty::getServiceId, serviceIds))) {
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                propertyByKey.put(key(service.getServiceId(), property.getIdentifier()), property);
            }
        }
        List<String> missing = new ArrayList<>();
        for (BindingKey bindingKey : keys) {
            String compositeKey = key(bindingKey.serviceIdentifier(), bindingKey.propertyIdentifier());
            if (!propertyByKey.containsKey(compositeKey)) {
                missing.add(bindingKey.serviceIdentifier() + "." + bindingKey.propertyIdentifier());
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "以下属性在设备绑定的产品物模型里不存在（共 " + missing.size() + " 个）："
                    + String.join("、", missing)
                    + "。请确认服务标识与属性标识，或先在产品物模型里补齐该属性");
        }
    }

    /**
     * 批量取属性主键（写侧用：把业务标识解析成当前的主键缓存值）。
     *
     * @param productId 产品主键
     * @param keys      绑定业务键
     * @return 业务键 → 属性主键（调用前必须先 {@link #validateBindings} 保证全部存在）
     */
    public Map<String, Long> resolvePropertyIds(Long productId, List<BindingKey> keys) {
        Map<String, Long> result = new HashMap<>();
        if (productId == null || keys == null || keys.isEmpty()) {
            return result;
        }
        List<Long> serviceIds = iotServiceMapper.selectList(new LambdaQueryWrapper<IotService>()
                .eq(IotService::getProductId, productId))
            .stream().map(IotService::getId).toList();
        if (serviceIds.isEmpty()) {
            return result;
        }
        Map<Long, IotService> serviceById = loadServices(new LinkedHashSet<>(serviceIds));
        for (IotProperty property : iotPropertyMapper.selectList(
            new LambdaQueryWrapper<IotProperty>().in(IotProperty::getServiceId, serviceIds))) {
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                result.put(key(service.getServiceId(), property.getIdentifier()), property.getId());
            }
        }
        return result;
    }

    private Map<Long, IotService> loadServices(Set<Long> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, IotService> serviceById = new HashMap<>();
        for (IotService service : iotServiceMapper.selectBatchIds(serviceIds)) {
            serviceById.put(service.getId(), service);
        }
        return serviceById;
    }

    private ResolvedBinding toResolved(IotTwinPointBinding binding, IotProperty matched,
                                       Map<Long, IotService> serviceById) {
        ResolvedBinding resolved = new ResolvedBinding();
        resolved.setBindingId(binding.getId());
        resolved.setStale(matched == null);
        if (matched != null) {
            resolved.setPropertyId(matched.getId());
            resolved.setPropertyName(matched.getPropertyName());
            resolved.setDataType(matched.getDataType());
            IotService service = serviceById.get(matched.getServiceId());
            resolved.setServiceName(service == null ? null : service.getServiceName());
        } else {
            resolved.setPropertyId(null);
            // 解析不到时不编造名称：回落属性标识本身，让用户至少知道它原来指向什么
            resolved.setPropertyName(binding.getPropertyIdentifier());
            resolved.setDataType(null);
            resolved.setServiceName(null);
        }
        resolved.setUnit(binding.getUnitOverride() != null
            ? binding.getUnitOverride()
            : (matched == null ? null : matched.getUnit()));
        return resolved;
    }

    /**
     * 自愈：主键缓存悬空但业务标识能解析到的，回写新的 {@code property_id}。
     *
     * <p>只在「解析成功且主键与缓存不一致」时回写。回写失败只记 WARN 不影响本次响应
     * （读路径不该因为写失败而报错）。</p>
     */
    /**
     * 回写失效的主键缓存（**写操作，只允许写路径调用**）。
     *
     * <p>独立成公开方法而不是塞进 {@link #resolve} 的可选分支，原因见 `resolve` 注释：
     * CodeQL 的 CSRF 判据是静态调用图可达性，运行时布尔开关挡不住它。
     * 本方法要求调用方**自己先 {@link #resolve} 一次**并把结果传进来，避免重复解析。</p>
     *
     * @param bindings 绑定实体
     * @param resolved 已解析结果（key 为绑定主键）
     */
    public void healStaleCache(Collection<IotTwinPointBinding> bindings,
                                Map<Long, ResolvedBinding> resolved) {
        List<Map<String, Object>> healing = new ArrayList<>();
        for (IotTwinPointBinding binding : bindings) {
            ResolvedBinding hit = resolved.get(binding.getId());
            if (hit == null || Boolean.TRUE.equals(hit.getStale()) || hit.getPropertyId() == null) {
                continue;
            }
            if (!hit.getPropertyId().equals(binding.getPropertyId())) {
                // 每项带上**读到的旧值**（oldPropertyId），供 SQL 做乐观并发条件：
                // 只有「库中仍是这一版」的行才会被更新（工单 #307）。
                Map<String, Object> patch = new HashMap<>(4);
                patch.put("id", binding.getId());
                patch.put("oldPropertyId", binding.getPropertyId());
                patch.put("propertyId", hit.getPropertyId());
                healing.add(patch);
            }
        }
        if (healing.isEmpty()) {
            return;
        }
        try {
            // **一条 CASE WHEN 语句**完成全部回写（不在循环里逐条 update——
            // 读路径上的写放大会直接把「打开场景」变成 N 次往返）。
            int affected = twinPointBindingMapper.batchHealPropertyId(healing);
            if (affected == healing.size()) {
                log.info("[iot] 孪生绑定主键缓存已自愈回写：count={} 条"
                    + "（属性行因 TSL 重导入换了主键）", affected);
            } else {
                // 行数不符 ⇒ 期间有并发改写（另一请求已替换绑定或已完成自愈）。
                // **必须记 WARN 而不是静默**：否则「自愈结论与库中值长期不一致、
                // 来回抖动」这种问题只能靠翻痕迹猜。
                log.warn("[iot] 孪生绑定主键缓存自愈部分跳过：期望 {} 条，实际 {} 条。"
                        + "差额说明这些行在读取与回写之间被并发改写过（本次不覆盖新值，属预期保护）",
                    healing.size(), affected);
            }
        } catch (RuntimeException ex) {
            // 读路径不该因写失败而整体失败：记录完整堆栈并继续返回本次已解析的结果
            log.warn("[iot] 孪生绑定主键缓存回写失败（不影响本次读取）：count={} 条",
                healing.size(), ex);
        }
    }

    /**
     * 绑定业务键（写侧入参的抽象）。
     *
     * @param serviceIdentifier  服务标识
     * @param propertyIdentifier 属性标识
     */
    public record BindingKey(String serviceIdentifier, String propertyIdentifier) {
    }

    /**
     * 解析结果（读侧）。
     *
     * @author wenbin
     * @since 2026-10-21
     */
    @Getter
    @Setter
    public static class ResolvedBinding {

        /** 绑定主键。 */
        private Long bindingId;

        /** 当前属性主键（解析不到时为 null）。 */
        private Long propertyId;

        /** 属性显示名（解析不到时回落属性标识）。 */
        private String propertyName;

        /** 物模型数据类型（解析不到时为 null）。 */
        private String dataType;

        /** 服务名称（解析不到时为 null）。 */
        private String serviceName;

        /** 生效单位（unit_override 优先）。 */
        private String unit;

        /** 是否已失效。 */
        private Boolean stale;
    }
}
