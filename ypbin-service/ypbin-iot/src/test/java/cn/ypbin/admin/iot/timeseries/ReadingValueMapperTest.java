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
 * 读数 → 时序列的映射规则（§5.2.1）。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class ReadingValueMapperTest {

    @Test
    @DisplayName("★ 整数在 2^53 内走数值列；**超过 2^53 走文本列**（double 只有 53 位有效精度）")
    void mustGuardLongPrecision() {
        assertThat(ReadingValueMapper.map("23").numeric()).isTrue();
        assertThat(ReadingValueMapper.map("-7").numeric()).isTrue();
        long boundary = 1L << 53;
        assertThat(ReadingValueMapper.map(String.valueOf(boundary)).numeric())
            .as("2^53 本身可精确表示 ⇒ 仍走数值列").isTrue();
        assertThat(ReadingValueMapper.map(String.valueOf(boundary + 1)).column())
            .as("超过 2^53 必须走文本列，不能静默丢精度").isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("123456789012345678901234567890").column())
            .as("超出 long 范围的纯数字也走文本列（不截断）").isEqualTo(ReadingValueMapper.COLUMN_TEXT);
    }

    @Test
    @DisplayName("★ 布尔落文本并归一为小写；小数/科学计数法走数值列；其它文本走文本列")
    void mustMapByLexicalForm() {
        assertThat(ReadingValueMapper.map("TRUE").column()).isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("True").text()).isEqualTo("true");

        ReadingValueMapper.MappedValue decimal = ReadingValueMapper.map("23.5");
        assertThat(decimal.numeric()).isTrue();
        assertThat(decimal.numericValue()).isEqualTo(23.5d);
        assertThat(ReadingValueMapper.map("1.5e3").numericValue()).isEqualTo(1500d);

        for (String text : new String[] {"GOOD", "{\"a\":1}", "0x1F", "1d", "NaN", "Infinity", "12,5"}) {
            assertThat(ReadingValueMapper.map(text).column())
                .as("%s 不是规范数值形态 ⇒ 文本列（不猜）", text).isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        }
    }

    @Test
    @DisplayName("★ 边界：Long.MIN_VALUE 不得走数值列（Math.abs 溢出会误判）；全角数字/前导零走文本列")
    void mustHandleBoundaryForms() {
        assertThat(ReadingValueMapper.map(String.valueOf(Long.MIN_VALUE)).column())
            .as("Long.MIN_VALUE 的 Math.abs 仍为负 ⇒ 必须靠区间判定，否则会静默丢精度")
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map(String.valueOf(Long.MAX_VALUE)).column())
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("１２３").column())
            .as("全角数字不是协议侧形态（且与数值解析口径不一致）⇒ 文本列")
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("007").column())
            .as("前导零整数通常是编码/序列号 ⇒ 文本列，不丢形态").isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("0").numeric()).as("单个 0 是正常数值").isTrue();
    }

    @Test
    @DisplayName("★ 文本行的 numericValue 必须是 null（防止下一段 JDBC 写入器把 0.0 写进数值列）")
    void textRowsMustHaveNullNumericValue() {
        assertThat(ReadingValueMapper.map("GOOD").numericValue()).isNull();
        assertThat(ReadingValueMapper.map("007").numericValue()).isNull();
        assertThat(ReadingValueMapper.map("23").numericValue()).isEqualTo(23d);
    }

    @Test
    @DisplayName("空串落文本列（不猜 0）；文本列保留原值")
    void mustNotGuessForBlank() {
        assertThat(ReadingValueMapper.map("").column()).isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("").text()).isEmpty();
        assertThat(ReadingValueMapper.map(" meter-01 ").text()).isEqualTo("meter-01");
        assertThat(ReadingValueMapper.map("   ").text()).as("空白串返回 trim 后的值（与其它文本分支一致）").isEmpty();
    }
}
