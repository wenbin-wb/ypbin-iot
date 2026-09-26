/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapping;

import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 设备 → **点位坐标索引**（入站校验 + 读侧坐标归一的数据源）。
 *
 * <p><b>一条 SQL 取回整批设备</b>（本仓铁律：严禁在循环里查库）：一次
 * {@code iot_point_mapping WHERE device_id IN (…)} 拿到全部映射，再用**一次** {@code iot_property}
 * 批量取回被引用的属性行（主键 + {@code identifier}）——最多两次查询，与设备数/点位数无关。
 * 空入参（{@code deviceIds} 为空）与「映射为空」都**先判空短路**：不做 {@code IN ()}、
 * 不发第二次查询、不抛异常。</p>
 *
 * <p><b>规范坐标 = 属性标识（{@code iot_property.identifier}）</b>（2026-09-26 统一，见
 * {@code docs/IOT-ROADMAP.md} 四点十七补充段）。历史遗留形态是**属性主键字符串**
 * （{@code access} 采集链路在统一之前上报的就是它）。本索引因此同时产出三件事：</p>
 * <ol>
 *   <li><b>形态 → 规范标识</b>（{@link DeviceCoordinates#canonicalByForm()}）：入站把任意被接受的
 *       形态**归一到标识**再落库（写侧统一，新数据不再出现第二种 field）；读侧据此把历史行归位。</li>
 *   <li><b>规范标识 → 全部历史主键字符串</b>（{@link DeviceCoordinates#legacyFormsByCanonical()}）：
 *       过渡期内读侧（最新值 / 历史曲线）要**两种都认**，靠它把存量历史行也查出来。
 *       <b>是集合而不是单值</b>：见下面「标识撞名」一条。</li>
 *   <li><b>孤儿形态</b>（{@link DeviceCoordinates#orphanForms()}）：见下面「属性行必须存在」。</li>
 * </ol>
 *
 * <p><b>属性行必须存在（孤儿映射收紧，2026-09-26）</b>：{@code iot_property} 行被物理删除
 * （TSL 重导入走的 {@code IotThingModelServiceImpl#replaceTsl} 就是物理删）而 {@code iot_point_mapping}
 * 行仍在时，该映射是**孤儿**——它不再产出任何合法坐标，只把主键字符串计入
 * {@link DeviceCoordinates#orphanForms()}，由入站侧「丢弃 + 单独计数」，
 * 由保留期巡检统计成指标（见 {@code IotPointMappingMapper#countOrphanMappings}）。
 * 这与统一之前的区别是实打实的：此前孤儿映射的主键字符串**仍被算作已映射**，读数照落库。</p>
 *
 * <p><b>两类「撞名」必须都留痕（不静默）</b>：</p>
 * <ul>
 *   <li>{@link DeviceCoordinates#ambiguousForms()}：<b>形态级</b>撞名——A 点的历史主键字符串恰好等于
 *       B 点的属性标识。该形态**判给标识**（规范坐标，规则确定、可复现），记入本集合由调用方 WARN。</li>
 *   <li>{@link DeviceCoordinates#duplicateIdentifiers()}：<b>坐标级</b>撞名——同一设备上**两条映射**的
 *       属性行标识相同。可达性已核实：{@code uk_iot_property(tenant_id, service_id, identifier)}
 *       只在 service 内唯一，同一产品的两个 service 可以各有一个 {@code temperature}，
 *       而点位映射只校验「属性所属 service 属于本设备产品」⇒ 一台设备可以映射两个同名属性。
 *       此时两个点位**必然**共享同一个规范坐标（这是「规范坐标 = 标识」这一选择的内生结果），
 *       本索引无法把它们分开；能做且必须做的是**不静默**：记入本集合 + WARN + 计数
 *       {@value #METRIC_COORDINATE_COLLISION}。**读侧则把两者的历史形态都查出来**
 *       （{@link DeviceCoordinates#legacyFormsByCanonical()} 是集合），至少不让存量数据凭空消失。
 *       彻底解法（拒绝同设备同名标识的映射）属产品/校验策略，登记在
 *       {@code docs/IOT-ROADMAP.md} 四点十七，本轮不做。</li>
 * </ul>
 *
 * <p><b>租户</b>：{@code iot_point_mapping} / {@code iot_property} 都是租户表。入站上报路径**没有租户
 * 身份**（只有 {@code X-Internal-Token}）⇒ 查询必须包在 {@link TenantContext#executeIgnore} 里，
 * 否则租户插件 fail-closed 直接抛。安全性来自「设备 id 显式取自本批上报 / 已由租户过滤后的设备行」，
 * 与 {@code AvailabilityServiceImpl#resolveTenants} 同款处理。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
@Component
public class PointMappingIndex {

    /**
     * 同一设备上两条映射的**规范坐标相同**（属性标识撞名）的次数。
     *
     * <p>按「每次装载发现一次」计：它是配置错误的暴露口，不是唯一映射数。持续增长说明物模型里
     * 同产品多 service 定义了同名标识，且设备把它们都映射上了——此时两个点位的读数会落进同一个坐标。</p>
     */
    public static final String METRIC_COORDINATE_COLLISION = "iot.pointmapping.coordinate_collision";

    private final IotPointMappingMapper pointMappingMapper;
    private final IotPropertyMapper propertyMapper;
    private final Counter coordinateCollisionCounter;

    public PointMappingIndex(IotPointMappingMapper pointMappingMapper, IotPropertyMapper propertyMapper,
                             MeterRegistry meterRegistry) {
        this.pointMappingMapper = pointMappingMapper;
        this.propertyMapper = propertyMapper;
        this.coordinateCollisionCounter = Counter.builder(METRIC_COORDINATE_COLLISION)
            .description("同一设备上两条点位映射的规范坐标（属性标识）相同的次数")
            .register(meterRegistry);
    }

    /**
     * 一台设备的坐标索引。
     *
     * @param canonicalByForm        任意**被接受的**形态 → 规范标识（含标识自身：标识 → 标识）
     * @param legacyFormsByCanonical 规范标识 → 该坐标的**全部**历史主键字符串形态（仅属性行存在时才有）
     * @param orphanForms            孤儿映射的形态（属性行缺失）；**不是**合法坐标，只用于区分计数
     * @param ambiguousForms         与其它点位的属性标识撞名的历史形态（判给标识，记此以便告警）
     * @param duplicateIdentifiers   同一设备上被两条映射共用的规范标识（坐标级撞名；记此以便告警）
     * @author wenbin
     * @since 2026-09-26
     */
    public record DeviceCoordinates(Map<String, String> canonicalByForm,
                                    Map<String, Set<String>> legacyFormsByCanonical,
                                    Set<String> orphanForms,
                                    Set<String> ambiguousForms,
                                    Set<String> duplicateIdentifiers) {

        /** 空索引（既无映射，也无孤儿）。 */
        public static DeviceCoordinates empty() {
            return new DeviceCoordinates(Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
        }

        /**
         * 形态 → 规范标识。
         *
         * @param form 上报/存储里出现的坐标形态
         * @return 规范标识；该形态不被任何**有效**映射接受时返回 {@code null}
         */
        public String canonicalize(String form) {
            return form == null ? null : canonicalByForm.get(form);
        }

        /**
         * 规范坐标在过渡期内**要一并查询**的全部存储形态。
         *
         * <p>返回**有序**集合（规范标识在首位，其后是历史主键字符串）⇒ 由它拼出的 SQL 谓词稳定，
         * 不随 JVM/哈希顺序变化（否则同一条查询在不同进程里生成不同 SQL 文本，排障时看着像"改了代码"）。</p>
         *
         * @param canonical 规范标识
         * @return 形态集合；该坐标不是本设备的点位时只含入参自身
         */
        public Set<String> aliasesOf(String canonical) {
            if (canonical == null) {
                return Set.of();
            }
            Set<String> legacyForms = legacyFormsByCanonical.get(canonical);
            if (legacyForms == null || legacyForms.isEmpty()) {
                return Set.of(canonical);
            }
            Set<String> aliases = new LinkedHashSet<>(legacyForms.size() + 1);
            aliases.add(canonical);
            aliases.addAll(legacyForms);
            return Collections.unmodifiableSet(aliases);
        }

        /** 是否没有任何有效映射（调用方据此把该设备的带点位读数全判为未映射）。 */
        public boolean noMapping() {
            return canonicalByForm.isEmpty();
        }
    }

    /**
     * 批量取「设备 → 坐标索引」。
     *
     * @param deviceIds 设备 ID（空则直接返回空 Map，不查库）
     * @return 设备 → 坐标索引；**没有任何映射行的设备不会出现在结果里**
     */
    public Map<Long, DeviceCoordinates> loadCoordinates(Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return TenantContext.executeIgnore(() -> load(deviceIds));
    }

    /** 真正的两次批量查询（已在 ignore-tenant 上下文内）。 */
    private Map<Long, DeviceCoordinates> load(Collection<Long> deviceIds) {
        List<IotPointMapping> mappings = pointMappingMapper.selectList(
            Wrappers.<IotPointMapping>lambdaQuery()
                .select(IotPointMapping::getDeviceId, IotPointMapping::getPropertyId)
                .in(IotPointMapping::getDeviceId, deviceIds)
                // 显式排序（2026-09-26，独立复核建议）：`aliasesOf` 声称「有序」，而历史形态的相对顺序
                // 若跟随 DB 行序（无 ORDER BY 时不保证）就会出现「同一条查询的 SQL 谓词在不同次运行里顺序不同」。
                // 按主键升序把顺序钉死，代价是一次可走主键的排序。
                .orderByAsc(IotPointMapping::getId));
        if (mappings.isEmpty()) {
            // 空映射：不再查属性表（先判空短路），由调用方把该设备的读数全部按「未映射」丢弃
            return Map.of();
        }
        Set<Long> propertyIds = mappings.stream()
            .map(IotPointMapping::getPropertyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> identifierByProperty = propertyIds.isEmpty()
            ? Map.of() : loadIdentifiers(propertyIds);

        Map<Long, MutableCoordinates> builders = new LinkedHashMap<>();
        for (IotPointMapping mapping : mappings) {
            if (mapping.getDeviceId() == null || mapping.getPropertyId() == null) {
                // 脏行（理论上不会出现）：跳过而不是抛，避免一条坏映射让整批上报失败
                continue;
            }
            MutableCoordinates coordinates = builders.computeIfAbsent(
                mapping.getDeviceId(), deviceId -> new MutableCoordinates());
            String legacyForm = String.valueOf(mapping.getPropertyId());
            String identifier = identifierByProperty.get(mapping.getPropertyId());
            if (identifier == null || identifier.isBlank()) {
                // 孤儿映射：属性行缺失（或标识为空）⇒ 该映射不再产出合法坐标，只留形态供区分计数
                coordinates.orphanForms.add(legacyForm);
                continue;
            }
            // 顺序即规则：先登记历史形态，再登记规范形态 ⇒ 形态撞名时**标识胜出**（确定性）
            String previous = coordinates.canonicalByForm.putIfAbsent(legacyForm, identifier);
            if (previous != null && !previous.equals(identifier)) {
                coordinates.ambiguousForms.add(legacyForm);
            }
            // 坐标级撞名：同一标识已被另一条映射占用 ⇒ 两个点位必然共享一个规范坐标（不静默）
            Set<String> legacyForms = coordinates.legacyFormsByCanonical
                .computeIfAbsent(identifier, key -> new LinkedHashSet<>());
            if (!legacyForms.isEmpty() && !legacyForms.contains(legacyForm)) {
                coordinates.duplicateIdentifiers.add(identifier);
            }
            legacyForms.add(legacyForm);
            String overwritten = coordinates.canonicalByForm.put(identifier, identifier);
            if (overwritten != null && !overwritten.equals(identifier)) {
                coordinates.ambiguousForms.add(identifier);
            }
        }
        return build(builders);
    }

    /** 冻结成不可变记录，并把坐标级撞名的次数计进指标。 */
    private Map<Long, DeviceCoordinates> build(Map<Long, MutableCoordinates> builders) {
        Map<Long, DeviceCoordinates> result = new LinkedHashMap<>(builders.size());
        for (Map.Entry<Long, MutableCoordinates> entry : builders.entrySet()) {
            DeviceCoordinates coordinates = entry.getValue().freeze();
            if (!coordinates.duplicateIdentifiers().isEmpty()) {
                coordinateCollisionCounter.increment(coordinates.duplicateIdentifiers().size());
            }
            result.put(entry.getKey(), coordinates);
        }
        return result;
    }

    /** 一次批量取回属性标识（属性行缺失时该主键就不在结果里 ⇒ 该映射被判为孤儿）。 */
    private Map<Long, String> loadIdentifiers(Set<Long> propertyIds) {
        return propertyMapper.selectList(Wrappers.<IotProperty>lambdaQuery()
                .select(IotProperty::getId, IotProperty::getIdentifier)
                .in(IotProperty::getId, propertyIds))
            .stream()
            // 必须同时过滤 null 值的两种情况：Collectors.toMap 的 merge 不接受 null 值（会抛 NPE），
            // 而 identifier 为 null 的属性行语义上等价于「属性行缺失」——由调用方按孤儿处理
            .filter(property -> property.getId() != null && property.getIdentifier() != null)
            .collect(Collectors.toMap(IotProperty::getId, IotProperty::getIdentifier, (left, right) -> left));
    }

    /** 构建期的可变累加器（构建完冻结成不可变记录，避免调用方拿到可改集合）。 */
    private static final class MutableCoordinates {

        private final Map<String, String> canonicalByForm = new LinkedHashMap<>();

        private final Map<String, Set<String>> legacyFormsByCanonical = new LinkedHashMap<>();

        private final Set<String> orphanForms = new LinkedHashSet<>();

        private final Set<String> ambiguousForms = new LinkedHashSet<>();

        private final Set<String> duplicateIdentifiers = new LinkedHashSet<>();

        private DeviceCoordinates freeze() {
            Map<String, Set<String>> legacyForms = new LinkedHashMap<>(legacyFormsByCanonical.size());
            for (Map.Entry<String, Set<String>> entry : legacyFormsByCanonical.entrySet()) {
                legacyForms.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
            return new DeviceCoordinates(Map.copyOf(canonicalByForm), Map.copyOf(legacyForms),
                Set.copyOf(orphanForms), Set.copyOf(ambiguousForms), Set.copyOf(duplicateIdentifiers));
        }
    }
}
