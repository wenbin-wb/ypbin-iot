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
 * 空锁实现（{@code ypbin.lock.enabled=false} 时装配）。
 *
 * <p>语义：永远「抢到」，即**退回到加锁之前的行为**——每个副本都会执行任务。
 * 仅供单实例部署或排障时显式关闭锁用；多副本下会重复执行，配置层会打 WARN。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class NoopDistributedLock implements DistributedLock {

    @Override
    public LockLease tryAcquire(String lockKey) {
        return new NoopLease(lockKey);
    }

    /** 永远「持有」的租约（续期恒成功、释放无动作）。 */
    private static final class NoopLease implements LockLease {

        private final String lockKey;

        private NoopLease(String lockKey) {
            this.lockKey = lockKey;
        }

        @Override
        public String lockKey() {
            return lockKey;
        }

        @Override
        public boolean isHeld() {
            return true;
        }

        @Override
        public long expiresAtMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public boolean renew() {
            return true;
        }

        @Override
        public void release() {
            // 空锁没有需要释放的资源：显式留空而非省略，表明这是设计而非遗漏
        }
    }
}
