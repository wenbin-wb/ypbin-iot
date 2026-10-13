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
import java.time.Duration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis 上的双进程互斥演示（#220，手工运行，不属于 JUnit 套件）。
 *
 * <p>两个独立 JVM 进程连同一个 Redis、抢同一把锁：占锁进程在跑「4 个租户」的一轮任务，
 * 另一进程的尝试必须全部失败、直到锁释放才能接管。把它做成 {@code main} 而不是测试，
 * 是为了能在命令行上把两个进程的输出并排展示（可复现命令见 {@code docs/MULTINODE-OPS.md}）。</p>
 *
 * <p>用法：{@code java ... RedisLockTwoProcessDemo <name> <holdMillis> <waitMillis>}
 * （默认连 {@code 127.0.0.1:6379}，可用环境变量 {@code REDIS_HOST}/{@code REDIS_PORT} 覆盖）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class RedisLockTwoProcessDemo {

    private static final String LOCK_NAME = LockNames.ALERT_EVALUATE;

    private static final int TENANT_COUNT = 4;

    private RedisLockTwoProcessDemo() {
    }

    /**
     * 程序入口。
     *
     * @param args name holdMillis waitMillis
     * @throws Exception 中断/连接异常
     */
    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "node";
        long holdMillis = args.length > 1 ? Long.parseLong(args[1]) : 2_000L;
        long waitMillis = args.length > 2 ? Long.parseLong(args[2]) : 15_000L;
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        LockProperties properties = new LockProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setRenewInterval(Duration.ofSeconds(10));
        DistributedLock lock = new LeaseDistributedLock(new RedisLockStore(template), properties,
            Clock.systemUTC());
        String key = properties.getKeyPrefix() + LOCK_NAME;

        long deadline = System.currentTimeMillis() + waitMillis;
        LockLease lease = lock.tryAcquire(key);
        while (lease == null && System.currentTimeMillis() < deadline) {
            log(name, "SKIPPED：锁被其它进程持有，等待重试…");
            Thread.sleep(500L);
            lease = lock.tryAcquire(key);
        }
        if (lease == null) {
            log(name, "GAVE UP：等待超时仍未拿到锁");
            factory.destroy();
            return;
        }
        log(name, "ACQUIRED 锁（key=" + key + "，TTL=" + properties.getTtl().toSeconds() + "s）");
        for (int tenant = 1; tenant <= TENANT_COUNT; tenant++) {
            log(name, "处理 tenant-" + tenant);
            Thread.sleep(holdMillis / TENANT_COUNT);
        }
        lease.release();
        log(name, "RELEASED 锁");
        factory.destroy();
    }

    private static void log(String name, String message) {
        System.out.println("[" + System.currentTimeMillis() + "][" + name + "] " + message);
        System.out.flush();
    }
}
