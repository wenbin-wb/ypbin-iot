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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定时任务壳与分布式锁的接线（#220）：每一层 @Scheduled 都必须「抢到才调业务、抢不到不调」。
 *
 * <p>这层是真正决定多副本行为的接线处：业务实现没有变，变的是「谁被允许调用它」。
 * 因此逐个壳体验证「锁被占 ⇒ 业务零调用」「锁空闲 ⇒ 业务恰好一次」。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ScheduledTaskLockWiringTest {

    private final LockProperties properties = LockTestSupport.properties();

    private final DistributedLock lock = LockTestSupport.inMemoryLock(properties,
        new LockTestSupport.MutableClock(0L));

    private final TaskLockGuard guard = new TaskLockGuard(lock, (task, interval) -> () -> {
        // 测试不自动续期
    }, properties, new SimpleMeterRegistry());

    private LockLease hold(String lockName) {
        return lock.tryAcquire(properties.getKeyPrefix() + lockName);
    }

    @Test
    @DisplayName("告警评估器：锁被占时零调用，空闲时恰好一轮")
    void alertEvaluatorRespectsLock() {
        AlertEvaluatorService service = mock(AlertEvaluatorService.class);
        AlertEvaluator evaluator = new AlertEvaluator(service, guard);

        LockLease held = hold(LockNames.ALERT_EVALUATE);
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

        LockLease held = hold(LockNames.ALERT_OUTAGE_SCAN);
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

        LockLease held = hold(LockNames.ALERT_NOTIFY_DISPATCH);
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

        LockLease held = hold(LockNames.PLATFORM_ALERT_EVALUATE);
        evaluator.evaluate();
        verify(service, never()).evaluateOnce();

        held.release();
        evaluator.evaluate();
        verify(service, times(1)).evaluateOnce();
    }
}
