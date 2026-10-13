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

/**
 * 一次成功加锁的租约（#220）。
 *
 * <p><b>生命周期</b>：{@link #renew()} 在 TTL 到期前把租约往后推；{@link #release()} 立即释放。
 * 持锁方必须保证 {@code release()} 一定被调用（用 {@code try/finally}）——否则一次异常就会让锁
 * 一直挂到 TTL 结束，期间所有副本都跑不了这条任务。{@link TaskLockGuard} 已经用
 * {@code try/finally} 兜住了这件事。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public interface LockLease extends AutoCloseable {

    /**
     * 锁键。
     *
     * @return 锁键
     */
    String lockKey();

    /**
     * 是否仍持有锁（续期失败或已释放后为 {@code false}）。
     *
     * @return 是否仍持有
     */
    boolean isHeld();

    /**
     * 租约到期时刻（epoch 毫秒；用于观测与测试断言）。
     *
     * @return 到期时刻
     */
    long expiresAtMillis();

    /**
     * 续期（把到期时刻推到「现在 + TTL」）。
     *
     * <p>续期失败（锁已被别的实例接管 / 存储不可用）时返回 {@code false} 并把 {@link #isHeld()}
     * 置为 {@code false}，**不抛异常**——续期只是看门狗，抛异常会打断任务本身。</p>
     *
     * @return 是否续期成功
     */
    boolean renew();

    /**
     * 释放锁（按 token 比对，只有仍是自己的锁才会被删除；重复调用无害）。
     */
    void release();

    @Override
    default void close() {
        release();
    }
}
