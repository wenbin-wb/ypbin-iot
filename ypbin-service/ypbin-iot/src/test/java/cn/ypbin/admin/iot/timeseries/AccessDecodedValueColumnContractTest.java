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
 * **跨模块契约**：接入侧（access）解码输出的字符串形态 → 时序表的列选择。
 *
 * <p>为什么需要这个用例（而不是只靠各自的单测）：解码在 access 模块、列选择在 iot 模块，
 * 两个模块**没有编译期依赖**，靠的是「字符串形态」这一个隐式契约。这里把契约钉死：
 * access 的 {@code TextFrameValueDecoder} 对数值点输出干净数值串（如 {@code "23.5"}），
 * 只有如此 {@link ReadingValueMapper} 才会把它写进 {@code value_double}（曲线才画得出来）；
 * 文本点（{@code serialNo} 这类，含前导零）必须仍然走 {@code value_text}。</p>
 *
 * <p>形态来源：access 单测 {@code TextFrameValueDecoderTest} / {@code AccessReadingMappingTest}
 * （那里断言了「解码后 {@code String.valueOf(value)} 是纯数值串」）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class AccessDecodedValueColumnContractTest {

    @Test
    @DisplayName("★ 数值点解码形态 ⇒ value_double（曲线可画）")
    void numericDecodedFormsMustLandInNumericColumn() {
        // temperature（data_type=decimal）解出的形态
        ReadingValueMapper.MappedValue temperature = ReadingValueMapper.map("23.5");
        assertThat(temperature.column()).isEqualTo(ReadingValueMapper.COLUMN_DOUBLE);
        assertThat(temperature.numericValue()).isEqualTo(23.5d);

        // humidity（data_type=int）解出的形态
        ReadingValueMapper.MappedValue humidity = ReadingValueMapper.map("45");
        assertThat(humidity.column()).isEqualTo(ReadingValueMapper.COLUMN_DOUBLE);
        assertThat(humidity.numericValue()).isEqualTo(45.0d);

        // 缩放/偏移生效后的形态（applyScale 产出的 BigDecimal 字符串化形态）
        assertThat(ReadingValueMapper.map("23.500000").column())
            .isEqualTo(ReadingValueMapper.COLUMN_DOUBLE);
    }

    @Test
    @DisplayName("★ 文本/布尔点解码形态 ⇒ value_text（serialNo 不许被数值化）")
    void textDecodedFormsMustStayInTextColumn() {
        assertThat(ReadingValueMapper.map("SN-0001").column())
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("007").column())
            .as("前导零编号：access 侧按 string 类型原样输出，列选择侧同样按文本落库")
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(ReadingValueMapper.map("true").column())
            .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
    }
}
