/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数值解析与 2^53 精度判据（task-23 §4.6.5）单测。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class NumericValuesTest {

    @Test
    @DisplayName("★ parse：合法十进制/科学计数法可解析；非法形态与非有限一律返回 null")
    void parseMustAcceptDecimalFormsAndRejectOthers() {
        assertThat(NumericValues.parse("23.5")).isEqualTo(23.5d);
        assertThat(NumericValues.parse("007")).isEqualTo(7d);
        assertThat(NumericValues.parse(" 42 ")).isEqualTo(42d);
        assertThat(NumericValues.parse("1.5e3")).isEqualTo(1500d);

        for (String bad : new String[] {null, "", "   ", "abc", "NaN", "Infinity", "1d", "1.2.3",
            "0x1F", "12,5", "1e400"}) {
            assertThat(NumericValues.parse(bad)).as("不可物化：%s", bad).isNull();
        }
    }

    @Test
    @DisplayName("★ 2^53 边界：|v| ≤ 2^53 不丢精度；> 2^53 用声明值判据（doubles 会提前四舍五入）")
    void precisionBoundaryMustFollowTwoPower53OnDeclaredValue() {
        assertThat(NumericValues.MAX_EXACT_INTEGER_IN_DOUBLE).isEqualTo(9_007_199_254_740_992d);
        assertThat(NumericValues.isPrecisionLost("9007199254740992")).isFalse();
        assertThat(NumericValues.isPrecisionLost("-9007199254740992")).isFalse();
        // 2^53+1 转 double 会四舍五入成 2^53 ⇒ 必须先用 BigDecimal 判据，否则漏报
        assertThat(NumericValues.isPrecisionLost("9007199254740993")).isTrue();
        assertThat(NumericValues.isPrecisionLost("-9007199254740993")).isTrue();
        assertThat(NumericValues.isPrecisionLost("1e20")).isTrue();
        assertThat(NumericValues.isPrecisionLost("0")).isFalse();
        assertThat(NumericValues.isPrecisionLost("abc")).isFalse();
        assertThat(NumericValues.isPrecisionLost(null)).isFalse();
    }
}
