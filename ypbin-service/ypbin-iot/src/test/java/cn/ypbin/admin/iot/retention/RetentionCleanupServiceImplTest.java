/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.lock.DistributedLock;
import cn.ypbin.admin.iot.lock.LockNames;
import cn.ypbin.admin.iot.lock.LockTestSupport;
import cn.ypbin.admin.iot.lock.TaskLockGuard;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 保留清理的行为（D0.8）：护栏、数据库时钟、计数、跳过语义。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class RetentionCleanupServiceImplTest {

    private static final LocalDateTime DB_NOW = LocalDateTime.of(2026, 9, 24, 3, 0);

    private final OutageEventMapper outageEventMapper = mock(OutageEventMapper.class);

    private final MaintenanceWindowMapper maintenanceWindowMapper = mock(MaintenanceWindowMapper.class);

    private final DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);

    private final IotPointMappingMapper pointMappingMapper = mock(IotPointMappingMapper.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RetentionCleanupServiceImpl service(RetentionProperties properties) {
        when(livenessMapper.selectNow()).thenReturn(DB_NOW);
        TaskLockGuard guard = LockTestSupport.guard(LockTestSupport.properties(), registry);
        return new RetentionCleanupServiceImpl(outageEventMapper, maintenanceWindowMapper, livenessMapper,
            pointMappingMapper, properties, registry, guard);
    }

    private RetentionCleanupServiceImpl service(RetentionProperties properties, TaskLockGuard guard) {
        when(livenessMapper.selectNow()).thenReturn(DB_NOW);
        return new RetentionCleanupServiceImpl(outageEventMapper, maintenanceWindowMapper, livenessMapper,
            pointMappingMapper, properties, registry, guard);
    }

    private static RetentionProperties days(int outageDays, int windowDays) {
        RetentionProperties properties = new RetentionProperties();
        properties.setOutageEventDays(outageDays);
        properties.setMaintenanceWindowDays(windowDays);
        return properties;
    }

    @Test
    @DisplayName("★ 启用时：截止时刻按**数据库时钟**与配置天数计算，并分别清理两张表")
    void mustDeleteWithDatabaseClockCutoff() {
        when(outageEventMapper.deleteStartedBefore(any())).thenReturn(5);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(3);

        RetentionCleanupResult result = service(days(396, 396)).cleanupOnce();

        ArgumentCaptor<LocalDateTime> outageCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outageEventMapper).deleteStartedBefore(outageCutoff.capture());
        assertThat(outageCutoff.getValue()).as("断档截止 = 数据库现在 − 396 天")
            .isEqualTo(DB_NOW.minusDays(396));
        ArgumentCaptor<LocalDateTime> windowCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(maintenanceWindowMapper).deleteStartedBefore(windowCutoff.capture());
        assertThat(windowCutoff.getValue()).isEqualTo(DB_NOW.minusDays(396));

        assertThat(result.outageEvents()).isEqualTo(5);
        assertThat(result.maintenanceWindows()).isEqualTo(3);
        assertThat(result.skipped()).isFalse();
        assertThat(result.skipReason()).as("已执行时原因码必须是 NONE").isEqualTo(RetentionSkipReason.NONE);
        assertThat(result.durationMs()).as("已执行必须如实带回耗时").isGreaterThanOrEqualTo(0L);
        assertThat(result.total()).isEqualTo(8);
        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_DELETED).tag("table", "outage_event")
            .counter().count()).isEqualTo(5.0d);
        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_DELETED).tag("table", "maintenance_window")
            .counter().count()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ 关闭时：不删任何数据，返回 skipped")
    void mustSkipWhenDisabled() {
        RetentionProperties properties = days(396, 396);
        properties.setEnabled(false);

        RetentionCleanupResult result = service(properties).cleanupOnce();

        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).as("关闭时必须如实给出 disabled，而不是无信息的 skipped")
            .isEqualTo(RetentionSkipReason.DISABLED);
        assertThat(result.total()).isZero();
        verify(outageEventMapper, never()).deleteStartedBefore(any());
        verify(maintenanceWindowMapper, never()).deleteStartedBefore(any());
    }

    @Test
    @DisplayName("★ 护栏：天数配置成 0/负数（= 全部过期）时必须**拒绝执行**，绝不清空全表")
    void mustRefuseWhenRetentionNotPositive() {
        for (RetentionProperties properties : new RetentionProperties[] {days(0, 396), days(396, -1)}) {
            RetentionCleanupResult result = service(properties).cleanupOnce();
            assertThat(result.skipped()).as("非法天数必须跳过而不是照删").isTrue();
            assertThat(result.skipReason()).as("非法天数必须给出 invalid-config（与「开关关闭」区分）")
                .isEqualTo(RetentionSkipReason.INVALID_CONFIG);
        }
        verify(outageEventMapper, never()).deleteStartedBefore(any());
        verify(maintenanceWindowMapper, never()).deleteStartedBefore(any());
    }

    @Test
    @DisplayName("★ 启动自检：天数非正 / 间隔非正时拒绝启动（不可逆动作不允许带着错误配置跑起来）")
    void mustFailFastOnInvalidConfiguration() {
        assertThatThrownBy(() -> new IotRetentionConfiguration(days(0, 396)).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("必须为正数");
        assertThatThrownBy(() -> new IotRetentionConfiguration(days(396, -1)).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class);

        RetentionProperties badInterval = days(396, 396);
        badInterval.setCleanupIntervalMs(0);
        assertThatThrownBy(() -> new IotRetentionConfiguration(badInterval).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cleanup-interval-ms");

        // 关闭时不做校验（不删数据就无所谓天数）
        RetentionProperties disabled = days(0, 0);
        disabled.setEnabled(false);
        new IotRetentionConfiguration(disabled).afterPropertiesSet();
    }

    @Test
    @DisplayName("无过期数据：不报错、计数为 0、skipped=false")
    void mustReturnEmptyResultWhenNothingExpired() {
        when(outageEventMapper.deleteStartedBefore(any())).thenReturn(0);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(0);

        RetentionCleanupResult result = service(days(396, 396)).cleanupOnce();

        assertThat(result.skipped()).isFalse();
        assertThat(result.total()).isZero();
    }

    @Test
    @DisplayName("★ 多节点（#220）：锁被另一实例持有时本轮**跳过**，如实返回 lock-held-by-other")
    void mustSkipWhenLockHeldByAnotherInstance() {
        cn.ypbin.admin.iot.lock.LockProperties lockProperties = LockTestSupport.properties();
        DistributedLock sharedLock = LockTestSupport.inMemoryLock(lockProperties,
            new LockTestSupport.MutableClock(0L));
        // 另一实例先抢到锁（模拟另一个节点正在清理）
        org.assertj.core.api.Assertions.assertThat(
            sharedLock.tryAcquire(lockProperties.getKeyPrefix() + LockNames.RETENTION_CLEANUP)).isNotNull();
        TaskLockGuard guard = LockTestSupport.guard(sharedLock, lockProperties, registry);

        RetentionCleanupResult result = service(days(396, 396), guard).cleanupOnce();

        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo(RetentionSkipReason.LOCK_HELD_BY_OTHER);
        verify(outageEventMapper, never()).deleteStartedBefore(any());
        verify(maintenanceWindowMapper, never()).deleteStartedBefore(any());
    }

    @Test
    @DisplayName("★ 防重入：并发触发只跑一轮，后到者拿到 already-running（绝不并发删两轮）")
    void concurrentTriggerMustRunOnlyOnce() throws Exception {
        CountDownLatch enteredDelete = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        when(outageEventMapper.deleteStartedBefore(any())).thenAnswer(invocation -> {
            enteredDelete.countDown();
            releaseDelete.await(10, TimeUnit.SECONDS);
            return 5;
        });
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(3);
        RetentionCleanupServiceImpl svc = service(days(396, 396));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RetentionCleanupResult> first = pool.submit(svc::cleanupOnce);
            assertThat(enteredDelete.await(10, TimeUnit.SECONDS)).as("第一轮已进入删除阶段").isTrue();

            Future<RetentionCleanupResult> second = pool.submit(svc::cleanupOnce);
            RetentionCleanupResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.skipReason()).as("后到者必须因分布式锁被占而跳过")
                .isEqualTo(RetentionSkipReason.LOCK_HELD_BY_OTHER);
            assertThat(secondResult.skipped()).isTrue();
            assertThat(secondResult.outageEvents()).isZero();

            releaseDelete.countDown();
            RetentionCleanupResult firstResult = first.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.skipped()).isFalse();
            assertThat(firstResult.outageEvents()).isEqualTo(5);
            assertThat(firstResult.maintenanceWindows()).isEqualTo(3);

            verify(outageEventMapper, times(1)).deleteStartedBefore(any());
            verify(maintenanceWindowMapper, times(1)).deleteStartedBefore(any());
        } finally {
            releaseDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("★ 失败必须如实抛出，且防重入闸门要释放（下一次还能正常跑）")
    void failureMustPropagateAndReleaseGate() {
        when(outageEventMapper.deleteStartedBefore(any()))
            .thenThrow(new IllegalStateException("DB 挂了"))
            .thenReturn(7);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(2);
        RetentionCleanupServiceImpl svc = service(days(396, 396));

        assertThatThrownBy(svc::cleanupOnce)
            .as("删除失败必须如实上抛（不吞）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DB 挂了");

        RetentionCleanupResult retry = svc.cleanupOnce();
        assertThat(retry.skipped()).as("失败后闸门必须已释放，下一次可正常执行").isFalse();
        assertThat(retry.outageEvents()).isEqualTo(7);
        assertThat(retry.skipReason()).isEqualTo(RetentionSkipReason.NONE);
    }

    @Test
    @DisplayName("★ 孤儿映射巡检：计数写入 iot.pointmapping.orphan gauge，且**不删除映射行**")
    void mustCountOrphanMappingsWithoutDeleting() {
        when(pointMappingMapper.countOrphanMappings()).thenReturn(7L);

        service(days(396, 396)).cleanupOnce();

        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_ORPHAN).gauge().value())
            .as("存量孤儿映射数必须可观测（生产排障靠它判断要不要清理映射）").isEqualTo(7.0d);
        // 只读巡检：绝不物理删映射（那会改设备的采集配置）⇒ 除计数外不得有任何其它交互
        verify(pointMappingMapper).countOrphanMappings();
        verifyNoMoreInteractions(pointMappingMapper);
    }

    @Test
    @DisplayName("★ 启动即巡检一次：gauge 不再在首个巡检周期前恒为初始 0（0 不能既表示「还没测」又表示「没有孤儿」）")
    void startupMustInspectOrphansImmediately() {
        when(pointMappingMapper.countOrphanMappings()).thenReturn(3L);

        // 只触发启动钩子（不跑 cleanupOnce）
        service(days(396, 396)).inspectOrphansOnStartup();

        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_ORPHAN).gauge().value())
            .as("启动后 gauge 必须已经是真实值，而不是初值 0").isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ 孤儿巡检**不受清理开关影响**：清理关掉时仍刷新 gauge（只读巡检与不可逆删除解耦）")
    void orphanInspectionMustRunEvenWhenCleanupDisabled() {
        RetentionProperties properties = days(396, 396);
        properties.setEnabled(false);
        when(pointMappingMapper.countOrphanMappings()).thenReturn(2L);

        service(properties).cleanupOnce();

        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_ORPHAN).gauge().value()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("★ 孤儿巡检失败不打断清理：只记 ERROR、保留上一次取值")
    void orphanInspectionFailureMustNotBreakCleanup() {
        when(pointMappingMapper.countOrphanMappings()).thenThrow(new IllegalStateException("巡检 SQL 挂了"));
        when(outageEventMapper.deleteStartedBefore(any())).thenReturn(4);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(0);

        RetentionCleanupResult result = service(days(396, 396)).cleanupOnce();

        assertThat(result.outageEvents()).as("巡检失败不得把不可逆清理拖停").isEqualTo(4);
        assertThat(result.skipped()).isFalse();
    }
}
