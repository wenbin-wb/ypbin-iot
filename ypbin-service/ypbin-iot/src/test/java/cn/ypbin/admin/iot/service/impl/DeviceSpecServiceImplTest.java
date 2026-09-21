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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备采集规格服务单测（access 采集的取数入口）。
 *
 * <p>守三件事：① 无设备/无点位时**短路**、绝不发空 IN；② 设备级周期取各点位周期的**最小值**；
 * ③ 查询跑在**调用方传入的租户上下文**里（无此绑定则租户插件 fail-closed，整个采集取数不可用）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class DeviceSpecServiceImplTest {

    private static final Long TENANT = 11L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotPointMappingMapper mappingMapper = mock(IotPointMappingMapper.class);
    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final DeviceSpecServiceImpl service =
        new DeviceSpecServiceImpl(deviceMapper, mappingMapper, propertyMapper);

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(IotDevice.class, IotPointMapping.class, IotProperty.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                entity);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("tenantId 为空：直接返回空集合，不查任何表")
    void nullTenantShouldShortCircuit() {
        assertThat(service.listByTenant(null)).isEmpty();
        verify(deviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("租户无设备：返回空集合，且不再去查点位（不发空 IN）")
    void noDeviceShouldShortCircuit() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listByTenant(TENANT)).isEmpty();
        verify(mappingMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("设备无点位：仍返回设备（点位为空列表），且不查属性标识")
    void deviceWithoutMappingShouldNotQueryProperties() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        when(mappingMapper.selectList(any())).thenReturn(List.of());

        List<AccessDeviceSpecResp> specs = service.listByTenant(TENANT);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().getPoints()).isEmpty();
        assertThat(specs.getFirst().getPollIntervalMs()).isNull();
        verify(propertyMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("装配：connectionId 自带租户、点位带属性标识、设备级周期取点位最小值")
    void shouldAssembleSpecWithPoints() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        IotPointMapping fast = mapping(100L, 900L, "holding:1", 1000);
        IotPointMapping slow = mapping(100L, 901L, "holding:2", 5000);
        when(mappingMapper.selectList(any())).thenReturn(List.of(fast, slow));
        when(propertyMapper.selectBatchIds(any())).thenReturn(List.of(property(900L, "temperature"),
            property(901L, "humidity")));

        AccessDeviceSpecResp spec = service.listByTenant(TENANT).getFirst();

        assertThat(spec.getConnectionId()).isEqualTo("t" + TENANT + "-d100");
        assertThat(spec.getDeviceId()).isEqualTo("100");
        assertThat(spec.getProtocol()).isEqualTo("modbus");
        assertThat(spec.getPollIntervalMs()).as("取点位周期最小值").isEqualTo(1000);
        assertThat(spec.getPoints()).hasSize(2);
        assertThat(spec.getPoints().get(0).getIdentifier()).isEqualTo("temperature");
        assertThat(spec.getPoints().get(0).getAddress()).isEqualTo("holding:1");
        assertThat(spec.getPoints().get(0).getPropertyId()).isEqualTo("900");
        assertThat(spec.getPoints().get(0).getRw()).isEqualTo("RW");
    }

    @Test
    @DisplayName("★ 查询必须跑在调用方传入的租户上下文里（否则租户插件 fail-closed，取数整块不可用）")
    void queryMustRunInsideGivenTenantContext() {
        AtomicReference<Optional<Long>> seenTenant = new AtomicReference<>();
        when(deviceMapper.selectList(any())).thenAnswer(invocation -> {
            seenTenant.set(TenantContext.getTenantId());
            return List.of(device(100L));
        });
        when(mappingMapper.selectList(any())).thenReturn(List.of());

        service.listByTenant(TENANT);

        assertThat(seenTenant.get()).as("查设备时租户上下文必须是入参 tenantId")
            .contains(TENANT);
    }

    private static IotDevice device(Long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setDeviceName("水表-" + id);
        device.setProtocol("modbus");
        device.setEndpoint("tcp://127.0.0.1:15002");
        device.setStatus(1);
        return device;
    }

    private static IotPointMapping mapping(Long deviceId, Long propertyId, String address, int interval) {
        IotPointMapping mapping = new IotPointMapping();
        mapping.setDeviceId(deviceId);
        mapping.setPropertyId(propertyId);
        mapping.setRawAddress(address);
        mapping.setAddressType("holding");
        mapping.setPollIntervalMs(interval);
        mapping.setEnabled(Boolean.TRUE);
        mapping.setRw("RW");
        mapping.setScaleFactor(new BigDecimal("0.1"));
        return mapping;
    }

    private static IotProperty property(Long id, String identifier) {
        IotProperty property = new IotProperty();
        property.setId(id);
        property.setIdentifier(identifier);
        return property;
    }
}
