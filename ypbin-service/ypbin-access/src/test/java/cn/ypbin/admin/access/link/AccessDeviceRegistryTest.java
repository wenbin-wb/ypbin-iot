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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备注册表单测：守「只喂本节点租约内的设备」这条安全语义。
 *
 * <p>这条为什么必须测：框架在 {@code ApplicationReadyEvent} 调一次 {@code loadAll()} 就按返回结果
 * 建链采集；一旦把非本节点租户的设备也返回，就会**重复采集别的节点负责的租户**（多副本下表现为
 * 数据重复/租户串采），且没有任何报错。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessDeviceRegistryTest {

    private static final Long TENANT_HELD_A = 11L;
    private static final Long TENANT_HELD_B = 22L;
    private static final Long TENANT_NOT_HELD = 33L;

    @Test
    @DisplayName("loadAll 只取本节点租约内的租户；非持有租户连查都不查")
    void loadAllShouldOnlyQueryHeldTenants() {
        DeviceSpecSource source = mock(DeviceSpecSource.class);
        when(source.loadByTenant(TENANT_HELD_A)).thenReturn(List.of(device("a1")));
        when(source.loadByTenant(TENANT_HELD_B)).thenReturn(List.of(device("b1"), device("b2")));
        when(source.loadByTenant(TENANT_NOT_HELD)).thenReturn(List.of(device("c1")));

        AccessDeviceRegistry registry = new AccessDeviceRegistry(source,
            () -> Set.of(TENANT_HELD_A, TENANT_HELD_B));

        List<DeviceSpec> devices = registry.loadAll();

        assertThat(devices).extracting(DeviceSpec::deviceId)
            .containsExactlyInAnyOrder("a1", "b1", "b2");
        verify(source, never()).loadByTenant(TENANT_NOT_HELD);
    }

    @Test
    @DisplayName("暂无租约：返回空集合且完全不查远端（启动早于领取时不应拉全量）")
    void loadAllWithoutLeaseShouldReturnEmpty() {
        DeviceSpecSource source = mock(DeviceSpecSource.class);
        AccessDeviceRegistry registry = new AccessDeviceRegistry(source, Set::of);

        assertThat(registry.loadAll()).isEmpty();
        verify(source, never()).loadByTenant(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("租约变化后 loadAll 随之变化（同一实例反复调用结果不同）")
    void loadAllShouldFollowLeaseChanges() {
        DeviceSpecSource source = mock(DeviceSpecSource.class);
        when(source.loadByTenant(TENANT_HELD_A)).thenReturn(List.of(device("a1")));
        AtomicReference<Set<Long>> held = new AtomicReference<>(Set.of());
        AccessDeviceRegistry registry = new AccessDeviceRegistry(source, held::get);

        assertThat(registry.loadAll()).isEmpty();
        held.set(Set.of(TENANT_HELD_A));
        assertThat(registry.loadAll()).extracting(DeviceSpec::deviceId).containsExactly("a1");
    }

    @Test
    @DisplayName("★ 单个租户取数失败不得中断引导：跳过该租户，其余租户照常返回")
    void loadAllShouldTolerateOneTenantFailure() {
        DeviceSpecSource source = mock(DeviceSpecSource.class);
        when(source.loadByTenant(TENANT_HELD_A))
            .thenThrow(new DeviceSpecLoadException("iot 内部接口不可达"));
        when(source.loadByTenant(TENANT_HELD_B)).thenReturn(List.of(device("b1")));

        AccessDeviceRegistry registry = new AccessDeviceRegistry(source,
            () -> Set.of(TENANT_HELD_A, TENANT_HELD_B));

        // 引导（ApplicationReadyEvent 的一次性调用）不得因为一个租户取数失败而整体抛断：
        // 那会让整个协议栈起不来；正确行为是「本轮少采一个租户」，运行期由配置对账补齐
        assertThat(registry.loadAll()).extracting(DeviceSpec::deviceId).containsExactly("b1");
    }

    @Test
    @DisplayName("未接线时 emit 不抛异常且被丢弃（监听器数量为 0）——框架接线由 addChangeListener 完成")
    void emitWithoutListenerShouldBeDroppedSafely() {
        AccessDeviceRegistry registry = new AccessDeviceRegistry(mock(DeviceSpecSource.class), Set::of);

        registry.emit(new DeviceChange(ChangeType.ADD, device("d1"), registry.nextRevision("d1")));

        assertThat(registry.listenerCount()).isZero();
    }

    @Test
    @DisplayName("接线后 emit 投递给监听器；多条监听器都收到")
    void emitShouldFanOutToListeners() {
        AccessDeviceRegistry registry = new AccessDeviceRegistry(mock(DeviceSpecSource.class), Set::of);
        List<DeviceChange> first = new ArrayList<>();
        List<DeviceChange> second = new ArrayList<>();
        registry.addChangeListener(first::add);
        registry.addChangeListener(second::add);

        registry.emit(new DeviceChange(ChangeType.ADD, device("d1"), registry.nextRevision("d1")));

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(registry.listenerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("nextRevision 按设备独立严格递增（跨设备互不影响）")
    void nextRevisionShouldBeStrictlyIncreasingPerDevice() {
        AccessDeviceRegistry registry = new AccessDeviceRegistry(mock(DeviceSpecSource.class), Set::of);

        long d1First = registry.nextRevision("d1");
        long d1Second = registry.nextRevision("d1");
        long d2First = registry.nextRevision("d2");

        assertThat(d1Second).isGreaterThan(d1First);
        assertThat(d2First).isPositive();
        assertThat(registry.trackedRevisionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("连接参数缺失时返回 empty（框架据此跳过设备而不是中断引导）")
    void connectionProviderShouldReturnEmptyWhenMissing() {
        DeviceSpecSource source = mock(DeviceSpecSource.class);
        when(source.findConnection(anyString())).thenReturn(Optional.empty());
        AccessConnectionSpecProvider provider = new AccessConnectionSpecProvider(source);

        assertThat(provider.find("nope")).isEmpty();
        assertThat(provider.find("")).isEmpty();
        assertThat(provider.find(null)).isEmpty();
    }

    private static DeviceSpec device(String deviceId) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("tcp"), "link-" + deviceId,
            Endpoint.of("tcp://127.0.0.1:15002").uri(), Duration.ofSeconds(5), Map.of());
    }
}
