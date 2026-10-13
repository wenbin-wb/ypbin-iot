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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

/**
 * 生产用续期调度器：一条守护线程，按固定间隔给所有在跑的租约续期（#220）。
 *
 * <p>为什么必须自动续期而不是让任务自己记得调 {@code renew()}：任务一旦进入长循环
 * （例如保留清理的分批删除、告警评估的多租户轮询），开发者很容易漏掉续期点，
 * 而漏掉的后果是「锁在任务跑一半时过期，第二个副本开始并行执行」——静默且难复现。
 * 交给一个统一的看门狗，任务代码就不必关心这件事。</p>
 *
 * <p>守护线程（daemon）+ 单线程：它只做一次 Redis 往返，负载可忽略；应用关闭时由
 * {@link #destroy()} 显式停止，不阻塞 JVM 退出。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ScheduledLeaseRenewalScheduler implements LeaseRenewalScheduler, DisposableBean {

    /** 看门狗线程名（排障时一眼能认出）。 */
    static final String THREAD_NAME = "iot-task-lock-renewal";

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Cancellable schedule(Runnable task, Duration interval) {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, interval.toMillis(),
            interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
