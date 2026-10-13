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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多节点语义的集成级单元用例（#220）：**2 个节点 × 4 个租户**。
 *
 * <p>与 #220 验收里「≥2 节点 ≥4 租户」对应的最小可信模型：两个节点共享同一把锁（生产环境里那把锁在
 * Redis 上；这里用共享的进程内存储，时序语义完全相同），每个节点的一轮任务处理 4 个租户。
 * 断言的是：**只有一个节点执行**，且 4 个租户恰好各被处理一次——若互斥失效，计数会翻倍。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class MultiNodeLockContentionTest {

    private static final int TENANT_COUNT = 4;

    private static final String TASK = LockNames.ALERT_EVALUATE;

    @Test
    @DisplayName("★ 2 节点同时开跑：仅 1 个节点执行；4 个租户恰好各处理一次（重复即失败）")
    void onlyOneNodeProcessesFourTenants() throws Exception {
        LockProperties properties = LockTestSupport.properties();
        LockStore store = LockTestSupport.inMemoryStore();
        LockTestSupport.MutableClock clock = new LockTestSupport.MutableClock(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskLockGuard nodeA = guard(new LeaseDistributedLock(store, properties, clock), properties, registry);
        TaskLockGuard nodeB = guard(new LeaseDistributedLock(store, properties, clock), properties, registry);

        Set<Long> processedTenants = ConcurrentHashMap.newKeySet();
        AtomicInteger tenantExecutions = new AtomicInteger();
        AtomicInteger roundExecutions = new AtomicInteger();
        Runnable round = () -> {
            roundExecutions.incrementAndGet();
            for (long tenantId = 1; tenantId <= TENANT_COUNT; tenantId++) {
                processedTenants.add(tenantId);
                tenantExecutions.incrementAndGet();
                sleepQuietly(30);
            }
        };

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> runAtBarrier(nodeA, start, round));
            Future<Boolean> second = pool.submit(() -> runAtBarrier(nodeB, start, round));
            start.countDown();
            boolean executedByFirst = first.get(10, TimeUnit.SECONDS);
            boolean executedBySecond = second.get(10, TimeUnit.SECONDS);

            assertThat(executedByFirst ^ executedBySecond)
                .as("2 个节点里必须恰好 1 个执行（另一个跳过）").isTrue();
            assertThat(roundExecutions.get()).as("轮次只跑一次").isEqualTo(1);
            assertThat(tenantExecutions.get()).as("4 个租户恰好各处理一次，无重复").isEqualTo(TENANT_COUNT);
            assertThat(processedTenants).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("★ 持锁节点崩溃（不释放）：TTL 到期后另一节点可接管，任务不会永久停摆")
    void crashedNodeLockIsTakenOverAfterTtl() {
        LockProperties properties = LockTestSupport.properties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setRenewInterval(Duration.ofSeconds(10));
        LockStore store = LockTestSupport.inMemoryStore();
        LockTestSupport.MutableClock clock = new LockTestSupport.MutableClock(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskLockGuard survivor = guard(new LeaseDistributedLock(store, properties, clock), properties, registry);

        // 模拟「某节点抢到锁后进程崩溃」：直接往共享存储写一条 30 秒后到期、且不再续期的锁
        assertThat(store.tryLock(properties.getKeyPrefix() + TASK, "crashed-node", 30_000L, 0L, 30_000L))
            .as("遗弃锁写入成功").isTrue();
        assertThat(survivor.runExclusively(TASK, () -> {
        })).as("TTL 未到，幸存节点不能跑").isFalse();

        clock.advance(Duration.ofSeconds(31));
        assertThat(survivor.runExclusively(TASK, () -> {
        })).as("TTL 到期后幸存节点必须能接管").isTrue();
    }

    private static TaskLockGuard guard(DistributedLock lock, LockProperties properties,
                                       SimpleMeterRegistry registry) {
        return new TaskLockGuard(lock, (task, interval) -> () -> {
            // 不自动续期
        }, properties, registry);
    }

    private boolean runAtBarrier(TaskLockGuard guard, CountDownLatch start, Runnable round) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return guard.runExclusively(TASK, round);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
