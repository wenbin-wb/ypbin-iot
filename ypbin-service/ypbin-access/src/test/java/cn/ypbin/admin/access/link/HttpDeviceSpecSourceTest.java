/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.admin.iot.device.IDeviceSpecClient;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.starter.core.model.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * access 取数实现单测。
 *
 * <p>守四件事：① 点位随 {@code properties} 传到协议栈（订阅规划器的输入）；② 失败**不静默**——
 * 非成功信封/调用异常都记 ERROR 且本轮按「无设备」处理；③ {@code connectionId} 能解析出租户，
 * 非法一律返回 {@code Optional.empty()}（框架据此跳过设备，而不是抛断整轮引导）；
 * ④ 一次租户拉取 + 建链回调**只打一次远端**（缓存生效）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class HttpDeviceSpecSourceTest {

    private static final Long TENANT = 11L;

    private final IDeviceSpecClient client = mock(IDeviceSpecClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpDeviceSpecSource source = new HttpDeviceSpecSource(client, objectMapper);

    @Test
    @DisplayName("loadByTenant：连同点位清单一起交给协议栈（properties.points 可反序列化回 DTO）")
    void loadByTenantShouldCarryPoints() throws Exception {
        when(client.listByTenant(TENANT)).thenReturn(R.ok(List.of(spec())));

        List<DeviceSpec> devices = source.loadByTenant(TENANT);

        assertThat(devices).hasSize(1);
        DeviceSpec device = devices.getFirst();
        assertThat(device.deviceId()).isEqualTo("100");
        assertThat(device.protocol().value()).isEqualTo("modbus");
        assertThat(device.connectionId()).isEqualTo("t11-d100");
        assertThat(device.pollInterval()).isEqualTo(Duration.ofMillis(1000));
        String pointsJson = device.properties().get(HttpDeviceSpecSource.PROPERTY_POINTS);
        assertThat(pointsJson).isNotBlank();
        List<AccessPointMappingDto> points = objectMapper.readValue(pointsJson,
            new TypeReference<>() { });
        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getIdentifier()).isEqualTo("temperature");
        assertThat(points.getFirst().getAddress()).isEqualTo("holding:1");
    }

    @Test
    @DisplayName("非成功信封：本轮按无设备处理（不抛），且不污染缓存")
    void failedEnvelopeShouldYieldNoDevice() {
        when(client.listByTenant(TENANT)).thenReturn(R.fail(500, "boom"));

        assertThat(source.loadByTenant(TENANT)).isEmpty();
        assertThat(source.findConnection("t11-d100")).as("失败不得留下半份缓存").isEmpty();
    }

    @Test
    @DisplayName("调用异常：同样按无设备处理（记 ERROR），不把异常抛给租约调度线程")
    void clientExceptionShouldYieldNoDevice() {
        when(client.listByTenant(anyLong())).thenThrow(new IllegalStateException("network down"));

        assertThat(source.loadByTenant(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("findConnection：解析 connectionId 中的租户并复用缓存，只打一次远端")
    void findConnectionShouldParseTenantAndReuseCache() {
        when(client.listByTenant(TENANT)).thenReturn(R.ok(List.of(spec())));

        source.loadByTenant(TENANT);
        Optional<ConnectionSpec> connection = source.findConnection("t11-d100");

        assertThat(connection).isPresent();
        assertThat(connection.get().endpoint().uri()).isEqualTo("tcp://127.0.0.1:15002");
        assertThat(connection.get().credentialRef()).isEqualTo("cred-1");
        verify(client, times(1)).listByTenant(TENANT);
    }

    @Test
    @DisplayName("findConnection：连接先于设备规格被问到时才补拉一次；非法 connectionId 一律 empty")
    void findConnectionShouldHandleColdCacheAndIllegalIds() {
        when(client.listByTenant(TENANT)).thenReturn(R.ok(List.of(spec())));

        assertThat(source.findConnection("t11-d100")).isPresent();
        assertThat(source.findConnection("t11-d999")).as("同租户但无此设备").isEmpty();
        verify(client, times(1)).listByTenant(TENANT);

        assertThat(source.findConnection(null)).isEmpty();
        assertThat(source.findConnection("")).isEmpty();
        assertThat(source.findConnection("no-marker")).isEmpty();
        assertThat(source.findConnection("tXd100")).as("租户号非数字").isEmpty();
        verify(client, times(1)).listByTenant(any());
    }

    private static AccessDeviceSpecResp spec() {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setPropertyId("900");
        point.setIdentifier("temperature");
        point.setAddress("holding:1");
        point.setAddressType("holding");
        point.setIntervalMs(1000);
        point.setRw("R");

        AccessDeviceSpecResp spec = new AccessDeviceSpecResp();
        spec.setDeviceId("100");
        spec.setDeviceName("水表-100");
        spec.setProtocol("modbus");
        spec.setConnectionId("t11-d100");
        spec.setEndpoint("tcp://127.0.0.1:15002");
        spec.setCredentialRef("cred-1");
        spec.setPollIntervalMs(1000);
        spec.setPoints(List.of(point));
        return spec;
    }
}
