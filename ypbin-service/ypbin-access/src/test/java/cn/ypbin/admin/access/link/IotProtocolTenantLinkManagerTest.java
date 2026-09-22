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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实链路管理器的单测（增量 3b-2 + M-2 配置变更对账）。
 *
 * <p>核心守卫是<b>revision 单调递增</b>：框架按设备记录「已应用的 revision」，收到 ≤ 它的变更
 * <b>直接丢弃、无异常无日志</b>。所以这里不仅断言「变更发出去了」，还用一个实现了同款去重规则的
 * 假框架验证「真的被应用了」——否则测试会在 revision 复用时依然全绿（假成功）。</p>
 *
 * <p>M-2 追加的守卫：<b>配置变更对账</b>（新增/消失/规格变化三类差异都必须被应用）、
 * <b>取数失败与「确实没有设备」必须分开</b>（失败不得下架既有设备、不得被当成已对账）、
 * 以及 <b>fence 后重取必须立即生效</b>（退避状态不得跨越 fence 泄漏）。</p>
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

    /** 可推进的假时钟：退避是时间相关行为，必须能「把时间推过去」而不是靠 sleep。 */
    private final MutableClock clock = new MutableClock();
    private RecordingPlanner planner;
    private FakeFramework framework;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        source = new FakeSource();
        registry = new AccessDeviceRegistry(source, () -> Set.of(TENANT_A, TENANT_B));
        planner = new RecordingPlanner();
        meterRegistry = new SimpleMeterRegistry();
        linkManager = new IotProtocolTenantLinkManager(source, registry, planner, meterRegistry, clock);
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
        assertThat(planner.subscribedBatchSizes).as("ADD 之后必须发起订阅（框架不主动订阅）")
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
        assertThat(planner.subscribedBatchSizes).as("每轮都要对账").containsExactly(1, 1);
        assertThat(framework.actions).as("ADD 只在首次采集时发，不得重复建链").containsExactly("ADD:d1");
    }

    @Test
    @DisplayName("★ N-1+S7：空清单不缓存（退避后必须重取），且**退避窗口内不得再打远端**")
    void emptyDeviceListMustNotBePinnedAndMustBackOff() {
        // 第一轮：上游成功信封但列表为空 ⇒ 不缓存、进入退避
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).isEmpty();
        assertThat(source.loadCallCount(TENANT_A)).as("第一轮应取数一次").isEqualTo(1);
        assertThat(meterRegistry.get("iot.access.spec.empty").counter().count()).isEqualTo(1.0d);

        // 退避窗口内再被调用（每个租约周期都会调用 startCollecting）：不得再打远端
        linkManager.startCollecting(TENANT_A);
        assertThat(source.loadCallCount(TENANT_A))
            .as("S7：退避窗口内不得重取（否则零设备租户每 15s 一次调用 + 一条 WARN）")
            .isEqualTo(1);

        // 推过退避窗口后设备出现 ⇒ 必须重新取数、建链并订阅（N-1 的自愈不能被退避破坏）
        clock.advance(IotProtocolTenantLinkManager.BACKOFF_BASE.plusSeconds(1));
        source.devices.put(TENANT_A, List.of(device("d1")));
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).as("恢复后必须重新取数并建链").containsExactly("ADD:d1");
        assertThat(planner.subscribedBatchSizes).as("恢复后必须对账订阅").containsExactly(1);
        assertThat(source.loadCallCount(TENANT_A)).as("退避到期后应重取一次").isEqualTo(2);
    }

    @Test
    @DisplayName("★ 取数**失败**（抛异常）不得被当成「没有设备」：不下架、计入失败指标、退避后重试")
    void loadFailureMustNotBeTreatedAsEmpty() {
        source.devices.put(TENANT_A, List.of(device("d1")));
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).containsExactly("ADD:d1");

        // 上游接口开始失败：对账必须返回 false（调用方据此不推进版本号）
        source.failing.add(TENANT_A);
        assertThat(linkManager.reconcile(TENANT_A)).isFalse();
        assertThat(framework.actions).as("取数失败绝不能把既有设备下架").containsExactly("ADD:d1");
        assertThat(meterRegistry.get("iot.access.spec.failure").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("iot.access.spec.empty").counter().count())
            .as("失败不得计入「空清单」").isZero();

        // 失败也要退避（否则持续失败会每周期打一次远端）
        int loads = source.loadCallCount(TENANT_A);
        assertThat(linkManager.reconcile(TENANT_A)).isFalse();
        assertThat(source.loadCallCount(TENANT_A)).as("失败退避窗口内不得再打远端").isEqualTo(loads);

        // 退避到期且上游恢复 ⇒ 对账成功，并应用最新清单
        clock.advance(IotProtocolTenantLinkManager.BACKOFF_BASE.plusSeconds(1));
        source.failing.clear();
        source.devices.put(TENANT_A, List.of(device("d1"), device("d3")));
        assertThat(linkManager.reconcile(TENANT_A)).isTrue();
        assertThat(framework.actions).contains("ADD:d3");
    }

    @Test
    @DisplayName("★ 配置变更对账：新增→ADD、消失→REMOVE+清理订阅跟踪、规格变化→重新 ADD")
    void reconcileShouldApplyAddRemoveAndReplace() {
        source.devices.put(TENANT_A, List.of(device("d1"), device("d2")));
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).containsExactly("ADD:d1", "ADD:d2");

        // 上游改配置：d1 的点位变了、d2 没了、d3 是新增的
        source.devices.put(TENANT_A,
            List.of(deviceWithPoints("d1", "[{\"address\":\"holding:9\"}]"), device("d3")));

        assertThat(linkManager.reconcile(TENANT_A)).isTrue();

        assertThat(framework.actions).as("消失的设备必须 REMOVE、新增的设备必须 ADD")
            .contains("REMOVE:d2", "ADD:d3");
        // 假框架会丢弃 revision ≤ 已应用值的变更 ⇒ 第二次 ADD:d1 出现即证明「规格变化被重新应用」
        assertThat(framework.actions.stream().filter("ADD:d1"::equals).count())
            .as("规格变化的设备必须重新 ADD（框架按「先解绑再绑定」应用，新会话会触发重订阅）")
            .isEqualTo(2);
        assertThat(planner.forgotten).as("消失的设备必须清理订阅跟踪").containsExactly("d2");
        assertThat(planner.subscribedBatchSizes).as("对账后必须再发起一轮订阅").hasSize(2);
    }

    @Test
    @DisplayName("★ 对账：上游答「确实没有设备」（成功信封 + 空列表）⇒ 必须全部下架（G7 的删除面）")
    void reconcileShouldRemoveAllWhenUpstreamReallyHasNoDevice() {
        source.devices.put(TENANT_A, List.of(device("d1"), device("d2")));
        linkManager.startCollecting(TENANT_A);

        source.devices.put(TENANT_A, List.of());
        assertThat(linkManager.reconcile(TENANT_A)).as("「确实没有设备」是有效对账结果").isTrue();

        assertThat(framework.actions).filteredOn(action -> action.startsWith("REMOVE:"))
            .containsExactlyInAnyOrder("REMOVE:d1", "REMOVE:d2");
        assertThat(planner.forgotten).containsExactlyInAnyOrder("d1", "d2");
        assertThat(planner.subscribedBatchSizes).as("没有设备可订阅：不得再发起订阅").containsExactly(2);
    }

    @Test
    @DisplayName("对账：不在采集的租户不得取数、不得推设备（fence 后不能复活）")
    void reconcileMustBeNoopWhenNotCollecting() {
        source.devices.put(TENANT_B, List.of(device("d9")));

        assertThat(linkManager.reconcile(TENANT_B)).isFalse();

        assertThat(source.loadCallCount(TENANT_B)).isZero();
        assertThat(framework.actions).isEmpty();
    }

    @Test
    @DisplayName("对账：尚未成功取过清单（启动期为空的租户）交还给 startCollecting 的重取路径")
    void reconcileBeforeFirstSuccessMustDelegateToStartCollecting() {
        linkManager.startCollecting(TENANT_A);
        int loads = source.loadCallCount(TENANT_A);

        assertThat(linkManager.reconcile(TENANT_A)).isFalse();

        assertThat(source.loadCallCount(TENANT_A)).as("不得与 startCollecting 的退避重取重复打远端")
            .isEqualTo(loads);
    }

    @Test
    @DisplayName("★ N-2：设备仍无会话时必须**重发 ADD**（建链失败/设备离线可自愈），且不得每轮猛重试")
    void rebindMustRetryWhenDeviceHasNoSession() {
        source.devices.put(TENANT_A, List.of(device("d1")));
        planner.missingSessions.add("d1");

        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).as("首次采集只发一次 ADD（同一轮不得立刻再发）")
            .containsExactly("ADD:d1");

        // 退避窗口内（30s）不得重发
        clock.advance(IotProtocolTenantLinkManager.REBIND_BACKOFF_BASE.minusSeconds(1));
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).as("退避窗口内不得重发 ADD").containsExactly("ADD:d1");

        // 退避到期 ⇒ 重发 ADD（框架会重新 bind；revision 必须继续递增，否则被框架静默丢弃）
        clock.advance(Duration.ofSeconds(2));
        linkManager.startCollecting(TENANT_A);
        assertThat(framework.actions).as("退避到期必须重发 ADD（N-2 的自愈）")
            .containsExactly("ADD:d1", "ADD:d1");
        assertThat(meterRegistry.get("iot.access.device.rebind").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ N-2：有会话的设备不得被重发 ADD（否则每轮都在重建链）")
    void rebindMustNotTouchDevicesWithSession() {
        source.devices.put(TENANT_A, List.of(device("d1")));

        linkManager.startCollecting(TENANT_A);
        clock.advance(Duration.ofMinutes(5));
        linkManager.startCollecting(TENANT_A);
        clock.advance(Duration.ofMinutes(5));
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).containsExactly("ADD:d1");
        assertThat(meterRegistry.get("iot.access.device.rebind").counter().count()).isZero();
    }

    @Test
    @DisplayName("★ N-2：会话一旦建立就清掉重发退避（会话消失后必须能立刻重试）")
    void rebindBackoffMustBeClearedWhenSessionAppears() {
        source.devices.put(TENANT_A, List.of(device("d1")));
        planner.missingSessions.add("d1");
        linkManager.startCollecting(TENANT_A);

        // 会话建立（规划器不再报缺会话）⇒ 下一轮清掉退避
        planner.missingSessions.clear();
        linkManager.startCollecting(TENANT_A);

        // 会话又没了：因为退避已被清掉，本轮就该重发（不用再等 30s）
        planner.missingSessions.add("d1");
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions).as("清掉退避后应立刻重发").containsExactly("ADD:d1", "ADD:d1");
    }

    @Test
    @DisplayName("★ N-2：每轮重发有上限（整批设备离线时不得把框架与远端打爆），多余的计入 deferred")
    void rebindMustRespectPerCycleCap() {
        List<DeviceSpec> many = new ArrayList<>();
        for (int i = 1; i <= IotProtocolTenantLinkManager.REBIND_MAX_PER_CYCLE + 1; i++) {
            many.add(device("d" + i));
            planner.missingSessions.add("d" + i);
        }
        source.devices.put(TENANT_A, many);

        linkManager.startCollecting(TENANT_A);
        int addsAfterFirstLoad = framework.actions.size();
        clock.advance(IotProtocolTenantLinkManager.REBIND_BACKOFF_BASE.plusSeconds(1));
        linkManager.startCollecting(TENANT_A);

        assertThat(framework.actions.size() - addsAfterFirstLoad)
            .as("每轮最多重发 %s 个", IotProtocolTenantLinkManager.REBIND_MAX_PER_CYCLE)
            .isEqualTo(IotProtocolTenantLinkManager.REBIND_MAX_PER_CYCLE);
        assertThat(meterRegistry.get("iot.access.device.rebind.deferred").counter().count())
            .as("超出的 1 个计入 deferred").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ fence 必须清掉退避状态：重新领取后立刻重取（不能等满退避窗口）")
    void fenceMustClearBackoffSoReacquireRefetchesImmediately() {
        // 先制造退避：空清单 ⇒ 下一次重取要等 30s
        linkManager.startCollecting(TENANT_A);
        assertThat(source.loadCallCount(TENANT_A)).isEqualTo(1);

        linkManager.fence(TENANT_A, "租约丢失");
        source.devices.put(TENANT_A, List.of(device("d1")));

        // 时间**没有**推进：若 fence 没清退避，这里会因仍在退避窗口内而跳过取数 ⇒ 采不到
        linkManager.startCollecting(TENANT_A);

        assertThat(source.loadCallCount(TENANT_A)).as("fence 后重取必须立即生效").isEqualTo(2);
        assertThat(framework.actions).containsExactly("ADD:d1");
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
        return deviceWithPoints(deviceId, "[]");
    }

    private static DeviceSpec deviceWithPoints(String deviceId, String pointsJson) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("tcp"), "link-" + deviceId,
            "tcp://127.0.0.1:15002", Duration.ofSeconds(5), Map.of("points", pointsJson));
    }

    /** 假取数源：{@code devices} 是「成功信封的答案」，{@code failing} 里的租户模拟**取数失败**（抛异常）。 */
    private static final class FakeSource implements DeviceSpecSource {

        private final Map<Long, List<DeviceSpec>> devices = new HashMap<>();

        /** 取数调用次数（S7 用它证明「退避窗口内不再打远端」）。 */
        private final Map<Long, Integer> loadCalls = new HashMap<>();

        private final Set<Long> failing = new HashSet<>();

        @Override
        public List<DeviceSpec> loadByTenant(Long tenantId) {
            loadCalls.merge(tenantId, 1, Integer::sum);
            if (failing.contains(tenantId)) {
                throw new DeviceSpecLoadException("模拟取数失败：tenantId=" + tenantId);
            }
            return devices.getOrDefault(tenantId, List.of());
        }

        int loadCallCount(Long tenantId) {
            return loadCalls.getOrDefault(tenantId, 0);
        }

        @Override
        public Optional<ConnectionSpec> findConnection(String connectionId) {
            return Optional.of(ConnectionSpec.of(connectionId, ProtocolCode.of("tcp"),
                Endpoint.of("tcp://127.0.0.1:15002")));
        }
    }

    /** 记录订阅批次的替身（同时记录 forget 与「无会话设备」，后者供 N-2 用例驱动）。 */
    private static final class RecordingPlanner implements SubscriptionPlanner {

        private final List<Integer> subscribedBatchSizes = new ArrayList<>();
        private final List<String> forgotten = new ArrayList<>();

        /** 当前「没有会话」的设备（默认空＝都有会话）。 */
        private final Set<String> missingSessions = new HashSet<>();

        @Override
        public int subscribe(List<DeviceSpec> devices) {
            subscribedBatchSizes.add(devices.size());
            return devices.size();
        }

        @Override
        public void forget(String deviceId) {
            forgotten.add(deviceId);
        }

        @Override
        public Set<String> devicesWithoutSession(List<DeviceSpec> devices) {
            Set<String> missing = new HashSet<>();
            for (DeviceSpec device : devices) {
                if (missingSessions.contains(device.deviceId())) {
                    missing.add(device.deviceId());
                }
            }
            return missing;
        }
    }

    /** 可推进时钟（仅用于测试退避窗口）。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-21T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
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
