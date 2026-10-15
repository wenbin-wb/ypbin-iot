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

import cn.ypbin.starter.tools.lock.LeaseLockGuard;
import cn.ypbin.starter.tools.lock.LeaseLockProperties;
import cn.ypbin.starter.tools.lock.LeaseLockService;
import cn.ypbin.starter.tools.lock.LockLease;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * 宿主侧分布式锁的**测试支撑**（2026-10-15 task-18 重写）。
 *
 * <p><b>为什么还在业务仓</b>：锁的**实现**已上收到 ypbin-starter（其自身有
 * {@code LeaseLockGuardTest}/{@code LeaseLockServiceTest} 等覆盖抢锁/过期接管/续期/释放/fail-open），
 * 但「IoT 的哪些后台任务要互斥、锁名是什么」是**宿主业务语义**，验证这层接线仍需一个能在宿主测试里
 * 构造守卫的入口。本类只依赖 starter 的**公开 API**（{@link LeaseLockService}/{@link LeaseLockGuard}），
 * 不再复刻任何锁实现。</p>
 *
 * <p><b>为什么用内存假实现而不是 starter 的 {@code LeaseLockServiceImpl.inMemory()}</b>：后者时间源固定为
 * {@code Clock.systemUTC()}（不可注入），无法在单测里驱动「TTL 到期后接管」这类时序；而
 * starter 的 {@code InMemoryLeaseLockStore} 是包级可见、也未发布 test-jar，宿主侧无法复用。
 * 故这里用一个约 40 行的进程内 {@link LeaseLockService} 假实现：语义与 starter 的内存实现一致
 * （同一 key 同时只有一个持有者、支持主动 release），足以驱动宿主接线用例。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class IotLockTestSupport {

    private IotLockTestSupport() {
    }

    /**
     * 默认配置（与 nacos 显式配置一致的前缀；TTL/续期沿用 starter 默认）。
     *
     * @return 配置
     */
    public static LeaseLockProperties properties() {
        return new LeaseLockProperties();
    }

    /**
     * 共享同一份内存存储的锁服务（同一 service 的所有调用视为共享介质上的不同节点；用于模拟多副本）。
     *
     * @return 锁服务
     */
    public static LeaseLockService sharedLockService() {
        return new InMemoryLeaseLockService();
    }

    /**
     * 独立的内存锁服务（单实例语义，供不关心多节点的用例使用）。
     *
     * @return 锁服务
     */
    public static LeaseLockService inMemoryLockService() {
        return new InMemoryLeaseLockService();
    }

    /**
     * 用「不自动续期」的调度器包住锁服务（单测里由用例显式驱动，避免后台线程干扰断言）。
     *
     * @param lockService 锁服务
     * @param properties  配置
     * @param registry    指标
     * @return 守卫
     */
    public static LeaseLockGuard guard(LeaseLockService lockService, LeaseLockProperties properties,
                                       MeterRegistry registry) {
        return new LeaseLockGuard(lockService, (task, interval) -> () -> {
            // 测试里不自动续期：需要验证续期时由用例直接调用 lease.renew()
        }, properties, registry);
    }

    /**
     * 基于独立内存锁服务的守卫（单实例语义）。
     *
     * @param properties 配置
     * @param registry   指标
     * @return 守卫
     */
    public static LeaseLockGuard guard(LeaseLockProperties properties, MeterRegistry registry) {
        return guard(inMemoryLockService(), properties, registry);
    }

    /**
     * 进程内租约锁假实现（语义与 starter 的内存实现一致：同 key 同时只有一个持有者）。
     *
     * <p>仅用于宿主接线用例；生产装配始终由 starter 的自动配置提供（Redis 优先、内存兜底）。</p>
     */
    public static final class InMemoryLeaseLockService implements LeaseLockService {

        /** key → 持有者 token（不存在 = 无人持有）。 */
        private final Map<String, String> held = new ConcurrentHashMap<>();

        private long sequence;

        @Override
        @Nullable
        public LockLease tryAcquire(String lockKey) {
            String token = "test-" + (++sequence);
            if (held.putIfAbsent(lockKey, token) != null) {
                // 已被其它实例持有 ⇒ 返回 null（**不是**抛异常：获取失败 = 跳过本轮）
                return null;
            }
            return new TestLease(lockKey, token);
        }

        /** 进程内租约：release 按 token 比对，拿旧租约释放不得删掉新持有者的锁。 */
        private final class TestLease implements LockLease {

            private final String key;

            private final String token;

            private volatile boolean released;

            TestLease(String key, String token) {
                this.key = key;
                this.token = token;
            }

            @Override
            public String lockKey() {
                return key;
            }

            @Override
            public boolean isHeld() {
                return !released && token.equals(held.get(key));
            }

            @Override
            public long expiresAtMillis() {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean renew() {
                return isHeld();
            }

            @Override
            public void release() {
                if (released) {
                    return;
                }
                released = true;
                // 只有仍是自己持有的锁才删（token 比对），避免误删他人刚接管的锁
                held.remove(key, token);
            }
        }
    }
}
