/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.enums.AlertTriggerMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 四态状态机 + 抖动抑制的**纯逻辑**（设计 §2.3）。
 *
 * <p>与 {@link AlertRules} 一样刻意做成无状态静态类：设计里「连续 N 次 / 持续 T 秒 / 不可判定 / 抖动吸收」
 * 的每一条都要能被用例逐条钉死（D1–D8），而在评估器里它们会被 DB、Redis、租户上下文淹没。</p>
 *
 * <p><b>不可判定的处理是本类最重要的语义</b>（设计 §2.3 明确要求写清楚）：收到
 * {@code MISSING / NON_NUMERIC / BAD_QUALITY / STALE / INVALID_CONDITION} 时
 * <b>计数保持不变、状态不变</b>——既不增（否则误报），也不清零（否则永远凑不满 N）。</p>
 *
 * <p><b>唯一例外是 pending TTL</b>：候选「挂太久」必须被放弃，否则稀疏数据下『一年前越界一次 +
 * 今天越界一次』会被拼成『连续 2 次』。TTL 检查先于判定结果，因此「设备停发数据」也能让候选到期消失
 * （设计 D5）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public final class AlertStateMachine {

    private AlertStateMachine() {
    }

    /** 状态机本轮的**动作**。 */
    public enum Kind {

        /** 什么都不做（不可判定、或未命中任何转换）。 */
        NONE,

        /** 新建 PENDING 实例（首次越界但未满足持续条件）。 */
        CREATE_PENDING,

        /** 新建 FIRING 实例（立即模式、N≤1、或断档映射）。 */
        CREATE_FIRING,

        /** 丢弃 PENDING 候选（抖动被吸收或 TTL 到期）。 */
        DROP_PENDING,

        /** 既有 PENDING 升为 FIRING。 */
        PROMOTE,

        /** 只更新进度（当前值/计数），状态不变。 */
        PROGRESS,

        /** 活动告警恢复为 RESOLVED。 */
        RESOLVE
    }

    /**
     * 本轮决策（不可变）。
     *
     * @param kind             动作
     * @param consecutiveCount 决策后的连续计数
     * @param targetState      目标状态（{@link Kind#NONE}/UPDATE 时为当前状态；仅作日志与断言参考）
     * @param firingTs         正式触发时刻（仅 {@link Kind#CREATE_FIRING}/{@link Kind#PROMOTE} 有值）
     * @param reason           结束原因码（仅 {@link Kind#RESOLVE} 有值）
     */
    public record Decision(Kind kind, int consecutiveCount, AlertState targetState,
                           LocalDateTime firingTs, AlertReason reason) {

        /** 是否产生了新的活动告警（用于通知与计数）。 */
        public boolean isFiring() {
            return kind == Kind.CREATE_FIRING || kind == Kind.PROMOTE;
        }

        /** 是否恢复了告警。 */
        public boolean isResolved() {
            return kind == Kind.RESOLVE;
        }
    }

    /** 「什么都不做」的共享返回值。 */
    private static final Decision NONE = new Decision(Kind.NONE, 0, null, null, null);

    /**
     * 计算本轮决策。
     *
     * @param currentState      当前活动实例的状态；{@code null} = 没有活动实例
     * @param currentCount      当前连续计数（无实例时为 0）
     * @param startTs           当前实例的首次越界时刻（无实例/无值时 {@code null}）
     * @param verdict           本次判定结果
     * @param recoverySatisfied 是否已越过**恢复门槛**（含回差；由调用方用
     *                          {@link AlertRules#recovered} 算好）
     * @param windowSatisfied   持续窗口是否成立；{@code null} = **不可求值**（时序库读不到）
     * @param mode              抖动抑制模式
     * @param threshold         {@code CONSECUTIVE_COUNT} 的 N 或 {@code DURATION} 的秒数
     * @param pendingTtlSec     候选最大挂起秒数（{@code <= 0} = 不做 TTL 兜底）
     * @param now               当前时刻
     * @return 决策
     */
    public static Decision decide(AlertState currentState, int currentCount, LocalDateTime startTs,
                                  AlertValueVerdict verdict, boolean recoverySatisfied,
                                  Boolean windowSatisfied, AlertTriggerMode mode, int threshold,
                                  int pendingTtlSec, LocalDateTime now) {
        if (mode == null) {
            // 模式非法（库里脏数据）：不判定比按某个模式猜要安全，且必须可见（调用方会校验规则）
            return NONE;
        }
        if (currentState == null) {
            if (verdict != AlertValueVerdict.OUT_OF_RANGE) {
                return NONE;
            }
            return switch (mode) {
                case IMMEDIATE -> new Decision(Kind.CREATE_FIRING, 1, AlertState.FIRING, now, null);
                case CONSECUTIVE_COUNT -> threshold <= 1
                    ? new Decision(Kind.CREATE_FIRING, 1, AlertState.FIRING, now, null)
                    : new Decision(Kind.CREATE_PENDING, 1, AlertState.PENDING, null, null);
                case DURATION -> new Decision(Kind.CREATE_PENDING, 0, AlertState.PENDING, null, null);
            };
        }
        if (currentState == AlertState.PENDING) {
            // TTL 兜底先行：过期候选一律放弃（无论本轮判定如何），避免稀疏数据把「连续」拉成跨天
            if (pendingTtlSec > 0 && startTs != null
                && Duration.between(startTs, now).getSeconds() > pendingTtlSec) {
                return new Decision(Kind.DROP_PENDING, currentCount, AlertState.PENDING, null, null);
            }
            if (verdict == AlertValueVerdict.IN_RANGE) {
                // 抖动被吸收：PENDING 期间回落 ⇒ 直接丢弃，用户完全无感，只计数
                return new Decision(Kind.DROP_PENDING, currentCount, AlertState.PENDING, null, null);
            }
            if (verdict != AlertValueVerdict.OUT_OF_RANGE) {
                // 不可判定：计数保持不变、状态不变（既不清零也不虚增）
                return NONE;
            }
            int count = currentCount + 1;
            return switch (mode) {
                case IMMEDIATE -> new Decision(Kind.PROMOTE, count, AlertState.FIRING, now, null);
                case CONSECUTIVE_COUNT -> count >= threshold
                    ? new Decision(Kind.PROMOTE, count, AlertState.FIRING, now, null)
                    : new Decision(Kind.PROGRESS, count, AlertState.PENDING, null, null);
                case DURATION -> durationStep(startTs, windowSatisfied, threshold, count, now);
            };
        }
        // FIRING / ACKED：活动告警
        if (verdict == AlertValueVerdict.OUT_OF_RANGE) {
            return new Decision(Kind.PROGRESS, currentCount, currentState, null, null);
        }
        if (verdict == AlertValueVerdict.IN_RANGE && recoverySatisfied) {
            return new Decision(Kind.RESOLVE, currentCount, AlertState.RESOLVED, null,
                AlertReason.RECOVERED);
        }
        // 未越界但未越过回差（回差挡住了）或不可判定 ⇒ 保持活动（**陈旧绝不等于恢复**）
        return NONE;
    }

    /** 持续模式的推进：未满 T 秒只记进度；满 T 秒后按时序窗口结论决定升级 / 丢弃 / 保持。 */
    private static Decision durationStep(LocalDateTime startTs, Boolean windowSatisfied, int threshold,
                                        int count, LocalDateTime now) {
        if (startTs == null) {
            return new Decision(Kind.PROGRESS, count, AlertState.PENDING, null, null);
        }
        long elapsed = Duration.between(startTs, now).getSeconds();
        if (elapsed < threshold) {
            return new Decision(Kind.PROGRESS, count, AlertState.PENDING, null, null);
        }
        if (windowSatisfied == null) {
            // 时序库读不到 ⇒ 窗口**不可求值**：不改状态、不发通知（设计 §2.2.4 表中那一行）
            return NONE;
        }
        if (!windowSatisfied) {
            // 窗口内存在未越界的点 ⇒ 本次持续不成立，候选丢弃（与「回落」同处置）
            return new Decision(Kind.DROP_PENDING, count, AlertState.PENDING, null, null);
        }
        // 触发时刻取「满足 T 秒的那一刻」，不是发现时刻（设计 D7）
        return new Decision(Kind.PROMOTE, count, AlertState.FIRING,
            startTs.plusSeconds(threshold), null);
    }
}
