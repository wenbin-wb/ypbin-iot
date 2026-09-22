/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.availability.AvailabilityCalculator.Summary;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可用率计算纯逻辑单测（M-2 口径）。
 *
 * <p>自 A11 起，断档秒数由 SQL 精确聚合给出（{@code OutageEventMapper.summarizeInWindow} 负责按窗口裁剪），
 * 因此这里测的是「拿到精确输入后」的三件防御：窗口为 0 不误判、逐项下限 0（脏数据不得抬高可用率）、
 * 合计封顶到窗口（可用率不得为负），以及双条件达标。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
class AvailabilityCalculatorTest {

    private static final long WINDOW = 10 * 3600L;
    private static final long INTERVAL_MS = 5_000L;

    @Test
    @DisplayName("无断档：可用率 100% 且达标")
    void noOutageMeansFullAvailability() {
        Summary summary = AvailabilityCalculator.summarize(WINDOW, 0L, 0L, 0, INTERVAL_MS, false);

        assertThat(summary.windowSeconds()).isEqualTo(WINDOW);
        assertThat(summary.outageSeconds()).isZero();
        assertThat(summary.longestOutageSeconds()).isZero();
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(summary.meetsTarget()).isTrue();
        assertThat(summary.truncated()).isFalse();
    }

    @Test
    @DisplayName("窗口内 1 小时断档：可用率 = 1 - 3600/36000 = 0.9，不达标")
    void outageMustReduceAvailability() {
        Summary summary = AvailabilityCalculator.summarize(WINDOW, 3_600L, 3_600L, 1, INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).isEqualTo(3_600L);
        assertThat(summary.longestOutageSeconds()).isEqualTo(3_600L);
        assertThat(summary.outageCount()).isEqualTo(1);
        assertThat(summary.availability()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(summary.meetsTarget()).isFalse();
    }

    @Test
    @DisplayName("★ 脏数据：断档合计超过窗口时封顶到窗口（可用率不得为负）")
    void outageBeyondWindowMustBeClamped() {
        Summary summary = AvailabilityCalculator.summarize(WINDOW, WINDOW * 3, WINDOW * 2, 2, INTERVAL_MS,
            false);

        assertThat(summary.outageSeconds()).as("合计封顶").isEqualTo(WINDOW);
        assertThat(summary.longestOutageSeconds()).as("最长也封顶到合计").isEqualTo(WINDOW);
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.availability().signum()).isNotNegative();
    }

    @Test
    @DisplayName("★ 脏数据：负数断档不得把可用率抬高（逐项下限 0）")
    void negativeOutageMustNotInflateAvailability() {
        Summary summary = AvailabilityCalculator.summarize(WINDOW, -5_000L, -5_000L, 3, INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).isZero();
        assertThat(summary.longestOutageSeconds()).isZero();
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("★ 达标是双条件：可用率够高但单次断档超上限 ⇒ 不达标")
    void meetsTargetMustRequireBothConditions() {
        // 窗口 72h + 断档 15 分钟 ⇒ 可用率 99.65%（≥99.5%），但最长断档 900s > 600s（10×5s=50s ⇒ 取下限）
        long window = 72 * 3600L;
        Summary summary = AvailabilityCalculator.summarize(window, 900L, 900L, 1, INTERVAL_MS, false);

        assertThat(summary.availability()).isEqualByComparingTo(new BigDecimal("0.996528"));
        assertThat(summary.availability()).isGreaterThanOrEqualTo(AvailabilityRules.TARGET_AVAILABILITY);
        assertThat(summary.longestOutageSeconds()).isEqualTo(900L);
        assertThat(summary.maxAllowedOutageSeconds()).isEqualTo(600L);
        assertThat(summary.meetsTarget()).as("可用率达标但最长断档 900s > 600s ⇒ 不达标").isFalse();
    }

    @Test
    @DisplayName("窗口时长为 0（区间给反/相等）：不判不达标、可用率记 100%，不把参数错误装成重大断档")
    void emptyWindowMustNotBeReportedAsOutage() {
        Summary summary = AvailabilityCalculator.summarize(0L, 600L, 600L, 1, INTERVAL_MS, false);

        assertThat(summary.windowSeconds()).isZero();
        assertThat(summary.outageSeconds()).isZero();
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(summary.meetsTarget()).isTrue();
    }

    @Test
    @DisplayName("★ 明细截断不影响汇总：truncated 只透传，断档秒数/次数照旧（这是 A11 的核心）")
    void truncatedDetailsMustNotChangeSummary() {
        Summary summary = AvailabilityCalculator.summarize(WINDOW, 6_030L, 30L, 201, INTERVAL_MS, true);

        assertThat(summary.truncated()).isTrue();
        assertThat(summary.outageCount()).as("次数是精确值，不因为明细只返回 200 条而变成 200")
            .isEqualTo(201);
        assertThat(summary.outageSeconds()).as("汇总来自精确聚合").isEqualTo(6_030L);
        assertThat(summary.availability()).isEqualByComparingTo(new BigDecimal("0.832500"));
    }
}
