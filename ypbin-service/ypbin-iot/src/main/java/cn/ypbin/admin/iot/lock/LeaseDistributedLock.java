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

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带租约的分布式锁（#220）。
 *
 * <p>把「租约」这件事做在应用层：每次加锁写下的值里带**到期时刻**，存储层只需原子地判断
 * 「不存在 or 已过期」即可抢锁。于是 TTL 的行为不再依赖 Redis 的服务端过期精度，
 * 也可以被可注入时钟精确驱动（测试据此不真等）。</p>
 *
 * <p><b>token 的作用</b>：每个锁实例有一个随机的 {@code instanceId}，每次获取再拼一个随机串，
 * 保证「续期/释放只对自己那一把锁生效」——实例 A 的租约过期、实例 B 抢到之后，
 * A 迟到的续期/释放不会把 B 的锁删掉。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class LeaseDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(LeaseDistributedLock.class);

    private final LockStore store;

    private final LockProperties properties;

    private final Clock clock;

    /** 本实例标识（每个锁对象一个，用于 token 可读性与排障）。 */
    private final String instanceId = UUID.randomUUID().toString();

    LeaseDistributedLock(LockStore store, LockProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public LockLease tryAcquire(String lockKey) {
        long ttlMillis = properties.getTtl().toMillis();
        long nowMillis = clock.millis();
        long expireAtMillis = nowMillis + ttlMillis;
        String token = instanceId + ':' + UUID.randomUUID();
        boolean acquired = store.tryLock(lockKey, token, expireAtMillis, nowMillis, ttlMillis);
        if (!acquired) {
            return null;
        }
        return new Lease(lockKey, token, expireAtMillis, ttlMillis);
    }

    /**
     * 一次成功获取的租约。
     *
     * <p>{@link #held} 是 volatile：看门狗线程续期失败时置 false，任务线程可能读它。
     * {@link #expireAtMillis} 仅用于观测/断言，同样 volatile。</p>
     */
    private final class Lease implements LockLease {

        private final String key;

        private final String token;

        private final long ttlMillis;

        private volatile long expireAtMillis;

        private volatile boolean held = true;

        private Lease(String key, String token, long expireAtMillis, long ttlMillis) {
            this.key = key;
            this.token = token;
            this.expireAtMillis = expireAtMillis;
            this.ttlMillis = ttlMillis;
        }

        @Override
        public String lockKey() {
            return key;
        }

        @Override
        public boolean isHeld() {
            return held;
        }

        @Override
        public long expiresAtMillis() {
            return expireAtMillis;
        }

        @Override
        public boolean renew() {
            if (!held) {
                return false;
            }
            try {
                long nowMillis = clock.millis();
                long newExpireAtMillis = nowMillis + ttlMillis;
                if (store.renew(key, token, newExpireAtMillis, ttlMillis)) {
                    expireAtMillis = newExpireAtMillis;
                    return true;
                }
                held = false;
                return false;
            } catch (RuntimeException ex) {
                // 续期是看门狗：存储抖动不应把任务本身打断；如实置为「已丢失」并记全堆栈
                held = false;
                log.error("[iot] 分布式锁续期异常，按「锁已丢失」处理：key={}", key, ex);
                return false;
            }
        }

        @Override
        public void release() {
            held = false;
            try {
                boolean released = store.unlock(key, token);
                if (released) {
                    log.debug("[iot] 分布式锁已释放：key={}", key);
                }
            } catch (RuntimeException ex) {
                // 释放失败不吞：记全堆栈。残留的锁会由 TTL 兜底过期，不会永久挡路
                log.error("[iot] 分布式锁释放失败（将由 TTL 兜底过期）：key={}", key, ex);
            }
        }
    }
}
