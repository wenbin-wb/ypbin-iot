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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 设备 → **点位坐标集合**索引（P0-6c 的「物模型属性 × 该设备点位映射」落点）。
 *
 * <p><b>一条 SQL 取回整批设备</b>（本仓铁律：严禁在循环里查库）：一次
 * {@code iot_point_mapping WHERE device_id IN (…)} 拿到全部映射，再用**一次** {@code iot_property}
 * 批量取回被引用的属性标识（`identifier`）——最多两次查询，与设备数/点位数无关。
 * 空入参（{@code deviceIds} 为空）与「映射为空」都**先判空短路**：不做 {@code IN ()}、
 * 不发第二次查询、不抛异常。</p>
 *
 * <p><b>为什么集合里同时放两种形态（重要口径，勿想当然）</b>：读数里的 {@code propertyId} 在两条
 * 现存路径上不是同一种形态 ——</p>
 * <ul>
 *   <li><b>属性主键字符串</b>：{@code access} 采集链路上报的就是这个形态
 *       （{@code DeviceSpecServiceImpl} 把 {@code AccessPointMappingDto.propertyId} 设为
 *       {@code String.valueOf(mapping.getPropertyId())}，即主键的字符串）；</li>
 *   <li><b>属性标识</b>（{@code iot_property.identifier}，camelCase）：EMQX 入站设计 §6.1 的
 *       {@code up/property} 契约示例（{@code "propertyId": "temperature"}）与前端/物模型接口用的是这个形态；
 *       生产 {@code iot:latest:*} 的 field 也是标识（本机核查：device 9300001 的映射属性标识为
 *       temperature/humidity，其存量 field 正是这两个名字）。</li>
 * </ul>
 * <p>只放其中一种都会**误杀另一条活路径**（尤其只放标识会让 access 上报的读数全被判「未映射」），
 * 故两种都放。两种形态都来自**该设备自己的映射行**，因此「是不是这台设备的点位」这个判定不受影响。
 * 待平台统一坐标形态后，这里应收缩为那唯一一种（已在 ROADMAP/PR 登记）。</p>
 *
 * <p><b>租户</b>：{@code iot_point_mapping} / {@code iot_property} 都是租户表，而入站上报路径**没有租户
 * 身份**（只有 {@code X-Internal-Token}）⇒ 查询必须包在 {@link TenantContext#executeIgnore} 里，
 * 否则租户插件 fail-closed 直接抛。安全性来自「设备 id 显式取自本批上报」，与
 * {@code AvailabilityServiceImpl#resolveTenants} 同款处理。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
@Component
public class PointMappingIndex {

    private final IotPointMappingMapper pointMappingMapper;
    private final IotPropertyMapper propertyMapper;

    public PointMappingIndex(IotPointMappingMapper pointMappingMapper, IotPropertyMapper propertyMapper) {
        this.pointMappingMapper = pointMappingMapper;
        this.propertyMapper = propertyMapper;
    }

    /**
     * 批量取「设备 → 该设备的点位坐标集合」。
     *
     * @param deviceIds 设备 ID（空则直接返回空 Map，不查库）
     * @return 设备 → 坐标集合；**没有映射的设备不会出现在结果里**（调用方按「空集合＝全部未映射」处理）
     */
    public Map<Long, Set<String>> loadByDeviceIds(Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        return TenantContext.executeIgnore(() -> load(deviceIds));
    }

    /** 真正的两次批量查询（已在 ignore-tenant 上下文内）。 */
    private Map<Long, Set<String>> load(Collection<Long> deviceIds) {
        List<IotPointMapping> mappings = pointMappingMapper.selectList(
            Wrappers.<IotPointMapping>lambdaQuery()
                .select(IotPointMapping::getDeviceId, IotPointMapping::getPropertyId)
                .in(IotPointMapping::getDeviceId, deviceIds));
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
        Map<Long, Set<String>> coordinatesByDevice = new LinkedHashMap<>();
        for (IotPointMapping mapping : mappings) {
            if (mapping.getDeviceId() == null || mapping.getPropertyId() == null) {
                // 脏行（理论上不会出现）：跳过而不是抛，避免一条坏映射让整批上报失败
                continue;
            }
            Set<String> coordinates = coordinatesByDevice.computeIfAbsent(
                mapping.getDeviceId(), deviceId -> new LinkedHashSet<>());
            coordinates.add(String.valueOf(mapping.getPropertyId()));
            String identifier = identifierByProperty.get(mapping.getPropertyId());
            if (identifier != null && !identifier.isBlank()) {
                coordinates.add(identifier);
            }
        }
        return coordinatesByDevice;
    }

    /** 一次批量取回属性标识（属性行缺失时该主键就只剩主键字符串形态，不抛）。 */
    private Map<Long, String> loadIdentifiers(Set<Long> propertyIds) {
        return propertyMapper.selectList(Wrappers.<IotProperty>lambdaQuery()
                .select(IotProperty::getId, IotProperty::getIdentifier)
                .in(IotProperty::getId, propertyIds))
            .stream()
            .filter(property -> property.getId() != null && property.getIdentifier() != null)
            .collect(Collectors.toMap(IotProperty::getId, IotProperty::getIdentifier, (left, right) -> left));
    }
}
