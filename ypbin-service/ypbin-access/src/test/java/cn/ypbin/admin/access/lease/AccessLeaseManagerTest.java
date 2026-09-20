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
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * access 租约状态机的四条硬要求（spec §3.1①）：启动握手 fail-fast、周期续约、
 * self-fencing（撤销/节点级失效/本地过期）、周期重领。
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
    private AccessLeaseManager manager;

    @BeforeEach
    void setUp() {
        client = mock(ILeaseClient.class);
        linkManager = new LoggingTenantLinkManager();
        properties = new AccessProperties();
        properties.setNodeId(NODE);
        properties.setAcquireIntervalMs(Long.MAX_VALUE);
        meterRegistry = new SimpleMeterRegistry();
        manager = new AccessLeaseManager(client, linkManager, properties, meterRegistry);
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
    @DisplayName("握手完成前：既不重领也**不补注册**（防调度器抢跑；补注册会让抢跑窗口重新打开）")
    void refreshMustNotRunBeforeHandshake() {
        // register 必须 stub 成成功：否则本用例会靠「mock 返回 null 信封 → registerOrFail 抛错早退」而绿，
        // 通过的理由就不是「闸门存在」（复核用等价断言实证过：去掉闸门后会变成 register=1/acquire=1）
        when(client.register(any())).thenReturn(R.ok());

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(600));

        verify(client, times(0)).register(any());
        verify(client, times(0)).acquire(any());
        verify(client, times(0)).renew(any());
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

    private LeaseRenewAck ack(Long tenantId, LocalDateTime expireAt, long epoch) {
        LeaseRenewAck ack = new LeaseRenewAck();
        ack.setTenantId(tenantId);
        ack.setLeaseExpireAt(expireAt);
        ack.setEpoch(epoch);
        return ack;
    }
}
