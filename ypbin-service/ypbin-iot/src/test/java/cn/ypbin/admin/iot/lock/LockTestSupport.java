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

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 分布式锁的测试支撑（#220）。
 *
 * <p>测试需要「两个节点共享同一把锁、且时钟可控」，但生产装配类只暴露 {@link DistributedLock}
 * 这一个公共类型；因此这里给出不依赖 Redis、时钟可注入的构造入口，供 lock 包内外的用例复用。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class LockTestSupport {

    private LockTestSupport() {
    }

    /**
     * 默认配置（TTL 60s、续期 20s）。
     *
     * @return 配置
     */
    public static LockProperties properties() {
        return new LockProperties();
    }

    /**
     * 基于共享内存存储的锁（同一 store 的所有 lock 视为共享介质上的不同节点）。
     *
     * @param properties 配置
     * @param clock      可注入时钟
     * @return 锁
     */
    public static DistributedLock inMemoryLock(LockProperties properties, Clock clock) {
        return new LeaseDistributedLock(new InMemoryLockStore(), properties, clock);
    }

    /**
     * 基于共享内存存储的锁（共享同一个 store，用于模拟多节点）。
     *
     * @param properties 配置
     * @param store      共享存储
     * @param clock      可注入时钟
     * @return 锁
     */
    static DistributedLock lockOn(LockProperties properties, LockStore store, Clock clock) {
        return new LeaseDistributedLock(store, properties, clock);
    }

    /**
     * 用「不自动续期」的调度器包住锁（单元测试里由用例显式驱动续期）。
     *
     * @param lock       锁
     * @param properties 配置
     * @param registry   指标
     * @return 守卫
     */
    public static TaskLockGuard guard(DistributedLock lock, LockProperties properties,
                                      MeterRegistry registry) {
        return new TaskLockGuard(lock, (task, interval) -> () -> {
            // 测试里不自动续期：需要验证续期时由用例直接调用 lease.renew()
        }, properties, registry);
    }

    /**
     * 基于内存锁的守卫（单实例语义，供不关心多节点的用例使用）。
     *
     * @param properties 配置
     * @param registry   指标
     * @return 守卫
     */
    public static TaskLockGuard guard(LockProperties properties, MeterRegistry registry) {
        return guard(inMemoryLock(properties, Clock.systemUTC()), properties, registry);
    }

    /**
     * 共享内存存储（供测试自行构造多个节点的锁）。
     *
     * @return 存储
     */
    static LockStore inMemoryStore() {
        return new InMemoryLockStore();
    }

    /** 可手动推进的时钟（避免测试真等 TTL）。 */
    public static final class MutableClock extends Clock {

        private long millis;

        public MutableClock(long millis) {
            this.millis = millis;
        }

        /**
         * 前进指定时长。
         *
         * @param duration 时长
         */
        public void advance(Duration duration) {
            millis += duration.toMillis();
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
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
