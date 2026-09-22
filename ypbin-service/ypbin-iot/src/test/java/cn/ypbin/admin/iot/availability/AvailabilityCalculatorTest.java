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

import cn.ypbin.admin.iot.availability.AvailabilityCalculator.OutageWindow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可用率计算纯逻辑单测（M-2 口径）：窗口裁剪、进行中断档、双条件达标、以及脏数据的防御边界。
 *
 * @author wenbin
 * @since 2026-09-22
 */
class AvailabilityCalculatorTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 22, 0, 0, 0);
    private static final LocalDateTime TO = FROM.plusHours(10);
    private static final LocalDateTime NOW = TO;
    private static final long INTERVAL_MS = 5_000L;

    @Test
    @DisplayName("无断档：可用率 100% 且达标")
    void noOutageMeansFullAvailability() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(
            FROM, TO, NOW, List.of(), INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).isZero();
        assertThat(summary.longestOutageSeconds()).isZero();
        assertThat(summary.outageCount()).isZero();
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(summary.meetsTarget()).isTrue();
        assertThat(summary.windowSeconds()).isEqualTo(10 * 3600L);
    }

    @Test
    @DisplayName("窗口内 1 小时断档：可用率 = 1 - 3600/36000 = 0.9，不达标")
    void outageInsideWindowMustReduceAvailability() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, TO, NOW,
            List.of(new OutageWindow(FROM.plusHours(2), FROM.plusHours(3))), INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).isEqualTo(3_600L);
        assertThat(summary.longestOutageSeconds()).isEqualTo(3_600L);
        assertThat(summary.outageCount()).isEqualTo(1);
        assertThat(summary.availability()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(summary.meetsTarget()).isFalse();
    }

    @Test
    @DisplayName("★ 跨窗口的断档只算与窗口的交集（窗口外的部分不计入）")
    void outageMustBeClampedToWindow() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, TO, NOW,
            List.of(new OutageWindow(FROM.minusHours(5), FROM.plusMinutes(30)),
                new OutageWindow(TO.minusMinutes(15), TO.plusHours(4))), INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).as("30 分钟 + 15 分钟").isEqualTo(45 * 60L);
        assertThat(summary.longestOutageSeconds()).isEqualTo(30 * 60L);
        assertThat(summary.outageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 进行中的断档（end=null）按 now 结算；now 超过窗口末时截到窗口末")
    void ongoingOutageMustSettleAtNow() {
        AvailabilityCalculator.Summary inside = AvailabilityCalculator.summarize(FROM, TO, NOW,
            List.of(new OutageWindow(TO.minusHours(1), null)), INTERVAL_MS, false);
        assertThat(inside.outageSeconds()).isEqualTo(3_600L);

        AvailabilityCalculator.Summary beyond = AvailabilityCalculator.summarize(FROM, TO,
            NOW.plusHours(5), List.of(new OutageWindow(FROM, null)), INTERVAL_MS, false);
        assertThat(beyond.outageSeconds()).as("now 超出窗口 ⇒ 最多算满窗口").isEqualTo(10 * 3600L);
        assertThat(beyond.availability()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(beyond.meetsTarget()).isFalse();
    }

    @Test
    @DisplayName("★ 达标是双条件：可用率够高但单次断档超上限 ⇒ 不达标")
    void meetsTargetMustRequireBothConditions() {
        // 要同时满足「可用率 ≥ 99.5%」与「最长断档 > 600s」，窗口必须足够长：
        // 窗口 72h + 断档 15 分钟 ⇒ 可用率 99.65%（≥99.5%），而最长断档 900s > 600s（10×5s=50s ⇒ 取下限）
        LocalDateTime longTo = FROM.plusHours(72);
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, longTo, longTo,
            List.of(new OutageWindow(FROM.plusHours(1), FROM.plusHours(1).plusMinutes(15))),
            INTERVAL_MS, false);

        assertThat(summary.windowSeconds()).isEqualTo(72 * 3600L);
        assertThat(summary.availability()).isEqualByComparingTo(new BigDecimal("0.996528"));
        assertThat(summary.availability()).isGreaterThanOrEqualTo(AvailabilityRules.TARGET_AVAILABILITY);
        assertThat(summary.longestOutageSeconds()).isEqualTo(900L);
        assertThat(summary.maxAllowedOutageSeconds()).isEqualTo(600L);
        assertThat(summary.meetsTarget()).as("可用率达标但最长断档 900s > 600s ⇒ 不达标").isFalse();
    }

    @Test
    @DisplayName("窗口时长为 0（区间给反/相等）：不判不达标、可用率记 100%，不把参数错误装成重大断档")
    void emptyWindowMustNotBeReportedAsOutage() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, FROM, NOW,
            List.of(new OutageWindow(FROM.minusDays(1), FROM.plusDays(1))), INTERVAL_MS, false);

        assertThat(summary.windowSeconds()).isZero();
        assertThat(summary.outageSeconds()).isZero();
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(summary.meetsTarget()).isTrue();
    }

    @Test
    @DisplayName("★ 脏数据防御：断档明细重叠导致合计超过窗口时按窗口封顶（可用率不得为负）")
    void overlappingOutagesMustNotProduceNegativeAvailability() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, TO, NOW,
            List.of(new OutageWindow(FROM, TO), new OutageWindow(FROM, TO)), INTERVAL_MS, false);

        assertThat(summary.outageSeconds()).as("合计被窗口封顶").isEqualTo(10 * 3600L);
        assertThat(summary.availability()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.availability().signum()).isNotNegative();
        assertThat(summary.outageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("明细被截断时原样透传 truncated（可用率会偏低断档 ⇒ 客户端必须能看出来）")
    void truncatedFlagMustBePropagated() {
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(FROM, TO, NOW,
            List.of(new OutageWindow(FROM.plusHours(1), FROM.plusHours(2))), INTERVAL_MS, true);

        assertThat(summary.truncated()).isTrue();
    }
}
