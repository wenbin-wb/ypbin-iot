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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务守卫（#220）的语义：抢到才执行、抢不到跳过、异常必释放、存储不可用按配置降级、看门狗续期。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class TaskLockGuardTest {

    private static final String TASK = "unit-test-task";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final LockProperties properties = LockTestSupport.properties();

    private TaskLockGuard guard(DistributedLock lock) {
        return new TaskLockGuard(lock, (task, interval) -> () -> {
            // 默认不自动续期
        }, properties, registry);
    }

    @Test
    @DisplayName("★ 抢到锁才执行；执行结果被如实带回")
    void executesWhenLockFree() {
        TaskLockGuard guard = guard(LockTestSupport.inMemoryLock(properties,
            new LockTestSupport.MutableClock(0L)));
        AtomicInteger runs = new AtomicInteger();

        LockOutcome<Integer> outcome = guard.callExclusively(TASK, runs::incrementAndGet);

        assertThat(outcome.executed()).isTrue();
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.result()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 锁被另一节点持有时**跳过**：任务不执行、返回 executed=false（不报错）")
    void skipsWhenHeldByOtherNode() throws Exception {
        LockStore store = LockTestSupport.inMemoryStore();
        LockTestSupport.MutableClock clock = new LockTestSupport.MutableClock(0L);
        TaskLockGuard nodeA = guard(new LeaseDistributedLock(store, properties, clock));
        TaskLockGuard nodeB = guard(new LeaseDistributedLock(store, properties, clock));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = pool.submit(() -> nodeA.runExclusively(TASK, () -> {
                entered.countDown();
                awaitQuietly(release);
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("节点 A 已持锁").isTrue();

            LockOutcome<Boolean> skipped = nodeB.callExclusively(TASK, () -> true);
            assertThat(skipped.executed()).as("节点 B 必须跳过").isFalse();
            assertThat(skipped.result()).isNull();
            assertThat(registry.get(TaskLockGuard.METRIC_SKIPPED).tag("task", TASK).counter().count())
                .as("跳过必须可观测").isEqualTo(1.0d);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("★ 任务抛异常时锁必须被释放（下一次调用还能正常跑）")
    void releasesLockWhenTaskThrows() {
        DistributedLock lock = LockTestSupport.inMemoryLock(properties, new LockTestSupport.MutableClock(0L));
        TaskLockGuard guard = guard(lock);

        assertThatThrownBy(() -> guard.runExclusively(TASK, () -> {
            throw new IllegalStateException("任务失败");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("任务失败");

        assertThat(lock.tryAcquire(properties.getKeyPrefix() + TASK))
            .as("异常后锁必须已被释放").isNotNull();
    }

    @Test
    @DisplayName("★ 两个节点并发抢同一任务：只执行一次，另一个跳过")
    void concurrentNodesExecuteExactlyOnce() throws Exception {
        LockStore store = LockTestSupport.inMemoryStore();
        LockTestSupport.MutableClock clock = new LockTestSupport.MutableClock(0L);
        TaskLockGuard nodeA = guard(new LeaseDistributedLock(store, properties, clock));
        TaskLockGuard nodeB = guard(new LeaseDistributedLock(store, properties, clock));
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> runTogether(nodeA, start, executions));
            Future<Boolean> second = pool.submit(() -> runTogether(nodeB, start, executions));
            start.countDown();
            boolean executedFirst = first.get(10, TimeUnit.SECONDS);
            boolean executedSecond = second.get(10, TimeUnit.SECONDS);
            assertThat(executedFirst ^ executedSecond).as("恰好一个节点执行").isTrue();
            assertThat(executions.get()).as("任务只执行一次").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean runTogether(TaskLockGuard guard, CountDownLatch start, AtomicInteger executions) {
        awaitQuietly(start);
        return guard.runExclusively(TASK, () -> {
            executions.incrementAndGet();
            sleepQuietly(50);
        });
    }

    @Test
    @DisplayName("锁存储不可用 + fail-open（默认）：继续执行并标记 degraded=true")
    void failOpenWhenStoreUnavailable() {
        TaskLockGuard guard = guard(key -> {
            throw new IllegalStateException("Redis 不可达");
        });
        AtomicInteger runs = new AtomicInteger();

        LockOutcome<Integer> outcome = guard.callExclusively(TASK, runs::incrementAndGet);

        assertThat(outcome.executed()).isTrue();
        assertThat(outcome.degraded()).as("降级执行必须如实标记").isTrue();
        assertThat(outcome.result()).isEqualTo(1);
        assertThat(registry.get(TaskLockGuard.METRIC_UNAVAILABLE).tag("task", TASK).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("锁存储不可用 + fail-closed：如实上抛，不假装执行过")
    void failClosedWhenStoreUnavailable() {
        properties.setFailOpen(false);
        TaskLockGuard guard = guard(key -> {
            throw new IllegalStateException("Redis 不可达");
        });
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> guard.callExclusively(TASK, runs::incrementAndGet))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Redis 不可达");
        assertThat(runs.get()).as("fail-closed 下任务不得执行").isZero();
    }

    @Test
    @DisplayName("★ 持锁期间注册续期看门狗，任务结束时取消")
    void schedulesAndCancelsRenewalWatchdog() {
        FakeLease lease = new FakeLease();
        CapturingRenewalScheduler scheduler = new CapturingRenewalScheduler();
        TaskLockGuard guard = new TaskLockGuard(key -> lease, scheduler, properties, registry);
        AtomicInteger renewals = new AtomicInteger();
        lease.onRenew = renewals::incrementAndGet;

        guard.runExclusively(TASK, () -> scheduler.task.run());

        assertThat(scheduler.scheduled).as("必须注册续期").isTrue();
        assertThat(renewals.get()).as("看门狗触发时必须调用 lease.renew()").isEqualTo(1);
        assertThat(scheduler.cancelled).as("任务结束必须取消续期并释放").isTrue();
        assertThat(lease.released).isTrue();
    }

    @Test
    @DisplayName("续期失败时计数 iot.lock.renew.failed（不抛出、不影响任务）")
    void renewalFailureIsCounted() {
        FakeLease lease = new FakeLease();
        lease.renewResult = false;
        CapturingRenewalScheduler scheduler = new CapturingRenewalScheduler();
        TaskLockGuard guard = new TaskLockGuard(key -> lease, scheduler, properties, registry);
        AtomicInteger runs = new AtomicInteger();

        guard.runExclusively(TASK, () -> {
            scheduler.task.run();
            runs.incrementAndGet();
        });

        assertThat(runs.get()).isEqualTo(1);
        assertThat(registry.get(TaskLockGuard.METRIC_RENEW_FAILED).tag("task", TASK).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("空锁（关闭互斥）时任务照常执行")
    void noopLockAlwaysExecutes() {
        TaskLockGuard guard = guard(new NoopDistributedLock());
        assertThat(guard.runExclusively(TASK, () -> {
        })).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记录续期调用的假租约。 */
    private static final class FakeLease implements LockLease {

        private Runnable onRenew = () -> {
        };

        private boolean renewResult = true;

        private boolean released;

        @Override
        public String lockKey() {
            return TASK;
        }

        @Override
        public boolean isHeld() {
            return !released;
        }

        @Override
        public long expiresAtMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean renew() {
            onRenew.run();
            return renewResult;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    /** 捕获续期任务、可手动触发的调度器。 */
    private static final class CapturingRenewalScheduler implements LeaseRenewalScheduler {

        private Runnable task;

        private boolean scheduled;

        private boolean cancelled;

        @Override
        public Cancellable schedule(Runnable renewalTask, Duration interval) {
            this.task = renewalTask;
            this.scheduled = true;
            return () -> cancelled = true;
        }
    }
}
