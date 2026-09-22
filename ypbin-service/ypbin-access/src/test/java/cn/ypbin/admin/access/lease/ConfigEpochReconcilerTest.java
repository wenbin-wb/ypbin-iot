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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置版本对账器的单测（M-2 / P4 + G7）。
 *
 * <p>守三条最容易被写错的契约：① 版本号相同**不得**再拉全量（否则「不一致才拉」退化成每轮都拉）；
 * ② 对账未完成时**不得**推进版本号（否则一次失败永久吞掉一次变更）；③ 只对「本节点持有的租户」生效
 * （否则会把别的节点的租户也拉一遍）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class ConfigEpochReconcilerTest {

    private static final Long TENANT_A = 11L;
    private static final Long TENANT_B = 22L;

    private ILeaseClient client;
    private RecordingLinkManager linkManager;
    private SimpleMeterRegistry meterRegistry;
    private ConfigEpochReconciler reconciler;

    @BeforeEach
    void setUp() {
        client = mock(ILeaseClient.class);
        linkManager = new RecordingLinkManager();
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new ConfigEpochReconciler(client, linkManager, meterRegistry);
    }

    @Test
    @DisplayName("首次观测就对账一次；版本号不变则**不再**拉全量；变了才再拉")
    void epochChangeShouldTriggerReconcileOnlyWhenChanged() {
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 0L))));

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("首次观测必须做一次全量对账（防「采集时刻早于版本号读取」竞态）")
            .containsExactly(TENANT_A);
        assertThat(reconciler.trackedTenantCount()).isEqualTo(1);

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("版本号相同不得再拉全量").hasSize(1);
        assertThat(meterRegistry.get("iot.access.config.changed").counter().count()).isZero();

        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 1L))));
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).containsExactly(TENANT_A, TENANT_A);
        assertThat(meterRegistry.get("iot.access.config.changed").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 对账未完成（返回 false）时**不得**推进版本号 ⇒ 下一轮必须重试")
    void notAppliedMustNotAdvanceEpochSoNextRoundRetries() {
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 7L))));
        linkManager.result = false;

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).hasSize(1);
        assertThat(reconciler.trackedTenantCount())
            .as("未完成对账就不能记版本号，否则这次变更被永久吞掉").isZero();
        assertThat(meterRegistry.get("iot.access.config.reconcile.not_applied").counter().count())
            .isEqualTo(1.0d);

        // 变体可证伪性：若实现「先推进版本号再对账」，下面这一次就不会再调用 reconcile
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("未完成 ⇒ 下一轮必须重试").hasSize(2);

        linkManager.result = true;
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).hasSize(3);
        assertThat(reconciler.trackedTenantCount()).isEqualTo(1);

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("对账完成后不再重复").hasSize(3);
    }

    @Test
    @DisplayName("只对「本节点持有的租户」对账：别人的租户不取数、不记账")
    void tenantsNotHeldMustNotBeReconciled() {
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 0L), item(TENANT_B, 0L))));

        reconciler.reconcile(Set.of(TENANT_A));

        assertThat(linkManager.reconciled).containsExactly(TENANT_A);
        assertThat(reconciler.trackedTenantCount()).as("不得替别的节点记账").isEqualTo(1);
    }

    @Test
    @DisplayName("未持有任何租户：一个远端都不打（空集合短路）")
    void emptyHeldTenantsMustNotCallRemote() {
        reconciler.reconcile(Set.of());

        verify(client, times(0)).batchEpoch();
    }

    @Test
    @DisplayName("对账请求失败（异常/非成功信封）：计数、不推进任何版本号、下一轮重试")
    void checkFailureMustNotAdvanceAnyEpoch() {
        // 必须用 doThrow：`when(mock.method())` 会在**打桩时**就执行该方法并立刻抛出，测试自己就炸了
        doThrow(new IllegalStateException("iot 不可达")).when(client).batchEpoch();
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(reconciler.trackedTenantCount()).isZero();
        assertThat(meterRegistry.get("iot.access.config.check.failure").counter().count()).isEqualTo(1.0d);

        // 该 mock 此时已被打成「抛异常」：`when(...)` 会立刻触发它 ⇒ 后续打桩也必须用 doReturn
        doReturn(R.fail(500, "boom")).when(client).batchEpoch();
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(meterRegistry.get("iot.access.config.check.failure").counter().count()).isEqualTo(2.0d);
        assertThat(linkManager.reconciled).isEmpty();

        doReturn(R.ok(batch(item(TENANT_A, 3L)))).when(client).batchEpoch();
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).containsExactly(TENANT_A);
        assertThat(reconciler.trackedTenantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("forget 之后即使版本号没变也必须重新对账（fence 重领期间上游可能改过配置）")
    void forgetShouldForceReconcileAgain() {
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 5L))));
        reconciler.reconcile(Set.of(TENANT_A));
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).hasSize(1);

        reconciler.forget(TENANT_A);
        assertThat(reconciler.trackedTenantCount()).isZero();

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("重领后必须重新对账").hasSize(2);
    }

    @Test
    @DisplayName("条目缺 tenantId 或缺 configEpoch：缺 tenantId 忽略；缺 configEpoch 按 0 处理")
    void malformedItemsMustBeTolerated() {
        TenantEpochItem noTenant = new TenantEpochItem();
        noTenant.setConfigEpoch(9L);
        TenantEpochItem noEpoch = new TenantEpochItem();
        noEpoch.setTenantId(TENANT_A);
        when(client.batchEpoch()).thenReturn(R.ok(batch(noTenant, noEpoch)));

        reconciler.reconcile(Set.of(TENANT_A));

        assertThat(linkManager.reconciled).containsExactly(TENANT_A);
        assertThat(reconciler.trackedTenantCount()).isEqualTo(1);
        verify(client, times(1)).batchEpoch();
    }

    private TenantEpochBatchResp batch(TenantEpochItem... items) {
        TenantEpochBatchResp resp = new TenantEpochBatchResp();
        resp.setItems(List.of(items));
        resp.setReadAt(LocalDateTime.now());
        return resp;
    }

    private TenantEpochItem item(Long tenantId, Long configEpoch) {
        TenantEpochItem item = new TenantEpochItem();
        item.setTenantId(tenantId);
        item.setEpoch(1L);
        item.setConfigEpoch(configEpoch);
        return item;
    }

    /** 记录对账调用的链路端口替身。 */
    private static final class RecordingLinkManager implements TenantLinkManager {

        private final List<Long> reconciled = new ArrayList<>();
        private boolean result = true;

        @Override
        public void startCollecting(Long tenantId) {
            // 本用例不涉及
        }

        @Override
        public void fence(Long tenantId, String reason) {
            // 本用例不涉及
        }

        @Override
        public void fenceAll(String reason) {
            // 本用例不涉及
        }

        @Override
        public boolean reconcile(Long tenantId) {
            reconciled.add(tenantId);
            return result;
        }

        @Override
        public boolean isCollecting(Long tenantId) {
            return false;
        }

        @Override
        public Set<Long> collectingTenants() {
            return Set.of();
        }
    }
}
