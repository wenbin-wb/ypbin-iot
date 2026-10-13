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
 * 跨实例互斥锁（#220 多节点生产化）。
 *
 * <p><b>为什么需要它</b>：仓内既有的一批后台任务（告警评估、断档映射、通知投递、保留清理、
 * 平台自告警）都按「单实例」语义设计，用进程内原语（{@code AtomicBoolean} 等）防重入。
 * 多副本部署下这些原语只看得到自己那个 JVM ⇒ 同一个租户会被两个副本同时评估、同一条清理会被跑两遍。
 * 本接口提供的就是「同一时刻全体副本里只有一个能拿到锁」的语义。</p>
 *
 * <p><b>获取失败 = 跳过，不是报错</b>：{@link #tryAcquire(String)} 在锁被其它实例持有时返回
 * {@code null}，而不是抛异常。调用方（{@link TaskLockGuard}）据此「跳过本轮、下一轮再来」——
 * 这正是后台任务的正确重试方式，报错只会被调度器记成异常噪音。</p>
 *
 * <p><b>实现必须带租约</b>：锁的持有有 TTL，持锁实例崩溃后最多 TTL 时间锁就会被别的实例接管；
 * 因此持锁期间要周期性 {@link LockLease#renew()}（由 {@link TaskLockGuard} 负责）。
 * 不用「无 TTL 的锁」——那会把一个实例的崩溃变成整条后台任务永久停摆。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public interface DistributedLock {

    /**
     * 尝试获取锁（非阻塞）。
     *
     * @param lockKey 锁键（调用方应带上统一前缀，见 {@link LockNames}）
     * @return 获取成功返回租约；锁已被其它实例持有返回 {@code null}（调用方跳过本轮）；
     *         存储层异常（Redis 不可达等）会抛出 {@code RuntimeException}，由调用方按
     *         {@code ypbin.lock.fail-open} 决定继续执行（fail-open）还是上抛（fail-closed）
     */
    LockLease tryAcquire(String lockKey);
}
