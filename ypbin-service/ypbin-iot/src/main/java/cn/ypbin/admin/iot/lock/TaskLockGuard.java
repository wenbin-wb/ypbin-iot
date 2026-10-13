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

import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 后台任务的「先抢锁、抢不到就跳过」守卫（#220）。
 *
 * <p>所有需要单实例执行的后台任务都通过它包裹执行，语义集中在三处：</p>
 * <ol>
 *   <li><b>抢到锁</b> ⇒ 执行任务，并在执行期间由看门狗自动续期；</li>
 *   <li><b>没抢到</b> ⇒ **跳过**（返回 {@code executed=false}），记 info 日志 + 计数，不报错、不排队；</li>
 *   <li><b>存储不可用</b> ⇒ 默认 fail-open（记 error 全堆栈后继续执行），可配 {@code ypbin.lock.fail-open=false}
 *       改为 fail-closed（把异常上抛，让调度器记成任务失败）。</li>
 * </ol>
 *
 * <p><b>绝不泄漏锁</b>：无论任务正常返回还是抛异常，{@code finally} 都会先取消续期看门狗、
 * 再释放租约。这是本类存在的最大理由——把「忘记释放」这类错误从每个任务里消除掉。</p>
 *
 * <p><b>指标</b>：{@value #METRIC_SKIPPED} / {@value #METRIC_UNAVAILABLE} / {@value #METRIC_RENEW_FAILED}
 * 按 {@code task} tag 区分，用于回答「某个任务到底有没有在某个副本上跑」「锁存储是不是一直在抖」。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@Component
public class TaskLockGuard {

    private static final Logger log = LoggerFactory.getLogger(TaskLockGuard.class);

    /** 因锁被其它实例持有而跳过的次数。 */
    public static final String METRIC_SKIPPED = "iot.lock.skipped";

    /** 锁存储不可用、按 fail-open 降级执行的次数。 */
    public static final String METRIC_UNAVAILABLE = "iot.lock.unavailable";

    /** 续期失败的次数（>0 说明锁存储不稳定或任务时长已逼近 TTL）。 */
    public static final String METRIC_RENEW_FAILED = "iot.lock.renew.failed";

    private final DistributedLock distributedLock;

    private final LeaseRenewalScheduler renewalScheduler;

    private final LockProperties properties;

    private final MeterRegistry meterRegistry;

    public TaskLockGuard(DistributedLock distributedLock, LeaseRenewalScheduler renewalScheduler,
                         LockProperties properties, MeterRegistry meterRegistry) {
        this.distributedLock = distributedLock;
        this.renewalScheduler = renewalScheduler;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 抢到锁才执行（无返回值任务）。
     *
     * @param lockName 锁名（{@link LockNames} 里的常量）
     * @param task     任务
     * @return 是否执行了任务
     */
    public boolean runExclusively(String lockName, Runnable task) {
        return callExclusively(lockName, () -> {
            task.run();
            return Boolean.TRUE;
        }).executed();
    }

    /**
     * 抢到锁才执行（有返回值任务）。
     *
     * @param lockName 锁名
     * @param task     任务
     * @param <T>      返回值类型
     * @return 执行结果（跳过时 {@code executed=false}）
     */
    public <T> LockOutcome<T> callExclusively(String lockName, Supplier<T> task) {
        String lockKey = properties.getKeyPrefix() + lockName;
        LockLease lease;
        try {
            lease = distributedLock.tryAcquire(lockKey);
        } catch (RuntimeException ex) {
            if (!properties.isFailOpen()) {
                // fail-closed：不假装执行过，如实上抛让调度器记成任务失败
                throw ex;
            }
            counter(METRIC_UNAVAILABLE, lockName).increment();
            log.error("[iot] 分布式锁不可用，按 fail-open **继续执行**任务 {}（多副本下可能重复执行；"
                + "如需严格单实例请设 {}.fail-open=false）", lockName, LockProperties.PREFIX, ex);
            return new LockOutcome<>(true, true, task.get());
        }
        if (lease == null) {
            counter(METRIC_SKIPPED, lockName).increment();
            log.info("[iot] 任务 {} 的分布式锁已被其它实例持有，本次**跳过**（获取失败=跳过，不报错）", lockName);
            return LockOutcome.skipped();
        }
        LeaseRenewalScheduler.Cancellable renewal = renewalScheduler.schedule(
            () -> renewOrLog(lockName, lease), properties.getRenewInterval());
        try {
            return new LockOutcome<>(true, false, task.get());
        } finally {
            renewal.cancel();
            lease.release();
        }
    }

    /** 看门狗续期：失败不抛（续期是尽力而为），但必须计数 + 留痕。 */
    private void renewOrLog(String lockName, LockLease lease) {
        if (lease.renew()) {
            return;
        }
        counter(METRIC_RENEW_FAILED, lockName).increment();
        log.warn("[iot] 任务 {} 的锁续期失败（锁可能已被其它实例接管）：本轮仍会跑完，"
            + "但多副本下该时间窗内可能出现并发执行", lockName);
    }

    /** 按任务取计数（Micrometer 对同 id 幂等注册，无需自行缓存）。 */
    private Counter counter(String name, String lockName) {
        return Counter.builder(name)
            .description(name)
            .tag("task", LogSanitizer.sanitize(lockName))
            .register(meterRegistry);
    }
}
