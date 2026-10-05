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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.enums.AlertTriggerMode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 状态机与抖动抑制的用例（设计 §3.2 的 D1–D8 + §2.3 的「不可判定」语义）。
 *
 * <p>用例按「多轮序列」模拟真实评估：每轮把上一轮的决策结果喂回状态机——因为抖动抑制的**值**在序列里，
 * 单轮快照证明不了「连续 3 次」，只有序列能证明。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertStateMachineTest {

    /** 模拟中的实例状态（状态机只认这三个字段）。 */
    private static final class Sim {

        private AlertState state;
        private int count;
        private LocalDateTime startTs;
        private LocalDateTime firingTs;
        private AlertReason reason;

        /** 施加一次决策（等价于评估器把决策写回实例）。 */
        void apply(AlertStateMachine.Decision decision, LocalDateTime now) {
            switch (decision.kind()) {
                case NONE -> { }
                case CREATE_PENDING -> {
                    state = AlertState.PENDING;
                    count = decision.consecutiveCount();
                    startTs = now;
                }
                case CREATE_FIRING -> {
                    state = AlertState.FIRING;
                    count = decision.consecutiveCount();
                    startTs = now;
                    firingTs = decision.firingTs();
                }
                case DROP_PENDING -> {
                    // 候选被丢弃：实例不复存在（下一轮从「没有实例」重新开始）
                    state = null;
                    count = 0;
                    startTs = null;
                }
                case PROMOTE -> {
                    state = AlertState.FIRING;
                    count = decision.consecutiveCount();
                    firingTs = decision.firingTs();
                }
                case PROGRESS -> count = decision.consecutiveCount();
                case RESOLVE -> {
                    state = AlertState.RESOLVED;
                    reason = decision.reason();
                }
            }
        }
    }

    /** 一轮：跑决策并按结果更新模拟状态。 */
    private static AlertStateMachine.Decision round(Sim sim, AlertValueVerdict verdict, boolean recovery,
                                                    Boolean window, AlertTriggerMode mode, int threshold,
                                                    int pendingTtlSec, LocalDateTime now) {
        AlertStateMachine.Decision decision = AlertStateMachine.decide(sim.state, sim.count, sim.startTs,
            verdict, recovery, window, mode, threshold, pendingTtlSec, now);
        sim.apply(decision, now);
        return decision;
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    @Test
    @DisplayName("D1+D2 连续 N=3：前两次停在 PENDING 不触发，第三次转 FIRING")
    void consecutiveCountPromotesOnNth() {
        Sim sim = new Sim();
        AlertStateMachine.Decision first = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0);
        assertThat(first.kind()).isEqualTo(AlertStateMachine.Kind.CREATE_PENDING);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);
        assertThat(sim.firingTs).isNull();

        AlertStateMachine.Decision second = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(15));
        assertThat(second.kind()).isEqualTo(AlertStateMachine.Kind.PROGRESS);
        assertThat(sim.count).isEqualTo(2);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);

        AlertStateMachine.Decision third = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(30));
        assertThat(third.kind()).isEqualTo(AlertStateMachine.Kind.PROMOTE);
        assertThat(third.isFiring()).isTrue();
        assertThat(sim.state).isEqualTo(AlertState.FIRING);
        assertThat(sim.firingTs).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("D3 序列 35,25,35,35：中间回落清零 ⇒ 第 4 轮计数只有 2，仍未触发")
    void inRangeResetsCount() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0);
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0.plusSeconds(15));
        assertThat(sim.count).isEqualTo(2);

        AlertStateMachine.Decision flapped = round(sim, AlertValueVerdict.IN_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(30));
        assertThat(flapped.kind()).isEqualTo(AlertStateMachine.Kind.DROP_PENDING);
        assertThat(sim.state).isNull();

        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0.plusSeconds(45));
        assertThat(sim.count).isEqualTo(1);
        AlertStateMachine.Decision fourth = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(60));
        assertThat(fourth.kind()).isEqualTo(AlertStateMachine.Kind.PROGRESS);
        assertThat(sim.count).isEqualTo(2);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);
    }

    @Test
    @DisplayName("D4 序列 35,35,abc,35：不可判定那轮计数**保持不变** ⇒ 第 4 轮凑满 3 次并触发")
    void undecidableKeepsCountUnchanged() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0);
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0.plusSeconds(15));
        assertThat(sim.count).isEqualTo(2);

        AlertStateMachine.Decision undecidable = round(sim, AlertValueVerdict.NON_NUMERIC, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(30));
        assertThat(undecidable.kind()).isEqualTo(AlertStateMachine.Kind.NONE);
        // 🔴 变异哨兵：把「不可判定」当成「未越界」会在这里把计数清零 ⇒ 第 4 轮凑不满 3 次
        assertThat(sim.count).isEqualTo(2);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);

        AlertStateMachine.Decision fourth = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(45));
        assertThat(fourth.kind()).isEqualTo(AlertStateMachine.Kind.PROMOTE);
        assertThat(sim.state).isEqualTo(AlertState.FIRING);
    }

    @Test
    @DisplayName("D4-补充：坏质量与陈旧同样「计数不变」（三种不可判定一视同仁）")
    void badQualityAndStaleAlsoKeepCount() {
        for (AlertValueVerdict verdict : List.of(AlertValueVerdict.BAD_QUALITY, AlertValueVerdict.STALE,
            AlertValueVerdict.MISSING, AlertValueVerdict.INVALID_CONDITION)) {
            Sim sim = new Sim();
            round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3,
                300, T0);
            round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3,
                300, T0.plusSeconds(15));
            AlertStateMachine.Decision decision = round(sim, verdict, false, null,
                AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(30));
            assertThat(decision.kind()).as("%s 必须保持不动", verdict).isEqualTo(AlertStateMachine.Kind.NONE);
            assertThat(sim.count).as("%s 不得改变计数", verdict).isEqualTo(2);
            assertThat(sim.state).as("%s 不得改变状态", verdict).isEqualTo(AlertState.PENDING);
        }
    }

    @Test
    @DisplayName("D5 首轮越界后停发数据：超 pending_ttl_sec 后候选被放弃，**永不触发**")
    void pendingTtlExpiryDropsCandidate() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 60,
            T0);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);

        AlertStateMachine.Decision expired = round(sim, AlertValueVerdict.STALE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 60, T0.plusSeconds(61));
        assertThat(expired.kind()).isEqualTo(AlertStateMachine.Kind.DROP_PENDING);
        assertThat(sim.state).isNull();

        // 之后再来一次越界只会从 1 重新开始（不会残留「半个候选」）
        AlertStateMachine.Decision restart = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 60, T0.plusSeconds(90));
        assertThat(restart.kind()).isEqualTo(AlertStateMachine.Kind.CREATE_PENDING);
        assertThat(sim.count).isEqualTo(1);
    }

    @Test
    @DisplayName("D6 持续 T=60s：只持续 45s 就回落 ⇒ 不触发，且候选被丢弃")
    void durationNotReachedDoesNotFire() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.DURATION, 60, 300, T0);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.DURATION, 60, 300,
            T0.plusSeconds(45));
        assertThat(sim.firingTs).isNull();

        AlertStateMachine.Decision flapped = round(sim, AlertValueVerdict.IN_RANGE, false, null,
            AlertTriggerMode.DURATION, 60, 300, T0.plusSeconds(50));
        assertThat(flapped.kind()).isEqualTo(AlertStateMachine.Kind.DROP_PENDING);
        assertThat(sim.state).isNull();
    }

    @Test
    @DisplayName("D7 持续 T=60s：窗口校验通过后触发，**触发时刻取满足 60s 那一刻**（不是发现时刻）")
    void durationFiresAtSatisfiedMoment() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.DURATION, 60, 300, T0);
        // 轮次还没到 60s：不查窗口、不升级
        AlertStateMachine.Decision early = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.DURATION, 60, 300, T0.plusSeconds(45));
        assertThat(early.kind()).isEqualTo(AlertStateMachine.Kind.PROGRESS);
        // 已满 60s 且窗口校验通过（true）⇒ 升级，触发时刻 = T0 + 60s
        AlertStateMachine.Decision fired = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, Boolean.TRUE,
            AlertTriggerMode.DURATION, 60, 300, T0.plusSeconds(75));
        assertThat(fired.kind()).isEqualTo(AlertStateMachine.Kind.PROMOTE);
        assertThat(fired.firingTs()).isEqualTo(T0.plusSeconds(60));
        assertThat(sim.state).isEqualTo(AlertState.FIRING);
    }

    @Test
    @DisplayName("D7-补充：窗口不可求值（null）⇒ 不改状态、不触发（时序库读不到不是「持续越界」）")
    void durationWindowUnavailableKeepsPending() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.DURATION, 60, 300, T0);
        AlertStateMachine.Decision blocked = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.DURATION, 60, 300, T0.plusSeconds(75));
        assertThat(blocked.kind()).isEqualTo(AlertStateMachine.Kind.NONE);
        assertThat(sim.state).isEqualTo(AlertState.PENDING);

        // 窗口校验为 false（窗口内存在未越界点）⇒ 与「回落」同处置：丢弃候选
        AlertStateMachine.Decision invalid = round(sim, AlertValueVerdict.OUT_OF_RANGE, false,
            Boolean.FALSE, AlertTriggerMode.DURATION, 60, 300, T0.plusSeconds(90));
        assertThat(invalid.kind()).isEqualTo(AlertStateMachine.Kind.DROP_PENDING);
        assertThat(sim.state).isNull();
    }

    @Test
    @DisplayName("D8 抖动被吸收：PENDING 期间回落只丢弃候选、**不产生用户可见通知**（这里断言状态不升级）")
    void flapIsAbsorbedWithoutFiring() {
        Sim sim = new Sim();
        round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null, AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300,
            T0);
        AlertStateMachine.Decision flapped = round(sim, AlertValueVerdict.IN_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusSeconds(15));
        assertThat(flapped.kind()).isEqualTo(AlertStateMachine.Kind.DROP_PENDING);
        assertThat(flapped.isFiring()).isFalse();
        assertThat(flapped.isResolved()).isFalse();
        assertThat(sim.firingTs).isNull();
    }

    @Test
    @DisplayName("IMMEDIATE：首轮越界即 FIRING（不做抖动抑制）")
    void immediateFiresAtOnce() {
        Sim sim = new Sim();
        AlertStateMachine.Decision decision = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.IMMEDIATE, 0, 300, T0);
        assertThat(decision.kind()).isEqualTo(AlertStateMachine.Kind.CREATE_FIRING);
        assertThat(sim.firingTs).isEqualTo(T0);
    }

    @Test
    @DisplayName("N=1 的连续模式等价于立即（不产生一个多余的 PENDING 轮次）")
    void consecutiveCountOneFiresAtOnce() {
        Sim sim = new Sim();
        AlertStateMachine.Decision decision = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 1, 300, T0);
        assertThat(decision.kind()).isEqualTo(AlertStateMachine.Kind.CREATE_FIRING);
    }

    @Test
    @DisplayName("T7 活动告警读到合格未越界值 ⇒ RESOLVED，原因码为 RECOVERED")
    void firingResolvesOnInRange() {
        Sim sim = new Sim();
        sim.state = AlertState.FIRING;
        sim.firingTs = T0;
        AlertStateMachine.Decision decision = round(sim, AlertValueVerdict.IN_RANGE, true, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusMinutes(5));
        assertThat(decision.kind()).isEqualTo(AlertStateMachine.Kind.RESOLVE);
        assertThat(decision.reason()).isEqualTo(AlertReason.RECOVERED);
        assertThat(sim.state).isEqualTo(AlertState.RESOLVED);
    }

    @Test
    @DisplayName("T6/T8 活动告警遇到**不可判定**（陈旧/坏质量/无值）⇒ 保持 FIRING，绝不自动恢复")
    void firingNeverResolvesOnUndecidable() {
        for (AlertValueVerdict verdict : List.of(AlertValueVerdict.STALE, AlertValueVerdict.BAD_QUALITY,
            AlertValueVerdict.NON_NUMERIC, AlertValueVerdict.MISSING,
            AlertValueVerdict.INVALID_CONDITION)) {
            Sim sim = new Sim();
            sim.state = AlertState.FIRING;
            sim.firingTs = T0;
            AlertStateMachine.Decision decision = round(sim, verdict, false, null,
                AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusMinutes(5));
            assertThat(decision.kind()).as("%s 不得驱动状态变化", verdict)
                .isEqualTo(AlertStateMachine.Kind.NONE);
            assertThat(sim.state).as("%s 之后仍必须是 FIRING", verdict).isEqualTo(AlertState.FIRING);
        }
    }

    @Test
    @DisplayName("回差挡住时：未越界但未越过回差 ⇒ 保持活动（不清零、不恢复）")
    void deadbandBlocksRecovery() {
        Sim sim = new Sim();
        sim.state = AlertState.FIRING;
        sim.firingTs = T0;
        AlertStateMachine.Decision decision = round(sim, AlertValueVerdict.IN_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusMinutes(1));
        assertThat(decision.kind()).isEqualTo(AlertStateMachine.Kind.NONE);
        assertThat(sim.state).isEqualTo(AlertState.FIRING);
    }

    @Test
    @DisplayName("ACKED 也能恢复；ACKED 期间继续越界只更新进度（不重复打扰由通知侧承担）")
    void ackedStillEvaluated() {
        Sim sim = new Sim();
        sim.state = AlertState.ACKED;
        sim.firingTs = T0;
        AlertStateMachine.Decision progress = round(sim, AlertValueVerdict.OUT_OF_RANGE, false, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusMinutes(1));
        assertThat(progress.kind()).isEqualTo(AlertStateMachine.Kind.PROGRESS);
        assertThat(sim.state).isEqualTo(AlertState.ACKED);

        AlertStateMachine.Decision resolved = round(sim, AlertValueVerdict.IN_RANGE, true, null,
            AlertTriggerMode.CONSECUTIVE_COUNT, 3, 300, T0.plusMinutes(2));
        assertThat(resolved.kind()).isEqualTo(AlertStateMachine.Kind.RESOLVE);
        assertThat(sim.state).isEqualTo(AlertState.RESOLVED);
    }

    @Test
    @DisplayName("状态转换表穷举：RESOLVED 是终态，PENDING 只能到 FIRING，ACKED 不能回 FIRING")
    void transitionTableIsExhaustive() {
        assertThat(AlertState.PENDING.canTransitionTo(AlertState.FIRING)).isTrue();
        assertThat(AlertState.PENDING.canTransitionTo(AlertState.ACKED)).isFalse();
        assertThat(AlertState.FIRING.canTransitionTo(AlertState.ACKED)).isTrue();
        assertThat(AlertState.FIRING.canTransitionTo(AlertState.RESOLVED)).isTrue();
        assertThat(AlertState.ACKED.canTransitionTo(AlertState.FIRING)).isFalse();
        assertThat(AlertState.ACKED.canTransitionTo(AlertState.RESOLVED)).isTrue();
        assertThat(AlertState.RESOLVED.canTransitionTo(AlertState.FIRING)).isFalse();
        assertThat(AlertState.RESOLVED.isTerminal()).isTrue();
        assertThat(AlertState.PENDING.isActive()).isTrue();
        assertThat(AlertState.ACKED.isActive()).isTrue();
        assertThat(AlertState.RESOLVED.isActive()).isFalse();
    }

    @Test
    @DisplayName("模式非法（脏数据）⇒ 不判定也不猜（NONE）")
    void invalidModeDoesNothing() {
        AlertStateMachine.Decision decision = AlertStateMachine.decide(null, 0, null,
            AlertValueVerdict.OUT_OF_RANGE, false, null, null, 3, 300, T0);
        assertThat(decision.kind()).isEqualTo(AlertStateMachine.Kind.NONE);
    }

    @Test
    @DisplayName("🔴 不变式：无活动实例时只产出 NONE/CREATE_*（评估器据此才敢解引用 active）")
    void nullStateNeverProducesActiveRequiringKinds() {
        // AlertTenantEvaluator 的 fail-loud 守卫依赖本不变式：
        // DROP_PENDING/PROMOTE/PROGRESS/RESOLVE 四分支都会解引用 active，
        // 若状态机将来对 null state 产出它们，评估器会 NPE。此处全组合锁定。
        for (AlertTriggerMode mode : AlertTriggerMode.values()) {
            for (AlertValueVerdict verdict : AlertValueVerdict.values()) {
                AlertStateMachine.Decision decision = AlertStateMachine.decide(null, 0, null,
                    verdict, false, null, mode, 3, 300, T0);
                assertThat(decision.kind())
                    .as("mode=%s verdict=%s", mode, verdict)
                    .isIn(AlertStateMachine.Kind.NONE,
                        AlertStateMachine.Kind.CREATE_PENDING,
                        AlertStateMachine.Kind.CREATE_FIRING);
            }
        }
    }
}
