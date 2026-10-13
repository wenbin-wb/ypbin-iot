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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 列物化（task-23 §4.6.3）：{@code map(raw, ValueColumn)} **只按给定列** materialize，不再按值形态猜列。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class ReadingValueMapperTest {

    @Test
    @DisplayName("★ 文本列：原样物化（不 trim、不按值形态数值化）")
    void textColumnMustMaterializeRawValue() {
        ReadingValueMapper.MappedValue numericLooking = ReadingValueMapper.map("123456", ValueColumn.TEXT);
        assertThat(numericLooking.column()).isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(numericLooking.numericValue()).as("文本行的 numericValue 必须为 null").isNull();
        assertThat(numericLooking.text()).isEqualTo("123456");

        ReadingValueMapper.MappedValue spaced = ReadingValueMapper.map(" 123 ", ValueColumn.TEXT);
        assertThat(spaced.text()).as("原样落文本：不 trim（空白也是数据）").isEqualTo(" 123 ");

        ReadingValueMapper.MappedValue empty = ReadingValueMapper.map("", ValueColumn.TEXT);
        assertThat(empty.text()).isEmpty();
        assertThat(empty.numericValue()).isNull();
    }

    @Test
    @DisplayName("★ 数值列：严格十进制解析（前导零/科学计数法都按声明列物化，不再看值形态）")
    void numericColumnMustMaterializeDeclaredNumbers() {
        assertThat(ReadingValueMapper.map("23.5", ValueColumn.NUMERIC).numericValue()).isEqualTo(23.5d);
        assertThat(ReadingValueMapper.map("-7", ValueColumn.NUMERIC).numericValue()).isEqualTo(-7d);
        assertThat(ReadingValueMapper.map("1.5e3", ValueColumn.NUMERIC).numericValue()).isEqualTo(1500d);
        // 声明为数值 ⇒ "007" 落数值列（列由类型决定，前导零启发式已删除）
        assertThat(ReadingValueMapper.map("007", ValueColumn.NUMERIC).numericValue()).isEqualTo(7d);
        // 空白包裹的数值去掉首尾空白后物化
        assertThat(ReadingValueMapper.map(" 42 ", ValueColumn.NUMERIC).numericValue()).isEqualTo(42d);
    }

    @Test
    @DisplayName("★ 数值列不可物化：抛异常（调用方必须在入站侧先按 not_numeric 丢弃，不留文本兜底）")
    void unparsableNumericMustThrow() {
        for (String bad : new String[] {"abc", "", "   ", "NaN", "Infinity", "1d", "1.2.3", "0x1F", "12,5"}) {
            assertThatThrownBy(() -> ReadingValueMapper.map(bad, ValueColumn.NUMERIC))
                .as("%s 不可物化为数值列 ⇒ 显式失败，不落文本列", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }


}
