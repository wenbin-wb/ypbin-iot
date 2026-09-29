/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 平台健康判定器用例（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §8 判据 1）。
 *
 * <p>覆盖重点（都是"做错了看不出来"的坑）：</p>
 * <ol>
 *   <li>**每条规则都必须有覆盖**（参数化穷举枚举，新增规则时用例会红）；</li>
 *   <li>**`lag = -1` 必须判触发**——产出侧约定 `-1` = 从未成功过；
 *       若按数值比大小会判成"健康"，而真相是评估器**从未跑起来**（最该告警的情况）；</li>
 *   <li>**阈值边界**：恰好等于阈值不算触发（`>` 而非 `>=`），必须有用例钉住；</li>
 *   <li>**指标缺失 ⇒ UNKNOWN 而非 HEALTHY**——否则采集坏掉时监控会报平安；</li>
 *   <li>**首次运行无基线**：当前值 > 0 仍要告警（问题在观察前已发生），= 0 则 UNKNOWN。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class PlatformHealthEvaluatorTest {

    private static PlatformHealthSnapshot snap(Long lag, Long roundFailed, Long notifyFailed,
                                               Long unmapped, Long orphan) {
        return new PlatformHealthSnapshot(lag, roundFailed, notifyFailed, unmapped, orphan);
    }

    // ===================== EVALUATOR_STALLED =====================

    @Test
    @DisplayName("🔴 lag=-1（从未成功过）必须判触发，不能因为 -1 < 阈值 就当成健康")
    void neverSucceededLagMustFire() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.EVALUATOR_STALLED, snap(-1L, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state())
            .as("产出侧用 -1 表示『从未成功过』；按数值比较会误判为健康，而这是最该告警的情况")
            .isEqualTo(PlatformHealthState.FIRING);
        assertThat(verdict.summary()).contains("从未成功");
    }

    @Test
    @DisplayName("正常滞后（实测峰值 15040ms）必须判健康——不能按直觉用 10s 阈值")
    void normalSawtoothLagMustBeHealthy() {
        // 这三个值取自已实测的锯齿样本（设计 §1.4.2），都 < 45s
        for (long lag : new long[] {168L, 8_406L, 15_040L}) {
            PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
                PlatformHealthRule.EVALUATOR_STALLED, snap(lag, 0L, 0L, 0L, 0L),
                PlatformHealthBaseline.none());
            assertThat(verdict.state())
                .as("实测锯齿峰值 15040ms 属正常；阈值取 3×周期(45s) 才不会周期性假告警（lag=%s）", lag)
                .isEqualTo(PlatformHealthState.HEALTHY);
        }
    }

    @Test
    @DisplayName("超过阈值（> 45s）判触发")
    void lagBeyondThresholdMustFire() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.EVALUATOR_STALLED, snap(60_000L, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state()).isEqualTo(PlatformHealthState.FIRING);
        assertThat(verdict.summary()).contains("60000");
    }

    @Test
    @DisplayName("阈值边界：恰好等于 45s 不算触发（> 而非 >=），必须钉住")
    void exactlyAtThresholdMustNotFire() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.EVALUATOR_STALLED,
            snap(PlatformHealthRule.STALLED_LAG_THRESHOLD_MS, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state())
            .as("边界语义必须明确：恰好等于阈值按未超阈值处理（余量刻意留给锯齿）")
            .isEqualTo(PlatformHealthState.HEALTHY);

        // 多 1ms 就应触发，证明边界确实是 > 而不是 >=
        PlatformHealthVerdict justOver = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.EVALUATOR_STALLED,
            snap(PlatformHealthRule.STALLED_LAG_THRESHOLD_MS + 1, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());
        assertThat(justOver.state()).isEqualTo(PlatformHealthState.FIRING);
    }

    @Test
    @DisplayName("取不到 lag ⇒ UNKNOWN（不是健康）")
    void missingLagMustBeUnknown() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.EVALUATOR_STALLED, snap(null, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state())
            .as("取不到指标时报健康会让采集故障被读成一切正常")
            .isEqualTo(PlatformHealthState.UNKNOWN);
        assertThat(verdict.observed()).isNull();
    }

    // ===================== 计数型规则 =====================

    @Test
    @DisplayName("计数较上轮增长 ⇒ 触发；未增长 ⇒ 健康")
    void counterGrowthMustFire() {
        PlatformHealthBaseline base = new PlatformHealthBaseline(0L, 0L, 0L);

        assertThat(PlatformHealthEvaluator.evaluate(PlatformHealthRule.EVALUATOR_ROUND_FAILED,
            snap(0L, 3L, 0L, 0L, 0L), base).state())
            .as("实测恒 0 ⇒ 任何增长都值得告警").isEqualTo(PlatformHealthState.FIRING);

        assertThat(PlatformHealthEvaluator.evaluate(PlatformHealthRule.EVALUATOR_ROUND_FAILED,
            snap(0L, 0L, 0L, 0L, 0L), base).state())
            .isEqualTo(PlatformHealthState.HEALTHY);
    }

    @Test
    @DisplayName("进程重启后计数归零不能掩盖问题：基线缺失且当前值>0 仍要告警")
    void firstRunWithPositiveCounterMustFire() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.NOTIFY_FAILING, snap(0L, 0L, 7L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state())
            .as("首次观测即非零，说明问题在观察开始前就已发生，不能因为『没基线』就放行")
            .isEqualTo(PlatformHealthState.FIRING);
        assertThat(verdict.summary()).contains("首次观测即非零");
    }

    @Test
    @DisplayName("首次运行且当前值为 0 ⇒ UNKNOWN（不知道，不猜健康）")
    void firstRunWithZeroCounterMustBeUnknown() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.NOTIFY_FAILING, snap(0L, 0L, 0L, 0L, 0L),
            PlatformHealthBaseline.none());

        assertThat(verdict.state()).isEqualTo(PlatformHealthState.UNKNOWN);
    }

    @Test
    @DisplayName("取不到计数 ⇒ UNKNOWN，不当作 0")
    void missingCounterMustBeUnknown() {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.NOTIFY_FAILING, snap(0L, 0L, null, 0L, 0L),
            new PlatformHealthBaseline(0L, 0L, 0L));

        assertThat(verdict.state())
            .as("把『取不到』当作 0 会在采集坏掉时静默报平安")
            .isEqualTo(PlatformHealthState.UNKNOWN);
    }

    @Test
    @DisplayName("入站丢弃是两项合计；任一项取不到则整体不可判定（不给出错误归因）")
    void ingestDropRequiresBothCounters() {
        // 两项都在：合计 5，较基线 0 增长 ⇒ 触发
        assertThat(PlatformHealthEvaluator.evaluate(PlatformHealthRule.INGEST_DROPPING,
            snap(0L, 0L, 0L, 3L, 2L), new PlatformHealthBaseline(0L, 0L, 0L)).state())
            .isEqualTo(PlatformHealthState.FIRING);

        // 缺一项：无法判断"合计的增长"是真丢数据还是刚开始采集另一项 ⇒ UNKNOWN
        assertThat(PlatformHealthEvaluator.evaluate(PlatformHealthRule.INGEST_DROPPING,
            snap(0L, 0L, 0L, 3L, null), new PlatformHealthBaseline(0L, 0L, 0L)).state())
            .as("只比一项会给出错误归因：合计增长可能只是另一项开始被采集")
            .isEqualTo(PlatformHealthState.UNKNOWN);
    }

    @Test
    @DisplayName("计数不增长（甚至重启归零）判健康")
    void counterNotGrowingMustBeHealthy() {
        // 重启后 current(0) < previous(5)：不是"增长"，应健康（否则重启后会误报一轮）
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(
            PlatformHealthRule.NOTIFY_FAILING, snap(0L, 0L, 0L, 0L, 0L),
            new PlatformHealthBaseline(0L, 5L, 0L));

        assertThat(verdict.state()).isEqualTo(PlatformHealthState.HEALTHY);
    }

    // ===================== 穷举与健壮性 =====================

    @ParameterizedTest
    @EnumSource(PlatformHealthRule.class)
    @DisplayName("每条规则都必须可判定且返回非空文案（新增规则时用例会红）")
    void everyRuleMustProduceVerdict(PlatformHealthRule rule) {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(rule,
            snap(1_000L, 1L, 1L, 1L, 1L), PlatformHealthBaseline.none());

        assertThat(verdict).isNotNull();
        assertThat(verdict.rule()).isEqualTo(rule);
        assertThat(verdict.summary())
            .as("告警文案不能为空——值班看到空文案只会更困惑")
            .isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(PlatformHealthRule.class)
    @DisplayName("全部指标缺失时：只能返回 UNKNOWN，绝不返回 FIRING/HEALTHY")
    void allMetricsMissingMustBeUnknown(PlatformHealthRule rule) {
        PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(rule,
            snap(null, null, null, null, null), PlatformHealthBaseline.none());

        assertThat(verdict.state())
            .as("指标全缺时任何『健康』或『触发』结论都是编的（rule=%s）", rule.getCode())
            .isEqualTo(PlatformHealthState.UNKNOWN);
    }

    @Test
    @DisplayName("snapshot/baseline 传 null 不炸（防御）")
    void nullInputsMustNotThrow() {
        for (PlatformHealthRule rule : PlatformHealthRule.values()) {
            PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(rule, null, null);
            assertThat(verdict.state()).isEqualTo(PlatformHealthState.UNKNOWN);
        }
    }

    @Test
    @DisplayName("rule 为 null 必须显式报错（静默返回会掩盖调用方的 bug）")
    void nullRuleMustThrow() {
        assertThatThrownBy(() -> PlatformHealthEvaluator.evaluate(null,
            snap(0L, 0L, 0L, 0L, 0L), PlatformHealthBaseline.none()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("无 baseline / 快照无效参数时 from() 退化为 none()")
    void baselineFromMustDegradeSafely() {
        assertThat(PlatformHealthBaseline.from(null).roundFailedCount()).isNull();
        assertThat(PlatformHealthBaseline.from(snap(0L, 1L, 2L, 3L, 4L)).ingestDroppedTotal())
            .isEqualTo(7L);
    }

    @Test
    @DisplayName("从指标映射构建快照：缺失的键留 null 而不是 0")
    void snapshotFromMetersMustKeepMissingAsNull() {
        Map<String, Long> meters = new HashMap<>();
        meters.put(PlatformMetrics.METRIC_EVALUATE_LAG, 1234L);
        // 其余四个不放 ⇒ 应为 null

        PlatformHealthSnapshot snapshot = PlatformHealthSnapshot.fromMeters(meters);

        assertThat(snapshot.lagMs()).isEqualTo(1234L);
        assertThat(snapshot.roundFailedCount())
            .as("没取到 ≠ 0；填 0 会让采集故障被读成健康")
            .isNull();
        assertThat(PlatformHealthSnapshot.fromMeters(null).lagMs()).isNull();
    }

    @Test
    @DisplayName("PlatformHealthVerdict 拒绝空 rule/state/summary")
    void verdictMustRejectHollowInput() {
        assertThatThrownBy(() -> new PlatformHealthVerdict(null, PlatformHealthState.HEALTHY,
            1L, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformHealthVerdict(PlatformHealthRule.NOTIFY_FAILING,
            null, 1L, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformHealthVerdict(PlatformHealthRule.NOTIFY_FAILING,
            PlatformHealthState.HEALTHY, 1L, "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("规则码按码解析（忽略大小写），未知返回 null")
    void ruleCodeParsing() {
        assertThat(PlatformHealthRule.ofCode("platform_notify_failing"))
            .isEqualTo(PlatformHealthRule.NOTIFY_FAILING);
        assertThat(PlatformHealthRule.ofCode("  PLATFORM_EVALUATOR_STALLED "))
            .isEqualTo(PlatformHealthRule.EVALUATOR_STALLED);
        assertThat(PlatformHealthRule.ofCode("nope")).isNull();
        assertThat(PlatformHealthRule.ofCode(null)).isNull();
    }
}
