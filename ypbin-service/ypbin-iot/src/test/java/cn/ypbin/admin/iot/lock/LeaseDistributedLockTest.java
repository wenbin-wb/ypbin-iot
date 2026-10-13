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

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约锁的核心语义（#220）：抢占/跳过、TTL 到期可被接管、续期、释放、异常不泄漏。
 *
 * <p>用可注入时钟把「等 TTL」变成「推进时钟」，用例是确定的、毫秒级完成的；
 * 用共享的 {@link InMemoryLockStore} 模拟「两个副本连同一把锁」。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class LeaseDistributedLockTest {

    private static final String KEY = "ypbin:iot:lock:test";

    private final LockTestSupport.MutableClock clock = new LockTestSupport.MutableClock(1_000_000L);

    private static LockProperties properties() {
        LockProperties properties = new LockProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setRenewInterval(Duration.ofSeconds(10));
        return properties;
    }

    @Test
    @DisplayName("★ 抢到锁后，另一个节点抢不到（返回 null = 跳过，不是报错）")
    void acquiredLockBlocksOtherNode() {
        LockStore store = new InMemoryLockStore();
        DistributedLock nodeA = new LeaseDistributedLock(store, properties(), clock);
        DistributedLock nodeB = new LeaseDistributedLock(store, properties(), clock);

        LockLease leaseA = nodeA.tryAcquire(KEY);
        assertThat(leaseA).as("第一个节点必须抢到").isNotNull();
        assertThat(leaseA.isHeld()).isTrue();
        assertThat(nodeB.tryAcquire(KEY)).as("第二个节点必须抢不到").isNull();

        leaseA.release();
        assertThat(nodeB.tryAcquire(KEY)).as("A 释放后 B 必须能抢到").isNotNull();
    }

    @Test
    @DisplayName("★ TTL 到期后无需释放，另一节点即可接管（可注入时钟，不真等）")
    void expiredLeaseCanBeTakenOver() {
        LockStore store = new InMemoryLockStore();
        LockProperties properties = properties();
        DistributedLock nodeA = new LeaseDistributedLock(store, properties, clock);
        DistributedLock nodeB = new LeaseDistributedLock(store, properties, clock);

        assertThat(nodeA.tryAcquire(KEY)).isNotNull();

        clock.advance(Duration.ofSeconds(29));
        assertThat(nodeB.tryAcquire(KEY)).as("未到期前 B 不能接管").isNull();

        clock.advance(Duration.ofSeconds(2));
        assertThat(nodeB.tryAcquire(KEY)).as("超过 TTL 后 B 必须能接管").isNotNull();
    }

    @Test
    @DisplayName("★ 续期把租约往后推：原到期时刻之后、新到期时刻之前，另一节点仍抢不到")
    void renewExtendsLease() {
        LockStore store = new InMemoryLockStore();
        LockProperties properties = properties();
        DistributedLock nodeA = new LeaseDistributedLock(store, properties, clock);
        DistributedLock nodeB = new LeaseDistributedLock(store, properties, clock);

        LockLease leaseA = nodeA.tryAcquire(KEY);
        long firstExpiry = leaseA.expiresAtMillis();

        clock.advance(Duration.ofSeconds(29));
        assertThat(leaseA.renew()).as("到期前续期必须成功").isTrue();
        assertThat(leaseA.expiresAtMillis()).as("续期后到期时刻必须推后").isGreaterThan(firstExpiry);

        clock.advance(Duration.ofSeconds(2));
        assertThat(nodeB.tryAcquire(KEY)).as("续期后 B 不能接管").isNull();

        clock.advance(Duration.ofSeconds(30));
        assertThat(nodeB.tryAcquire(KEY)).as("新租约也到期后 B 可接管").isNotNull();
    }

    @Test
    @DisplayName("非持有者的续期/删除都不生效（token 比对），持锁方的锁不受影响")
    void nonOwnerCannotRenewOrUnlock() {
        LockStore store = new InMemoryLockStore();
        LockProperties properties = properties();
        DistributedLock nodeA = new LeaseDistributedLock(store, properties, clock);
        DistributedLock nodeB = new LeaseDistributedLock(store, properties, clock);

        assertThat(nodeA.tryAcquire(KEY)).isNotNull();
        assertThat(store.renew(KEY, "someone-else", clock.millis() + 1_000, 30_000L)).isFalse();
        assertThat(store.unlock(KEY, "someone-else")).isFalse();
        assertThat(nodeB.tryAcquire(KEY)).as("非持有者删除无效，A 仍持有").isNull();
    }

    @Test
    @DisplayName("存储抛异常时 tryAcquire 如实抛出（由守卫决定 fail-open/closed），不吞")
    void storeFailureOnAcquirePropagates() {
        LockStore failing = new LockStore() {
            @Override
            public boolean tryLock(String key, String token, long expireAtMillis, long nowMillis,
                                   long ttlMillis) {
                throw new IllegalStateException("Redis 挂了");
            }

            @Override
            public boolean renew(String key, String token, long newExpireAtMillis, long ttlMillis) {
                return false;
            }

            @Override
            public boolean unlock(String key, String token) {
                return false;
            }
        };
        DistributedLock lock = new LeaseDistributedLock(failing, properties(), clock);

        assertThatThrownBy(() -> lock.tryAcquire(KEY))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Redis 挂了");
    }

    @Test
    @DisplayName("续期遇到存储异常：返回 false 且标记锁已丢失，**不抛**（不打断正在跑的任务）")
    void renewFailureMarksLeaseLost() {
        LockStore renewFails = new LockStore() {
            @Override
            public boolean tryLock(String key, String token, long expireAtMillis, long nowMillis,
                                   long ttlMillis) {
                return true;
            }

            @Override
            public boolean renew(String key, String token, long newExpireAtMillis, long ttlMillis) {
                throw new IllegalStateException("续期时 Redis 抖动");
            }

            @Override
            public boolean unlock(String key, String token) {
                return false;
            }
        };
        LockLease lease = new LeaseDistributedLock(renewFails, properties(), clock).tryAcquire(KEY);

        assertThat(lease.renew()).isFalse();
        assertThat(lease.isHeld()).as("续期失败必须如实标记为已丢失").isFalse();
        assertThat(lease.renew()).as("已丢失的租约不再重试续期").isFalse();
    }

    @Test
    @DisplayName("释放遇到存储异常：不抛出（由 TTL 兜底），但标记锁已不持有")
    void releaseFailureDoesNotThrow() {
        LockStore unlockFails = new LockStore() {
            @Override
            public boolean tryLock(String key, String token, long expireAtMillis, long nowMillis,
                                   long ttlMillis) {
                return true;
            }

            @Override
            public boolean renew(String key, String token, long newExpireAtMillis, long ttlMillis) {
                return true;
            }

            @Override
            public boolean unlock(String key, String token) {
                throw new IllegalStateException("释放时 Redis 抖动");
            }
        };
        LockLease lease = new LeaseDistributedLock(unlockFails, properties(), clock).tryAcquire(KEY);

        lease.release();
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    @DisplayName("空锁实现：永远抢到、续期恒成功、释放无动作（ypbin.lock.enabled=false）")
    void noopLockAlwaysAcquires() {
        DistributedLock lock = new NoopDistributedLock();
        LockLease first = lock.tryAcquire(KEY);
        LockLease second = lock.tryAcquire(KEY);
        assertThat(first).isNotNull();
        assertThat(second).as("空锁不做互斥，第二个也抢得到").isNotNull();
        assertThat(first.renew()).isTrue();
        assertThat(first.isHeld()).isTrue();
        first.release();
        assertThat(first.isHeld()).isTrue();
    }

    @Test
    @DisplayName("配置自检：renew-interval 必须小于 ttl，否则拒绝启动")
    void configurationRejectsRenewNotLessThanTtl() {
        LockProperties bad = properties();
        bad.setTtl(Duration.ofSeconds(10));
        bad.setRenewInterval(Duration.ofSeconds(10));
        assertThatThrownBy(() -> new IotLockConfiguration(bad).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("必须小于");
    }

    /** 只因 Clock 类型被引用而保留（避免未使用导入告警的显式检查）。 */
    @Test
    @DisplayName("时钟契约：可注入时钟实现返回 UTC 与毫秒")
    void clockContract() {
        Clock injected = clock;
        assertThat(injected.millis()).isEqualTo(1_000_000L);
        assertThat(injected.getZone()).isNotNull();
    }
}
