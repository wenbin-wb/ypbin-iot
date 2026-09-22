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
import static org.assertj.core.api.Assertions.assertThatCode;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置版本对账器的单测（M-2 / P4 + G7）。
 *
 * <p>守四条最容易被写错的契约：① 版本号相同**不得**再拉全量（否则「不一致才拉」退化成每轮都拉）；
 * ② 对账未完成时**不得**推进版本号（否则一次失败永久吞掉一次变更），但**必须继续重试**；
 * ③ 只对「本节点持有的租户」生效；④ 信号链路整体缺失（单租户部署／台账无该租户）时必须有
 * <b>有界</b>的周期安全网兜底（否则设备改了永远发现不了），且安全网每轮最多强制一个租户。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class ConfigEpochReconcilerTest {

    private static final Long TENANT_A = 11L;
    private static final Long TENANT_B = 22L;
    private static final Long TENANT_C = 33L;

    /** 安全网间隔（测试里用 5 分钟，与生产默认一致）。 */
    private static final long REFRESH_INTERVAL_MS = 300_000L;

    private ILeaseClient client;
    private RecordingLinkManager linkManager;
    private SimpleMeterRegistry meterRegistry;
    private ConfigEpochReconciler reconciler;

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        client = mock(ILeaseClient.class);
        linkManager = new RecordingLinkManager();
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new ConfigEpochReconciler(client, linkManager, meterRegistry, clock,
            REFRESH_INTERVAL_MS);
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
    @DisplayName("★ 对账未完成（返回 false）时**不得**推进版本号 ⇒ 下一轮必须重试；但同一版本号不得重复计数")
    void notAppliedMustNotAdvanceEpochSoNextRoundRetries() {
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 7L))));
        linkManager.result = false;

        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).hasSize(1);
        assertThat(reconciler.trackedTenantCount())
            .as("未完成对账就不能记版本号，否则这次变更被永久吞掉").isZero();
        assertThat(meterRegistry.get("iot.access.config.reconcile.not_applied").counter().count())
            .isEqualTo(1.0d);

        // 可证伪性：若实现「先推进版本号再对账」，下面这一次就不会再调用 reconcile
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).as("未完成 ⇒ 下一轮必须重试").hasSize(2);
        assertThat(meterRegistry.get("iot.access.config.reconcile.not_applied").counter().count())
            .as("同一版本号重试不得把指标重复放大（否则零设备租户会让它无界增长）").isEqualTo(1.0d);

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
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count())
            .as("对账请求本身失败时不跑安全网（请求每 tick 就会重试，跑安全网只会翻倍压力）").isZero();

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
    @DisplayName("★ 信号链路整体缺失（版本号恒不变）时必须靠**有界**周期安全网兜底收敛")
    void missingSignalMustConvergeThroughBoundedSafetyNet() {
        // 台账没有该租户 ⇒ configEpoch 恒为 0，永远不「变化」
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 0L), item(TENANT_B, 0L))));

        reconciler.reconcile(Set.of(TENANT_A, TENANT_B));
        int afterFirstSight = linkManager.reconciled.size();
        assertThat(afterFirstSight).as("首次观测：两个租户各对账一次").isEqualTo(2);

        // 未到安全网间隔：不得强制
        clock.advance(Duration.ofMillis(REFRESH_INTERVAL_MS - 1_000));
        reconciler.reconcile(Set.of(TENANT_A, TENANT_B));
        assertThat(linkManager.reconciled).as("安全网未到期不得强制对账").hasSize(afterFirstSight);

        // 超过间隔：**每轮最多强制一个**租户（避免把调度 tick 拖长）
        clock.advance(Duration.ofSeconds(2));
        reconciler.reconcile(Set.of(TENANT_A, TENANT_B));
        assertThat(linkManager.reconciled).as("每轮只允许强制一个租户").hasSize(afterFirstSight + 1);
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count())
            .isEqualTo(1.0d);

        // 再走一轮：另一个（尚未被强制的）租户被强制 ⇒ 轮转覆盖，不会饿死
        clock.advance(Duration.ofMillis(REFRESH_INTERVAL_MS));
        reconciler.reconcile(Set.of(TENANT_A, TENANT_B));
        assertThat(linkManager.reconciled).as("轮转覆盖：另一个租户也会被强制到")
            .hasSize(afterFirstSight + 2);
    }

    @Test
    @DisplayName("★ 安全网轮转覆盖：3 个租户在 3 个 tick 内全部被强制过（每 tick 只强制 1 个）")
    void safetyNetMustRotateAcrossAllStaleTenants() {
        when(client.batchEpoch()).thenReturn(R.ok(
            batch(item(TENANT_A, 0L), item(TENANT_B, 0L), item(TENANT_C, 0L))));

        reconciler.reconcile(Set.of(TENANT_A, TENANT_B, TENANT_C));
        assertThat(linkManager.reconciled).as("首次观测：3 个租户各对账一次").hasSize(3);

        // 每轮都把时间推过安全网间隔：每轮最多强制 1 个 ⇒ 3 轮覆盖 3 个租户
        for (int round = 1; round <= 3; round++) {
            clock.advance(Duration.ofMillis(REFRESH_INTERVAL_MS + 1_000));
            reconciler.reconcile(Set.of(TENANT_A, TENANT_B, TENANT_C));
            assertThat(linkManager.reconciled)
                .as("第 %s 轮：每轮只允许强制一个租户", round).hasSize(3 + round);
        }
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count())
            .as("3 轮共强制 3 个租户 ⇒ 全量轮转 = 租户数 × tick（与间隔取较大者）").isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ 安全网按「距上次尝试的时长」触发：**信号正常、版本号长期不变**的租户同样会被周期强制对账")
    void healthyTenantMustAlsoBeCoveredBySafetyNet() {
        // 版本号恒为 5（健康、无变更），reconcile 恒成功
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 5L))));
        reconciler.reconcile(Set.of(TENANT_A));
        assertThat(linkManager.reconciled).hasSize(1);

        clock.advance(Duration.ofMillis(REFRESH_INTERVAL_MS + 1_000));
        reconciler.reconcile(Set.of(TENANT_A));

        // 这条钉住 ROADMAP 里如实写下的代价：安全网不是「只在信号缺失时才触发」，
        // 而是「距上次尝试超过间隔就拉一次」——健康租户同样付费
        assertThat(linkManager.reconciled).as("信号正常也要按周期重拉").hasSize(2);
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count())
            .isEqualTo(1.0d);
        assertThat(meterRegistry.get("iot.access.config.changed").counter().count())
            .as("版本号没变，不得计入「变更」").isZero();
    }

    @Test
    @DisplayName("安全网可关闭（间隔 <= 0）：版本号恒不变时不再强制对账")
    void safetyNetMustBeSwitchable() {
        ConfigEpochReconciler disabled = new ConfigEpochReconciler(client, linkManager, meterRegistry,
            clock, 0L);
        when(client.batchEpoch()).thenReturn(R.ok(batch(item(TENANT_A, 0L))));

        disabled.reconcile(Set.of(TENANT_A));
        clock.advance(Duration.ofDays(1));
        disabled.reconcile(Set.of(TENANT_A));

        assertThat(linkManager.reconciled).hasSize(1);
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count()).isZero();
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
    @DisplayName("条目缺 tenantId 或缺 configEpoch、或 items 为 null：一律不得打断整轮 tick")
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

        // items 为 null（版本偏差/半成品响应）：getItems() 自带防御，不得 NPE，且安全网仍能兜底
        TenantEpochBatchResp nullItems = new TenantEpochBatchResp();
        nullItems.setItems(null);
        when(client.batchEpoch()).thenReturn(R.ok(nullItems));
        clock.advance(Duration.ofMillis(REFRESH_INTERVAL_MS + 1_000));
        assertThatCode(() -> reconciler.reconcile(Set.of(TENANT_A))).doesNotThrowAnyException();
        assertThat(meterRegistry.get("iot.access.config.reconcile.forced").counter().count())
            .as("响应畸形时安全网仍应把该租户兜住").isEqualTo(1.0d);
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

    /** 可推进时钟（安全网是时间相关行为，必须能推进而不是 sleep）。 */
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
