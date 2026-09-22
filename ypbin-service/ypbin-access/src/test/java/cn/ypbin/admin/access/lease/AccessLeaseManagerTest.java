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
        manager = new AccessLeaseManager(client, linkManager, properties, meterRegistry, reconciler);
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
        LocalDateTime newExpiry = LocalDateTime.now().plusSeconds(60);
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

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(120));

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

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(10));
        verify(client, times(1)).acquire(any());

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(70));
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

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(600));

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
        return resp;
    }

    private LeaseAssignmentDto assignment(Long tenantId) {
        // 默认给长 TTL：避免「模拟时间推进」的用例把租户判过期而触发自检停采，干扰别的断言
        return assignment(tenantId, 600);
    }

    private LeaseAssignmentDto assignment(Long tenantId, long ttlSeconds) {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(NODE);
        dto.setLeaseExpireAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        dto.setEpoch(1L);
        dto.setState(LeaseState.ACTIVE);
        return dto;
    }

    private TenantEpochBatchResp epochBatch(TenantEpochItem... items) {
        TenantEpochBatchResp resp = new TenantEpochBatchResp();
        resp.setItems(List.of(items));
        resp.setReadAt(LocalDateTime.now());
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
}
