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

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.service.DeviceSpecService;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 设备采集规格服务实现。
 *
 * <p><b>为什么要显式绑定租户上下文</b>：本服务只被 {@code /internal/**} 调用，而那条链路**没有租户身份**
 * （只有内部凭证）。设备与点位表都是租户表，租户插件在无租户上下文时是 **fail-closed** 的
 * （直接抛异常而不是放行全表）。因此这里用调用方传入的 {@code tenantId} 显式
 * {@link TenantContext#executeWithTenant} 绑定，让插件照常追加 {@code tenant_id} 条件——
 * 既不绕过隔离，也不需要手写 tenant_id 过滤。</p>
 *
 * <p>查询次数与设备数无关：设备 1 次、点位 1 次（按 deviceId 批量 IN）、属性标识 1 次（按主键批量），
 * 空集合一律短路，绝不发空 IN。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Service
public class DeviceSpecServiceImpl implements DeviceSpecService {

    /** 连接标识前缀：{@code t{tenantId}-d{deviceId}}，自带租户信息供连接回调定位。 */
    private static final String CONNECTION_PREFIX_TENANT = "t";

    private static final String CONNECTION_SEPARATOR = "-d";

    private final IotDeviceMapper iotDeviceMapper;
    private final IotPointMappingMapper iotPointMappingMapper;
    private final IotPropertyMapper iotPropertyMapper;

    public DeviceSpecServiceImpl(IotDeviceMapper iotDeviceMapper,
                                 IotPointMappingMapper iotPointMappingMapper,
                                 IotPropertyMapper iotPropertyMapper) {
        this.iotDeviceMapper = iotDeviceMapper;
        this.iotPointMappingMapper = iotPointMappingMapper;
        this.iotPropertyMapper = iotPropertyMapper;
    }

    @Override
    public List<AccessDeviceSpecResp> listByTenant(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return TenantContext.executeWithTenant(tenantId, () -> assemble(tenantId));
    }

    private List<AccessDeviceSpecResp> assemble(Long tenantId) {
        List<IotDevice> devices = iotDeviceMapper.selectList(
            new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getStatus, 1)
                .orderByAsc(IotDevice::getId));
        if (devices.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = devices.stream().map(IotDevice::getId).toList();

        Map<Long, List<IotPointMapping>> mappingsByDevice = iotPointMappingMapper.selectList(
                new LambdaQueryWrapper<IotPointMapping>()
                    .in(IotPointMapping::getDeviceId, deviceIds)
                    .eq(IotPointMapping::getEnabled, Boolean.TRUE)
                    .orderByAsc(IotPointMapping::getId))
            .stream()
            .collect(Collectors.groupingBy(IotPointMapping::getDeviceId));

        Map<Long, String> identifierByProperty = loadPropertyIdentifiers(mappingsByDevice);

        List<AccessDeviceSpecResp> result = new ArrayList<>(devices.size());
        for (IotDevice device : devices) {
            result.add(toSpec(tenantId, device,
                mappingsByDevice.getOrDefault(device.getId(), List.of()), identifierByProperty));
        }
        return result;
    }

    /** 批量取属性标识：空集合短路，避免空 IN。 */
    private Map<Long, String> loadPropertyIdentifiers(Map<Long, List<IotPointMapping>> mappingsByDevice) {
        List<Long> propertyIds = mappingsByDevice.values().stream()
            .flatMap(List::stream)
            .map(IotPointMapping::getPropertyId)
            .distinct()
            .toList();
        if (propertyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> identifiers = new HashMap<>(propertyIds.size());
        for (IotProperty property : iotPropertyMapper.selectBatchIds(propertyIds)) {
            identifiers.put(property.getId(), property.getIdentifier());
        }
        return identifiers;
    }

    private AccessDeviceSpecResp toSpec(Long tenantId, IotDevice device,
                                        List<IotPointMapping> mappings,
                                        Map<Long, String> identifierByProperty) {
        AccessDeviceSpecResp spec = new AccessDeviceSpecResp();
        String deviceId = String.valueOf(device.getId());
        spec.setDeviceId(deviceId);
        spec.setDeviceName(device.getDeviceName());
        spec.setProtocol(device.getProtocol());
        spec.setConnectionId(CONNECTION_PREFIX_TENANT + tenantId + CONNECTION_SEPARATOR + deviceId);
        spec.setEndpoint(device.getEndpoint());
        spec.setCredentialRef(device.getCredentialRef());
        spec.setPoints(mappings.stream().map(m -> toPoint(m, identifierByProperty)).toList());
        spec.setPollIntervalMs(minInterval(mappings));
        return spec;
    }

    private AccessPointMappingDto toPoint(IotPointMapping mapping, Map<Long, String> identifierByProperty) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setPropertyId(String.valueOf(mapping.getPropertyId()));
        point.setIdentifier(identifierByProperty.get(mapping.getPropertyId()));
        point.setAddress(mapping.getRawAddress());
        point.setAddressType(mapping.getAddressType());
        point.setIntervalMs(mapping.getPollIntervalMs());
        point.setScaleFactor(mapping.getScaleFactor());
        point.setOffsetValue(mapping.getOffsetValue());
        point.setByteOrder(mapping.getByteOrder());
        point.setRw(mapping.getRw());
        return point;
    }

    /** 设备级采集周期取各点位周期的最小值（协议栈的 DeviceSpec 只有设备级周期）。 */
    private Integer minInterval(List<IotPointMapping> mappings) {
        return mappings.stream()
            .map(IotPointMapping::getPollIntervalMs)
            .filter(value -> value != null && value > 0)
            .min(Integer::compareTo)
            .orElse(null);
    }
}
