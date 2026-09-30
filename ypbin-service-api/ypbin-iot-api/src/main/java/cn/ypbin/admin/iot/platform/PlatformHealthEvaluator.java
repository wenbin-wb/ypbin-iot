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

/**
 * 平台健康判定器（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §4）——**纯函数，零 IO**。
 *
 * <p><b>为什么必须纯</b>（照设计 §4/§8 判据 5 落地）：</p>
 * <ol>
 *   <li>可**穷举单测**：每条规则至少一正一反 + 阈值边界 + 指标缺失；</li>
 *   <li>**不依赖被监控的链路**——它只读入参，不查库、不调远程。这一点是刻意的：
 *       平台自告警若依赖 DB，那么"DB 挂了"时告警本身也发不出来（"监控者先坏"，设计 §6 R2）。</li>
 * </ol>
 *
 * <p><b>上一轮的值从哪来</b>：本类**无状态**，判定"计数是否增长"需要上一轮的基线，
 * 由调用方（服务层）持有并传入 {@link PlatformHealthBaseline}。这样本类仍是纯函数，
 * 基线如何跨轮维护（内存 / DB）是**调用方的选择**，不污染判定逻辑。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class PlatformHealthEvaluator {

    private PlatformHealthEvaluator() {
    }

    /**
     * 判定单条规则。
     *
     * @param rule     规则
     * @param snapshot 本轮指标快照
     * @param baseline 上一轮基线（**首次运行时传 {@link PlatformHealthBaseline#none()}**）
     * @return 判定结果（**绝不返回 `null`**）
     */
    public static PlatformHealthVerdict evaluate(PlatformHealthRule rule,
                                                 PlatformHealthSnapshot snapshot,
                                                 PlatformHealthBaseline baseline) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }
        PlatformHealthSnapshot snap = snapshot == null
            ? new PlatformHealthSnapshot(null, null, null, null, null) : snapshot;
        PlatformHealthBaseline base = baseline == null ? PlatformHealthBaseline.none() : baseline;
        return switch (rule) {
            case EVALUATOR_STALLED -> evaluateStalled(snap);
            case EVALUATOR_ROUND_FAILED -> evaluateCounterGrowth(rule, snap.roundFailedCount(),
                base.roundFailedCount(), "告警评估轮次");
            case NOTIFY_FAILING -> evaluateCounterGrowth(rule, snap.notifyFailedCount(),
                base.notifyFailedCount(), "通知投递");
            case INGEST_DROPPING -> evaluateCounterGrowth(rule, snap.ingestDroppedTotal(),
                base.ingestDroppedTotal(), "入站丢弃");
        };
    }

    /**
     * 评估器停摆判定。
     *
     * <p>🔴 <b>`lag = -1` 必须单独判</b>：产出侧用它表示"**从未成功过**"
     * （`AlertMetrics#currentLagMs` 刻意不用 0，因为 0 与"刚跑过"读数相同）。
     * 若按数值大小比较，`-1 < 45000` 会被判成**健康**——而真相是"评估器从来没跑起来"，
     * 这是最该告警的情况。⇒ 单独一支，直接 CRITICAL/触发。</p>
     *
     * @param snap 快照
     * @return 判定
     */
    private static PlatformHealthVerdict evaluateStalled(PlatformHealthSnapshot snap) {
        PlatformHealthRule rule = PlatformHealthRule.EVALUATOR_STALLED;
        Long lag = snap.lagMs();
        if (lag == null) {
            return new PlatformHealthVerdict(rule, PlatformHealthState.UNKNOWN, null,
                "取不到评估滞后指标（" + PlatformMetrics.METRIC_EVALUATE_LAG
                    + "）⇒ 无法判断评估器是否在跑");
        }
        if (lag < 0) {
            // -1 = 从未成功过（产出侧约定），不能按数值比大小
            return new PlatformHealthVerdict(rule, PlatformHealthState.FIRING, lag,
                "告警评估器**从未成功执行过**（滞后 = " + lag
                    + "，产出侧约定 -1 表示从未成功）");
        }
        if (lag > PlatformHealthRule.STALLED_LAG_THRESHOLD_MS) {
            return new PlatformHealthVerdict(rule, PlatformHealthState.FIRING, lag,
                "告警评估器已 " + lag + " ms 未成功（阈值 "
                    + PlatformHealthRule.STALLED_LAG_THRESHOLD_MS + " ms = 3 × 评估周期）");
        }
        return new PlatformHealthVerdict(rule, PlatformHealthState.HEALTHY, lag,
            "告警评估器正常（滞后 " + lag + " ms）");
    }

    /**
     * 计数型规则判定（**增长即告警**）。
     *
     * <p>阈值来源：这些计数在生产**实测恒 0**（设计 §1.4.2）⇒ 任何增长都值得看。
     * 用"增长"而不是"大于 0"是因为进程重启后计数归零，而问题可能仍在。</p>
     *
     * <p>⚠️ 首次运行（基线缺失）与指标缺失都返回 `UNKNOWN`：**不知道就说不不知道**，
     * 不猜"应该是好的"。</p>
     *
     * @param rule      规则
     * @param current   本轮值（`null` = 未取到）
     * @param previous  上轮基线（`null` = 首次运行）
     * @param label     人话标签（用于文案）
     * @return 判定
     */
    private static PlatformHealthVerdict evaluateCounterGrowth(PlatformHealthRule rule,
                                                               Long current, Long previous,
                                                               String label) {
        if (current == null) {
            return new PlatformHealthVerdict(rule, PlatformHealthState.UNKNOWN, null,
                "取不到" + label + "指标 ⇒ 无法判断");
        }
        if (previous == null) {
            // 首次运行没有基线：此时"当前值 > 0"说明问题**在本轮之前就存在**，仍应告警
            if (current > 0) {
                return new PlatformHealthVerdict(rule, PlatformHealthState.FIRING, current,
                    label + "累计 " + current + "（首次观测即非零 ⇒ 问题在观察前已发生）");
            }
            return new PlatformHealthVerdict(rule, PlatformHealthState.UNKNOWN, current,
                "首次观测" + label + "为 0，尚无基线 ⇒ 暂不判定（下一轮起按增量判断）");
        }
        long delta = current - previous;
        if (delta > 0) {
            return new PlatformHealthVerdict(rule, PlatformHealthState.FIRING, current,
                label + "较上轮增加 " + delta + "（累计 " + current + "）");
        }
        return new PlatformHealthVerdict(rule, PlatformHealthState.HEALTHY, current,
            label + "未增长（累计 " + current + "）");
    }
}
