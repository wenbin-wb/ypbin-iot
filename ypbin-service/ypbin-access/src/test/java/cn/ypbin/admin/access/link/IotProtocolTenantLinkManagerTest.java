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

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实链路管理器的单测（增量 3b-2）。
 *
 * <p>核心守卫是<b>revision 单调递增</b>：框架按设备记录「已应用的 revision」，收到 ≤ 它的变更
 * <b>直接丢弃、无异常无日志</b>。所以这里不仅断言「变更发出去了」，还用一个实现了同款去重规则的
 * 假框架验证「真的被应用了」——否则测试会在 revision 复用时依然全绿（假成功）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class IotProtocolTenantLinkManagerTest {

    private static final Long TENANT_A = 11L;
    private static final Long TENANT_B = 22L;

    private FakeSource source;
    private AccessDeviceRegistry registry;
    private IotProtocolTenantLinkManager linkManager;
    private final List<Integer> subscribedBatchSizes = new ArrayList<>();
    private FakeFramework framework;

    @BeforeEach
    void setUp() {
        source = new FakeSource();
        registry = new AccessDeviceRegistry(source, () -> Set.of(TENANT_A, TENANT_B));
        linkManager = new IotProtocolTenantLinkManager(source, registry, devices -> {
            subscribedBatchSizes.add(devices.size());
            return devices.size();
        });
        framework = new FakeFramework();
        registry.addChangeListener(framework::onChange);
    }

    @Test
    @DisplayName("开始采集：为该租户每台设备发 ADD，且 revision 按设备严格递增")
    void startCollectingShouldEmitAddPerDevice() {
        source.devices.put(TENANT_A, List.of(device("d1"), device("d2")));

        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).containsExactly(
            "ADD:d1", "ADD:d2");
        assertThat(linkManager.isCollecting(TENANT_A)).isTrue();
        assertThat(linkManager.collectingTenants()).containsExactly(TENANT_A);
        assertThat(subscribedBatchSizes).as("ADD 之后必须发起订阅（框架不主动订阅）")
            .containsExactly(2);
    }

    @Test
    @DisplayName("重复开始采集幂等：不产生重复 ADD")
    void repeatedStartShouldBeIdempotent() {
        source.devices.put(TENANT_A, List.of(device("d1")));

        linkManager.startCollecting(TENANT_A);
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).containsExactly("ADD:d1");
    }

    @Test
    @DisplayName("★ 对账：每轮都必须重试订阅（启动期无会话→0，下一周期会话就绪→补上），ADD 不得重复")
    void repeatedStartShouldReconcileSubscription() {
        source.devices.put(TENANT_A, List.of(device("d1")));

        linkManager.startCollecting(TENANT_A);
        linkManager.startCollecting(TENANT_A);

        // 这条钉住 B1 的修复机制：启动期（ApplicationRunner 早于 ApplicationReadyEvent）没有会话，
        // 订阅必须靠「下一个租约周期再对账」补上——否则采集恒为 0 且不自愈
        assertThat(subscribedBatchSizes).as("每轮都要对账").containsExactly(1, 1);
        assertThat(framework.actions).as("ADD 只在首次采集时发，不得重复建链").containsExactly("ADD:d1");
    }

    @Test
    @DisplayName("★ 空清单不得被缓存：取数失败（返回空）后必须在下一轮重新取数并对账订阅")
    void emptyDeviceListMustNotBePinned() {
        // 第一轮：取数失败（source 返回空）——不得把「零设备」钉死
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).isEmpty();

        // 第二轮：内部接口恢复，设备出现 ⇒ 必须能取到并建链 + 订阅
        source.devices.put(TENANT_A, List.of(device("d1")));
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).as("恢复后必须重新取数并建链").containsExactly("ADD:d1");
        assertThat(subscribedBatchSizes).as("恢复后必须对账订阅").containsExactly(1);
    }

    @Test
    @DisplayName("断链：发 REMOVE 并复用 ADD 时的同一份规格；重复断链是空操作")
    void fenceShouldEmitRemoveAndBeIdempotent() {
        source.devices.put(TENANT_A, List.of(device("d1")));
        linkManager.startCollecting(TENANT_A);

        linkManager.fence(TENANT_A, "租约丢失");
        linkManager.fence(TENANT_A, "再次调用");

        assertThat(framework.actions).containsExactly("ADD:d1", "REMOVE:d1");
        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        // REMOVE 用的是 ADD 时那份规格（不重新拉远端配置）
        assertThat(framework.lastRemoveSpec.deviceId()).isEqualTo("d1");
        assertThat(framework.lastRemoveSpec.protocol()).isEqualTo(ProtocolCode.of("tcp"));
    }

    @Test
    @DisplayName("★ revision 单调：start→fence→start 三轮的 revision 必须严格递增（否则被框架静默丢弃）")
    void revisionMustIncreaseStrictlyAcrossLifecycle() {
        source.devices.put(TENANT_A, List.of(device("d1")));

        linkManager.startCollecting(TENANT_A);
        linkManager.fence(TENANT_A, "重领");
        linkManager.startCollecting(TENANT_A);

        // 假框架实现了与真框架相同的去重规则：若 revision 未递增，第二、三条会被丢弃
        assertThat(framework.actions)
            .as("三轮变更都必须被应用；若 revision 复用，框架会静默丢弃后两条")
            .containsExactly("ADD:d1", "REMOVE:d1", "ADD:d1");
        assertThat(framework.revisions).hasSize(3);
        assertThat(framework.revisions.get(0)).isLessThan(framework.revisions.get(1));
        assertThat(framework.revisions.get(1)).isLessThan(framework.revisions.get(2));
    }

    @Test
    @DisplayName("fenceAll：把所有在采租户逐个断链")
    void fenceAllShouldFenceEveryTenant() {
        source.devices.put(TENANT_A, List.of(device("d1")));
        source.devices.put(TENANT_B, List.of(device("d2")));
        linkManager.startCollecting(TENANT_A);
        linkManager.startCollecting(TENANT_B);

        linkManager.fenceAll("节点失效");

        // 租户维度的遍历顺序不保证（ConcurrentHashMap），故按语义分组断言而不锁全局顺序
        assertThat(framework.actions).filteredOn(action -> action.startsWith("ADD:"))
            .containsExactlyInAnyOrder("ADD:d1", "ADD:d2");
        assertThat(framework.actions).filteredOn(action -> action.startsWith("REMOVE:"))
            .containsExactlyInAnyOrder("REMOVE:d1", "REMOVE:d2");
        assertThat(linkManager.collectingTenants()).isEmpty();
    }

    @Test
    @DisplayName("无可采设备：不报错、不产生变更，但该租户仍算「在采」")
    void noDeviceShouldNotFail() {
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).isEmpty();
        assertThat(linkManager.isCollecting(TENANT_A)).isTrue();
    }

    private static DeviceSpec device(String deviceId) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("tcp"), "link-" + deviceId,
            "tcp://127.0.0.1:15002", Duration.ofSeconds(5), Map.of("points", "[]"));
    }

    /** 假取数源。 */
    private static final class FakeSource implements DeviceSpecSource {

        private final Map<Long, List<DeviceSpec>> devices = new HashMap<>();

        @Override
        public List<DeviceSpec> loadByTenant(Long tenantId) {
            return devices.getOrDefault(tenantId, List.of());
        }

        @Override
        public Optional<ConnectionSpec> findConnection(String connectionId) {
            return Optional.of(ConnectionSpec.of(connectionId, ProtocolCode.of("tcp"),
                Endpoint.of("tcp://127.0.0.1:15002")));
        }
    }

    /** 假框架：复刻「按设备记录已应用 revision、≤ 则丢弃」的规则。 */
    private static final class FakeFramework {

        private final Map<String, Long> applied = new HashMap<>();
        private final List<String> actions = new ArrayList<>();
        private final List<Long> revisions = new ArrayList<>();
        private DeviceSpec lastRemoveSpec;

        void onChange(DeviceChange change) {
            String deviceId = change.device().deviceId();
            Long last = applied.get(deviceId);
            if (last != null && change.revision() <= last) {
                return;
            }
            applied.put(deviceId, change.revision());
            revisions.add(change.revision());
            actions.add(change.type() + ":" + deviceId);
            if (change.type() == ChangeType.REMOVE) {
                lastRemoveSpec = change.device();
            }
        }
    }
}
