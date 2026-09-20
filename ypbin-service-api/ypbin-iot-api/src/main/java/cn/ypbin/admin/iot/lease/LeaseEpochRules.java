/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import java.time.LocalDateTime;

/**
 * 租约与台账版本号（epoch）的<b>纯函数判据</b>：不碰库、不碰时间源，全部可单测。
 *
 * <p>为什么单独抽出来：这些判据散落在各处的后果是「同一件事有两套口径」——旧仓的复核就因为
 * 「契约写了组合判据、实现却另写一套」而判过问题。放这里以后，实现只能引用它们。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public final class LeaseEpochRules {

    /** 首次分配时的台账版本号。 */
    public static final long INITIAL_EPOCH = 1L;

    private LeaseEpochRules() {
    }

    /**
     * 快照是否可以采用（仅当快照版本更新）。
     *
     * @param localEpoch    本地版本号
     * @param snapshotEpoch 快照版本号
     * @return 可采用返回 {@code true}
     */
    public static boolean shouldAdoptSnapshot(long localEpoch, long snapshotEpoch) {
        return snapshotEpoch > localEpoch;
    }

    /**
     * 事件是否应当应用（仅当事件版本更新）。
     *
     * @param localEpoch 本地版本号
     * @param eventEpoch 事件版本号
     * @return 应当应用返回 {@code true}
     */
    public static boolean shouldApplyEvent(long localEpoch, long eventEpoch) {
        return eventEpoch > localEpoch;
    }

    /**
     * 推进版本号。
     *
     * @param currentEpoch 当前版本号
     * @return 下一个版本号
     * @throws IllegalArgumentException 已达上限（不静默溢出）
     */
    public static long nextEpoch(long currentEpoch) {
        if (currentEpoch == Long.MAX_VALUE) {
            throw new IllegalArgumentException("台账版本号已达上限，无法继续递增：epoch=" + currentEpoch);
        }
        return currentEpoch + 1;
    }

    /**
     * 该状态是否必须停采（非 ACTIVE 一律停采）。
     *
     * @param state 状态
     * @return 必须停采返回 {@code true}
     */
    public static boolean shouldFence(LeaseState state) {
        return state != null && state != LeaseState.ACTIVE;
    }

    /**
     * 组合判据：状态失效<b>或</b>租约已过期 ⇒ 必须自我停采。
     *
     * @param state         状态
     * @param leaseExpireAt 到期时间
     * @param now           当前时刻
     * @return 必须自我停采返回 {@code true}
     */
    public static boolean needsSelfFence(LeaseState state, LocalDateTime leaseExpireAt, LocalDateTime now) {
        if (shouldFence(state)) {
            return true;
        }
        return leaseExpireAt == null || isLeaseExpired(leaseExpireAt, now);
    }

    /**
     * 快照之后的事件是否要重放。
     *
     * @param snapshotEpoch 快照版本号
     * @param eventEpoch    事件版本号
     * @return 要重放返回 {@code true}
     */
    public static boolean shouldReplayAfterSnapshot(long snapshotEpoch, long eventEpoch) {
        return eventEpoch > snapshotEpoch;
    }

    /**
     * 租约是否已到期（到期时刻算已过期：{@code !expireAt.isAfter(now)}）。
     *
     * @param leaseExpireAt 到期时间
     * @param now           当前时刻
     * @return 已到期返回 {@code true}
     */
    public static boolean isLeaseExpired(LocalDateTime leaseExpireAt, LocalDateTime now) {
        return !leaseExpireAt.isAfter(now);
    }
}
