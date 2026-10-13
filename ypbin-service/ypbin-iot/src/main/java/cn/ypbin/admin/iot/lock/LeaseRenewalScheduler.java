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

/**
 * 锁租约续期的调度抽象（#220）。
 *
 * <p>抽出来是为了让 {@link TaskLockGuard} 的测试不必依赖真实线程与真实等待；
 * 生产实现是 {@link ScheduledLeaseRenewalScheduler}。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
interface LeaseRenewalScheduler {

    /**
     * 按固定间隔重复执行续期任务。
     *
     * @param task     续期任务
     * @param interval 间隔
     * @return 可取消句柄（任务结束时应取消）
     */
    Cancellable schedule(Runnable task, Duration interval);

    /** 可取消句柄。 */
    interface Cancellable {

        /** 取消后续执行（幂等）。 */
        void cancel();
    }
}
