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
     * 解析一组绑定的展示信息（读侧）。
     *
     * @param bindings 绑定实体（可为空列表）
     * @return 绑定主键 → 解析结果（键集合与入参一一对应）
     */
    public Map<Long, ResolvedBinding> resolve(Collection<IotTwinPointBinding> bindings) {
        Map<Long, ResolvedBinding> result = new LinkedHashMap<>();
        if (bindings == null || bindings.isEmpty()) {
            return result;
        }
        // ① 一次按主键取属性行（含 service_id）
        Set<Long> propertyIds = new LinkedHashSet<>();
        for (IotTwinPointBinding binding : bindings) {
            if (binding.getPropertyId() != null) {
                propertyIds.add(binding.getPropertyId());
            }
        }
        List<IotProperty> properties = propertyIds.isEmpty()
            ? List.of()
            : iotPropertyMapper.selectBatchIds(propertyIds);

        // ② 一次取全部被引用的服务行（拿到 service_id 业务标识，用于按标识匹配）
        Set<Long> serviceIds = new LinkedHashSet<>();
        for (IotProperty property : properties) {
            if (property.getServiceId() != null) {
                serviceIds.add(property.getServiceId());
            }
        }
        Map<Long, IotService> serviceById = loadServices(serviceIds);

        // ③ 建两个索引：按主键命中、按业务标识命中
        Map<String, IotProperty> propertyByKey = new HashMap<>();
        Map<Long, IotProperty> propertyById = new HashMap<>();
        for (IotProperty property : properties) {
            propertyById.put(property.getId(), property);
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                propertyByKey.put(key(service.getServiceId(), property.getIdentifier()), property);
            }
        }

        for (IotTwinPointBinding binding : bindings) {
            IotProperty matched = matchProperty(binding, propertyById, propertyByKey);
            result.put(binding.getId(), toResolved(binding, matched, serviceById));
        }
        healStaleCache(bindings, result);
        return result;
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

    /**
     * 先用主键缓存命中，再按业务标识兜底（主键悬空时）。
     *
     * @param binding       绑定实体
     * @param propertyById  主键索引
     * @param propertyByKey 业务键索引
     * @return 命中的属性行；都命中不到时返回 null
     */
    private IotProperty matchProperty(IotTwinPointBinding binding,
                                      Map<Long, IotProperty> propertyById,
                                      Map<String, IotProperty> propertyByKey) {
        if (binding.getPropertyId() != null) {
            IotProperty byId = propertyById.get(binding.getPropertyId());
            if (byId != null) {
                return byId;
            }
        }
        return propertyByKey.get(key(binding.getServiceIdentifier(), binding.getPropertyIdentifier()));
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
    private void healStaleCache(Collection<IotTwinPointBinding> bindings,
                                Map<Long, ResolvedBinding> resolved) {
        List<IotTwinPointBinding> healing = new ArrayList<>();
        for (IotTwinPointBinding binding : bindings) {
            ResolvedBinding hit = resolved.get(binding.getId());
            if (hit == null || Boolean.TRUE.equals(hit.getStale()) || hit.getPropertyId() == null) {
                continue;
            }
            if (!hit.getPropertyId().equals(binding.getPropertyId())) {
                // 只带批量更新需要的字段（不整行回写：那样会把读侧碰巧拿到的旧值写回去）
                IotTwinPointBinding patch = new IotTwinPointBinding();
                patch.setId(binding.getId());
                patch.setPointId(binding.getPointId());
                patch.setServiceIdentifier(binding.getServiceIdentifier());
                patch.setPropertyIdentifier(binding.getPropertyIdentifier());
                patch.setPropertyId(hit.getPropertyId());
                healing.add(patch);
            }
        }
        if (healing.isEmpty()) {
            return;
        }
        try {
            // **一条 CASE WHEN 语句**完成全部回写（不在循环里逐条 update——
            // 读路径上的写放大会直接把「打开场景」变成 N 次往返）。
            twinPointBindingMapper.batchHealPropertyId(healing);
            log.info("[iot] 孪生绑定主键缓存已自愈回写：count={} 条（属性行因 TSL 重导入换了主键）",
                healing.size());
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
