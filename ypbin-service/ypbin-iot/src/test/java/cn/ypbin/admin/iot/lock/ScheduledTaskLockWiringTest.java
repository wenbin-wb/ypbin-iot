/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.ypbin.admin.iot.alert.AlertEvaluator;
import cn.ypbin.admin.iot.alert.AlertEvaluatorService;
import cn.ypbin.admin.iot.alert.AlertNotifyDispatchService;
import cn.ypbin.admin.iot.alert.AlertNotifyDispatcher;
import cn.ypbin.admin.iot.alert.AlertOutageMappingService;
import cn.ypbin.admin.iot.alert.AlertOutageScanner;
import cn.ypbin.admin.iot.platform.PlatformAlertEvaluator;
import cn.ypbin.admin.iot.platform.PlatformAlertService;
import cn.ypbin.starter.tools.lock.LeaseLockGuard;
import cn.ypbin.starter.tools.lock.LeaseLockProperties;
import cn.ypbin.starter.tools.lock.LeaseLockService;
import cn.ypbin.starter.tools.lock.LockLease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主定时任务壳与分布式锁的**接线**（#220；2026-10-15 task-18 后改用 starter 租约锁）。
 *
 * <p>锁的**实现**已上收到 ypbin-starter（抢锁/过期接管/续期/释放/fail-open 由 starter 仓的同名用例覆盖），
 * 本类钉的是**宿主侧不可上收的部分**：IoT 的每个 {@code @Scheduled} 壳是否真的「抢到才调业务、
 * 抢不到不调」。这层才是决定多副本行为的地方——业务实现没变，变的是「谁被允许调用它」。</p>
 *
 * <p>锁名改用各任务自己的 {@code LOCK_NAME} 常量（原 {@code iot.lock.LockNames} 已随自建实现删除；
 * 每个锁名恰好一处使用，留在任务身边更不易漂移）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ScheduledTaskLockWiringTest {

    private final LeaseLockProperties properties = IotLockTestSupport.properties();

    private final LeaseLockService lockService = IotLockTestSupport.sharedLockService();

    private final LeaseLockGuard guard = IotLockTestSupport.guard(lockService, properties,
        new SimpleMeterRegistry());

    /** 先占住某个任务的锁（模拟「另一副本正在执行」）。 */
    private LockLease hold(String lockName) {
        return lockService.tryAcquire(properties.getKeyPrefix() + lockName);
    }

    @Test
    @DisplayName("告警评估器：锁被占时零调用，空闲时恰好一轮")
    void alertEvaluatorRespectsLock() {
        AlertEvaluatorService service = mock(AlertEvaluatorService.class);
        AlertEvaluator evaluator = new AlertEvaluator(service, guard);

        LockLease held = hold(AlertEvaluator.LOCK_NAME);
        evaluator.evaluate();
        verify(service, never()).evaluateOnce();

        held.release();
        evaluator.evaluate();
        verify(service, times(1)).evaluateOnce();
    }

    @Test
    @DisplayName("断档告警映射：锁被占时零调用，空闲时恰好一轮")
    void alertOutageScannerRespectsLock() {
        AlertOutageMappingService service = mock(AlertOutageMappingService.class);
        AlertOutageScanner scanner = new AlertOutageScanner(service, guard);

        LockLease held = hold(AlertOutageScanner.LOCK_NAME);
        scanner.scan();
        verify(service, never()).mapOnce();

        held.release();
        scanner.scan();
        verify(service, times(1)).mapOnce();
    }

    @Test
    @DisplayName("通知投递器：锁被占时零调用，空闲时恰好一轮")
    void alertNotifyDispatcherRespectsLock() {
        AlertNotifyDispatchService service = mock(AlertNotifyDispatchService.class);
        AlertNotifyDispatcher dispatcher = new AlertNotifyDispatcher(service, guard);

        LockLease held = hold(AlertNotifyDispatcher.LOCK_NAME);
        dispatcher.dispatch();
        verify(service, never()).dispatchOnce();

        held.release();
        dispatcher.dispatch();
        verify(service, times(1)).dispatchOnce();
    }

    @Test
    @DisplayName("平台自告警判定：锁被占时零调用，空闲时恰好一轮")
    void platformAlertEvaluatorRespectsLock() {
        PlatformAlertService service = mock(PlatformAlertService.class);
        PlatformAlertEvaluator evaluator = new PlatformAlertEvaluator(service, guard);

        LockLease held = hold(PlatformAlertEvaluator.LOCK_NAME);
        evaluator.evaluate();
        verify(service, never()).evaluateOnce();

        held.release();
        evaluator.evaluate();
        verify(service, times(1)).evaluateOnce();
    }

    @Test
    @DisplayName("★ 互斥粒度：两个不同任务的锁互不影响（各自能独立执行）")
    void differentTasksDoNotBlockEachOther() {
        AlertEvaluatorService evaluateService = mock(AlertEvaluatorService.class);
        AlertNotifyDispatchService dispatchService = mock(AlertNotifyDispatchService.class);
        AlertEvaluator evaluator = new AlertEvaluator(evaluateService, guard);
        AlertNotifyDispatcher dispatcher = new AlertNotifyDispatcher(dispatchService, guard);

        LockLease held = hold(AlertEvaluator.LOCK_NAME);
        evaluator.evaluate();
        // 另一个任务的锁没被占用 ⇒ 照常执行
        dispatcher.dispatch();

        verify(evaluateService, never()).evaluateOnce();
        verify(dispatchService, times(1)).dispatchOnce();
        held.release();
    }

    @Test
    @DisplayName("★ 锁名常量不得重复（重复会让两个任务互相顶掉：一个跑另一个必跳过）")
    void lockNamesMustBeDistinct() {
        String[] names = {AlertEvaluator.LOCK_NAME, AlertNotifyDispatcher.LOCK_NAME,
            AlertOutageScanner.LOCK_NAME, PlatformAlertEvaluator.LOCK_NAME};
        org.assertj.core.api.Assertions.assertThat(names)
            .as("每个后台任务的锁名必须唯一，否则「互斥」会误伤另一个任务")
            .doesNotHaveDuplicates()
            .allSatisfy(name -> org.assertj.core.api.Assertions.assertThat(name).isNotBlank());
    }
}
