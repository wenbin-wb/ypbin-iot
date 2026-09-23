/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.access.link.LoggingTenantLinkManager;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseRenewAck;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.LeaseState;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * access 租约状态机的四条硬要求（spec §3.1①）：启动握手 fail-fast、周期续约、
 * self-fencing（撤销/节点级失效/本地过期）、周期重领；外加 M-2 的配置版本对账接线。
 *
 * @author wenbin
 * @since 2026-09-20
 */
class AccessLeaseManagerTest {

    /** 可推进/可偏移的假时钟：时钟校准是时间相关行为，必须能控制而不是 sleep。 */
    private final MutableClock clock = new MutableClock();


    private static final String NODE = "access-1";
    private static final long TENANT_A = 11L;
    private static final long TENANT_B = 22L;

    private ILeaseClient client;
    private TenantLinkManager linkManager;
    private AccessProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private ConfigEpochReconciler reconciler;
    private AccessLeaseManager manager;

    @BeforeEach
    void setUp() {
        client = mock(ILeaseClient.class);
        // spy：既保留 LoggingTenantLinkManager 的真实行为（既有断言依赖它），又能断言对账调用
        linkManager = spy(new LoggingTenantLinkManager());
        properties = new AccessProperties();
        properties.setNodeId(NODE);
        properties.setAcquireIntervalMs(Long.MAX_VALUE);
        meterRegistry = new SimpleMeterRegistry();
        // spy：既要它真实工作（按版本号触发对账），又要能断言停采路径确实调了 forget
        reconciler = spy(new ConfigEpochReconciler(client, linkManager, meterRegistry,
            Clock.systemUTC(), 300_000L));
        manager = new AccessLeaseManager(client, linkManager, properties, meterRegistry, reconciler, clock);
        // 每个租约周期都会打一次 epoch 对账：默认给「没有任何条目」的成功信封，
        // 避免用例里出现 null 信封的错误日志（影响可读性，也会掩盖真问题）
        when(client.batchEpoch()).thenReturn(R.ok(epochBatch()));
    }

    @Test
    @DisplayName("握手成功：持有租户并开始采集")
    void startShouldHoldAndCollect() {
        startWith(TENANT_A, TENANT_B);

        assertThat(manager.heldTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(linkManager.collectingTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        ArgumentCaptor<AccessNodeRegisterReq> captor = ArgumentCaptor.forClass(AccessNodeRegisterReq.class);
        verify(client).register(captor.capture());
        assertThat(captor.getValue().getAccessNode()).isEqualTo(NODE);
    }

    @Test
    @DisplayName("握手失败即启动失败：注册非成功信封、领取非成功信封都要抛")
    void handshakeMustFailFast() {
        when(client.register(any())).thenReturn(R.fail(404, "接口不存在"));
        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("注册节点未成功");

        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.fail(500, "内部错误"));
        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("领取租约未成功");
    }

    @Test
    @DisplayName("握手失败即启动失败：传输异常也包成可行动消息")
    void handshakeMustFailFastOnTransportError() {
        when(client.register(any())).thenThrow(new IllegalStateException("Connection refused"));
        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法完成节点注册");
    }

    @Test
    @DisplayName("续约：回执刷新到期时间与 epoch；被回收的租户立即停采")
    void renewShouldRefreshAndFenceRevoked() {
        startWith(TENANT_A, TENANT_B);
        LocalDateTime newExpiry = LocalDateTime.now(clock).plusSeconds(60);
        LeaseRenewResp resp = new LeaseRenewResp();
        resp.setRenewedLeases(List.of(ack(TENANT_A, newExpiry, 3L)));
        resp.setRevokedTenantIds(List.of(TENANT_B));
        when(client.renew(any())).thenReturn(R.ok(resp));

        manager.renewAndSelfCheck();

        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(linkManager.isCollecting(TENANT_B)).isFalse();
        assertThat(meterRegistry.get("iot.access.lease.revoked").counter().count()).isEqualTo(1.0d);
        // epoch 回填：下次续约必须带服务端 epoch
        manager.renewAndSelfCheck();
        ArgumentCaptor<LeaseRenewReq> captor = ArgumentCaptor.forClass(LeaseRenewReq.class);
        verify(client, times(2)).renew(captor.capture());
        assertThat(captor.getAllValues().get(1).getLeases()).extracting(LeaseRenewItem::getEpoch)
            .containsExactly(3L);
    }

    @Test
    @DisplayName("★ M0b-4：本机钟**快**时不得提前自停采（判据必须校准到服务端时钟）")
    void fastLocalClockMustNotSelfFenceEarly() {
        LocalDateTime localNow = LocalDateTime.now(clock);
        // 服务端比本机慢 10 分钟（本机钟快）；租约按**服务端时间**还有 2 分钟
        //（即：不校准就会在「本机时刻」上看起来早过期了 9 分钟 ⇒ 误停采）
        LocalDateTime serverNow = localNow.minusMinutes(10);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(serverNow,
            assignmentAt(TENANT_A, serverNow.plusSeconds(120)))));
        manager.start();

        assertThat(manager.clockSkewSeconds())
            .as("偏移 = 服务端 - 本机 ⇒ 本机快时为负（允许 1s 往返误差）").isBetween(-601L, -599L);
        assertThat(meterRegistry.get("iot.access.lease.clock_skew_seconds").gauge().value())
            .as("偏移要上大盘").isBetween(-601.0d, -599.0d);

        // 本机时刻已比「租约到期时刻」晚了 8 分钟：若不校准就会误判过期而停采（数据凭空变少）
        manager.renewAndSelfCheck(localNow.plusMinutes(1));

        assertThat(manager.heldTenants()).as("按服务端时钟仍未到期，不得停采").containsExactly(TENANT_A);
        assertThat(linkManager.isCollecting(TENANT_A)).isTrue();
        assertThat(meterRegistry.get("iot.access.lease.self_fenced").counter().count()).isZero();
    }

    @Test
    @DisplayName("★ M0b-4：本机钟**慢**时必须按服务端时钟判定过期（否则服务端已接管仍在多采）")
    void slowLocalClockMustSelfFenceOnTime() {
        LocalDateTime localNow = LocalDateTime.now(clock);
        // 服务端比本机快 10 分钟（本机钟慢）；租约按服务端时间**已过期 1 秒**
        LocalDateTime serverNow = localNow.plusMinutes(10);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(serverNow,
            assignmentAt(TENANT_A, serverNow.minusSeconds(1)))));
        when(client.renew(any())).thenReturn(R.ok(new LeaseRenewResp()));
        manager.start();

        assertThat(manager.clockSkewSeconds())
            .as("本机慢时偏移为正（允许 1s 往返误差）").isBetween(599L, 601L);

        manager.renewAndSelfCheck(localNow.plusSeconds(1));

        assertThat(manager.heldTenants()).as("按服务端时钟已过期，必须自停采").isEmpty();
        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        assertThat(meterRegistry.get("iot.access.lease.self_fenced").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("服务端未回传时间时保留上一次校准（不得退回 0 把校准丢掉）")
    void missingServerTimeMustKeepPreviousSkew() {
        LocalDateTime serverNow = LocalDateTime.now(clock).plusMinutes(7);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(serverNow, assignment(TENANT_A))));
        manager.start();
        long calibrated = manager.clockSkewSeconds();
        assertThat(calibrated).isBetween(419L, 421L);

        // 续约回执不带 serverTime（如节点级否决）⇒ 沿用上次偏移
        LeaseRenewResp noServerTime = new LeaseRenewResp();
        noServerTime.setServerTime(null);
        when(client.renew(any())).thenReturn(R.ok(noServerTime));
        manager.renewAndSelfCheck(LocalDateTime.now(clock));

        assertThat(manager.clockSkewSeconds()).as("校准不得被清掉").isEqualTo(calibrated);
    }

    @Test
    @DisplayName("节点级失效：整体停采后重新注册并重新领取")
    void nodeFencedShouldRecover() {
        startWith(TENANT_A);
        LeaseRenewResp fenced = new LeaseRenewResp();
        fenced.setNodeFenced(true);
        when(client.renew(any())).thenReturn(R.ok(fenced));
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_B))));

        manager.renewAndSelfCheck();

        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        assertThat(manager.heldTenants()).containsExactly(TENANT_B);
        assertThat(manager.heldTenants()).doesNotContain(TENANT_A);
        verify(client, times(2)).register(any());
        assertThat(meterRegistry.get("iot.access.lease.node_fenced").counter().count()).isEqualTo(1.0d);
        // 节点级失效整体停采时也要逐个忘掉版本号（重领后必须重新对账）
        verify(reconciler).forget(TENANT_A);
    }

    @Test
    @DisplayName("本地过期自检：续约失败后到期必须自己停采（不依赖业务侧）")
    void localExpiryMustSelfFence() {
        // 该用例就是要测过期：显式用短 TTL（默认已改为长 TTL）
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A, 30))));
        manager.start();
        when(client.renew(any())).thenThrow(new IllegalStateException("iot 不可达"));

        manager.renewAndSelfCheck();
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(meterRegistry.get("iot.access.lease.renew.failure").counter().count()).isEqualTo(1.0d);

        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(120));

        assertThat(manager.heldTenants()).isEmpty();
        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        assertThat(meterRegistry.get("iot.access.lease.self_fenced").counter().count()).isEqualTo(1.0d);
        // 本地过期自停采也必须忘掉配置版本号，否则重新领取后会拿旧版本号「以为不用对账」
        verify(reconciler).forget(TENANT_A);
    }

    @Test
    @DisplayName("周期重领：到点后把待接管/新分配租户接过来；未到点不重复领取")
    void refreshShouldTakeOverOrphansWhenDue() {
        properties.setAcquireIntervalMs(60_000L);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A))));
        manager.start();
        when(client.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A), assignment(TENANT_B))));

        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(10));
        verify(client, times(1)).acquire(any());

        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(70));
        verify(client, times(2)).acquire(any());
        assertThat(manager.heldTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(meterRegistry.get("iot.access.lease.acquired").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 配置版本变化必须触达链路管理器（P4 接线）；版本不变则不再触发")
    void configEpochChangeShouldReachLinkManager() {
        when(client.batchEpoch()).thenReturn(R.ok(epochBatch(epochItem(TENANT_A, 1L))));
        startWith(TENANT_A);

        manager.renewAndSelfCheck();
        assertThat(reconciler.trackedTenantCount()).as("首次观测必须对账一次").isEqualTo(1);
        verify(linkManager, times(1)).reconcile(TENANT_A);

        manager.renewAndSelfCheck();
        verify(linkManager, times(1)).reconcile(TENANT_A);
        assertThat(meterRegistry.get("iot.access.config.changed").counter().count())
            .as("版本号没变不得重复对账").isZero();

        when(client.batchEpoch()).thenReturn(R.ok(epochBatch(epochItem(TENANT_A, 2L))));
        manager.renewAndSelfCheck();
        verify(linkManager, times(2)).reconcile(TENANT_A);
        assertThat(meterRegistry.get("iot.access.config.changed").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 租户被回收时必须忘掉配置版本号：重新领取后要重新对账（fence 期间上游可能改过配置）")
    void revokedTenantMustForgetConfigEpoch() {
        when(client.batchEpoch()).thenReturn(R.ok(epochBatch(epochItem(TENANT_A, 1L))));
        startWith(TENANT_A);
        manager.renewAndSelfCheck();
        assertThat(reconciler.trackedTenantCount()).isEqualTo(1);

        LeaseRenewResp resp = new LeaseRenewResp();
        resp.setRenewedLeases(List.of());
        resp.setRevokedTenantIds(List.of(TENANT_A));
        when(client.renew(any())).thenReturn(R.ok(resp));

        manager.renewAndSelfCheck();

        assertThat(manager.heldTenants()).isEmpty();
        assertThat(reconciler.trackedTenantCount()).as("回收后必须清掉版本号记录").isZero();
    }

    @Test
    @DisplayName("握手完成前：既不重领也**不补注册**（防调度器抢跑；补注册会让抢跑窗口重新打开）")
    void refreshMustNotRunBeforeHandshake() {
        // register 必须 stub 成成功：否则本用例会靠「mock 返回 null 信封 → registerOrFail 抛错早退」而绿，
        // 通过的理由就不是「闸门存在」（复核用等价断言实证过：去掉闸门后会变成 register=1/acquire=1）
        when(client.register(any())).thenReturn(R.ok());

        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(600));

        verify(client, times(0)).register(any());
        verify(client, times(0)).acquire(any());
        verify(client, times(0)).renew(any());
        verify(client, times(0)).batchEpoch();
    }

    private void startWith(Long... tenantIds) {
        when(client.register(any())).thenReturn(R.ok());
        LeaseAssignmentDto[] assignments = new LeaseAssignmentDto[tenantIds.length];
        for (int i = 0; i < tenantIds.length; i++) {
            assignments[i] = assignment(tenantIds[i]);
        }
        when(client.acquire(any())).thenReturn(R.ok(acquireResp(assignments)));
        manager.start();
    }

    private LeaseAcquireResp acquireResp(LeaseAssignmentDto... assignments) {
        LeaseAcquireResp resp = new LeaseAcquireResp();
        resp.setAccessNode(NODE);
        resp.setAssignments(List.of(assignments));
        // 默认不带服务端时间：既有用例都走「未校准」路径（偏移 0），行为与修复前一致
        return resp;
    }

    /** 带**服务端时间**的领取响应（M0b-4 校时用例用）。 */
    private LeaseAcquireResp acquireRespAt(LocalDateTime serverTime, LeaseAssignmentDto... assignments) {
        LeaseAcquireResp resp = acquireResp(assignments);
        resp.setServerTime(serverTime);
        return resp;
    }

    /** 按**服务端时间**给出到期时刻的归属（本机时钟偏移用例用）。 */
    private LeaseAssignmentDto assignmentAt(Long tenantId, LocalDateTime leaseExpireAt) {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(NODE);
        dto.setLeaseExpireAt(leaseExpireAt);
        dto.setEpoch(1L);
        dto.setState(LeaseState.ACTIVE);
        return dto;
    }

    private LeaseAssignmentDto assignment(Long tenantId) {
        // 默认给长 TTL：避免「模拟时间推进」的用例把租户判过期而触发自检停采，干扰别的断言
        return assignment(tenantId, 600);
    }

    private LeaseAssignmentDto assignment(Long tenantId, long ttlSeconds) {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(NODE);
        dto.setLeaseExpireAt(LocalDateTime.now(clock).plusSeconds(ttlSeconds));
        dto.setEpoch(1L);
        dto.setState(LeaseState.ACTIVE);
        return dto;
    }

    private TenantEpochBatchResp epochBatch(TenantEpochItem... items) {
        TenantEpochBatchResp resp = new TenantEpochBatchResp();
        resp.setItems(List.of(items));
        resp.setReadAt(LocalDateTime.now(clock));
        return resp;
    }

    private TenantEpochItem epochItem(Long tenantId, long configEpoch) {
        TenantEpochItem item = new TenantEpochItem();
        item.setTenantId(tenantId);
        item.setEpoch(1L);
        item.setConfigEpoch(configEpoch);
        return item;
    }

    private LeaseRenewAck ack(Long tenantId, LocalDateTime expireAt, long epoch) {
        LeaseRenewAck ack = new LeaseRenewAck();
        ack.setTenantId(tenantId);
        ack.setLeaseExpireAt(expireAt);
        ack.setEpoch(epoch);
        return ack;
    }

    @Test
    @DisplayName("★ 中点采样必须抵消往返延迟（桩注入固定 RTT 时偏移应≈0，不得等于 RTT/2）")
    void midpointSamplingMustCancelRoundTripDelay() {
        // 服务端时间固定取「往返中点」；客户端在调用期间把本地时钟推进 RTT（模拟 1200ms 往返）
        Duration rtt = Duration.ofMillis(1_200);
        LocalDateTime serverNow = LocalDateTime.now(clock).plus(rtt.dividedBy(2));
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenAnswer(invocation -> {
            clock.advance(rtt);
            return R.ok(acquireRespAt(serverNow, assignment(TENANT_A)));
        });

        manager.start();

        assertThat(manager.clockSkewSeconds())
            .as("取调用前后中点 ⇒ 偏移应≈0 秒（若退回「调用前时刻」会是 RTT/2=0.6s）").isZero();
        assertThat(Math.abs(meterRegistry.get("iot.access.lease.clock_skew_seconds").gauge().value()))
            .as("gauge 单位是**秒**：允许 0.2s（200ms）以内；退回调用前时刻会到 0.6s")
            .isLessThan(0.2d);
    }

    @Test
    @DisplayName("★ 首次校准即使偏移很大也必须**照采**（未校准的判据比大偏移更危险）")
    void firstCalibrationMustBeAdoptedEvenIfLarge() {
        // 容器时区误配：本机比 DB 快 8 小时（复核实测过的反面场景：若拒绝，判据退回本机原始时钟 ⇒ 每轮拆链）
        LocalDateTime localNow = LocalDateTime.now(clock);
        LocalDateTime serverNow = localNow.minusHours(8);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(serverNow,
            assignmentAt(TENANT_A, serverNow.plusMinutes(10)))));
        when(client.renew(any())).thenReturn(R.ok(new LeaseRenewResp()));

        manager.start();
        assertThat(manager.clockSkewSeconds())
            .as("首次读数必须采纳（-28800 ± 往返误差）").isBetween(-28_801L, -28_799L);

        // 租约按服务端时间还有 10 分钟：必须继续采集，不得因未校准而每轮拆链
        manager.renewAndSelfCheck(localNow.plusSeconds(20));
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(meterRegistry.get("iot.access.lease.self_fenced").counter().count())
            .as("不得拆链（若首次读数被拒，这里会每轮 +1）").isZero();
    }

    @Test
    @DisplayName("★ 相对已校准值的**跳变**必须连续两次确认才采纳（过渡态不得改判据）")
    void jumpMustBeConfirmedBeforeAdoption() {
        LocalDateTime localNow = LocalDateTime.now(clock);
        // 已校准：偏移 7 分钟（刻意避开与阈值同量级，防常量边界耦合）
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(localNow.plusMinutes(7),
            assignment(TENANT_A, 600))));
        manager.start();
        assertThat(manager.clockSkewSeconds()).isBetween(419L, 421L);

        // 跳变 30 分钟：第一次只暂缓（保留 7 分钟校准），计数 +1
        LocalDateTime jumped = LocalDateTime.now(clock).plusMinutes(30);
        LeaseRenewResp first = new LeaseRenewResp();
        first.setServerTime(jumped);
        when(client.renew(any())).thenReturn(R.ok(first));
        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(1));
        assertThat(manager.clockSkewSeconds()).as("跳变首次读数不得改判据").isBetween(419L, 421L);
        assertThat(meterRegistry.get("iot.access.lease.clock_skew.deferred").counter().count())
            .isEqualTo(1.0d);

        // 第二次读数与上一次待确认**一致** ⇒ 收敛，采纳
        LeaseRenewResp second = new LeaseRenewResp();
        second.setServerTime(LocalDateTime.now(clock).plusMinutes(30));
        when(client.renew(any())).thenReturn(R.ok(second));
        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(1));
        assertThat(manager.clockSkewSeconds())
            .as("连续两次一致 ⇒ 采纳（约 1800 秒）").isBetween(1_795L, 1_805L);
    }

    @Test
    @DisplayName("★ 永不收敛的跳变必须在暂缓上限后强制采纳（不得无限期停在旧判据）")
    void neverConvergingJumpMustBeForceAdoptedAtBudget() {
        LocalDateTime localNow = LocalDateTime.now(clock);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(localNow.plusMinutes(1),
            assignment(TENANT_A, 600))));
        manager.start();
        assertThat(manager.clockSkewSeconds()).isEqualTo(60L);

        // 每轮读数都比上一轮再漂 60s（> 容差 30s）⇒ 永远「不一致」
        for (int round = 1; round <= AccessLeaseManager.SKEW_CONFIRM_MAX_DEFERRALS + 1; round++) {
            LeaseRenewResp resp = new LeaseRenewResp();
            resp.setServerTime(LocalDateTime.now(clock).plusMinutes(30 + round));
            when(client.renew(any())).thenReturn(R.ok(resp));
            manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(1));
        }

        assertThat(meterRegistry.get("iot.access.lease.clock_skew.deferred").counter().count())
            .as("前面几次暂缓").isEqualTo((double) AccessLeaseManager.SKEW_CONFIRM_MAX_DEFERRALS);
        assertThat(manager.clockSkewSeconds())
            .as("达到暂缓上限后必须强制采纳（约 30+4 分钟），否则判据会永远停在 60s")
            .isBetween(2_035L, 2_045L);
    }

    @Test
    @DisplayName("★ 跳变阈值被显式置空必须兜底默认值（不得 NPE 把续约线程打死）")
    void nullThresholdMustFallBackToDefault() {
        properties.setClockSkewJumpThreshold(null);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(LocalDateTime.now(clock).plusSeconds(30),
            assignment(TENANT_A, 600))));
        when(client.renew(any())).thenReturn(R.ok(new LeaseRenewResp()));

        manager.start();
        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(1));

        assertThat(manager.clockSkewSeconds()).as("照采 30s 且全程不抛").isEqualTo(30L);
    }

    @Test
    @DisplayName("★ 告警阈值判据必须对称（+5.5s 与 -5.5s 都要超阈值；Duration.toSeconds 对负值向下取整会不对称）")
    void warnThresholdMustBeSymmetric() {
        assertThat(AccessLeaseManager.exceedsWarnThreshold(Duration.ofMillis(5_500))).isTrue();
        assertThat(AccessLeaseManager.exceedsWarnThreshold(Duration.ofMillis(-5_500))).isTrue();
        assertThat(AccessLeaseManager.exceedsWarnThreshold(Duration.ofSeconds(5))).isFalse();
        assertThat(AccessLeaseManager.exceedsWarnThreshold(Duration.ofSeconds(-5))).isFalse();
        assertThat(AccessLeaseManager.exceedsWarnThreshold(Duration.ofNanos(-1))).isFalse();
    }

    /** 可推进/可偏移时钟。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-22T06:00:00Z");

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
}
