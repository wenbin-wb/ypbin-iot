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

import cn.ypbin.admin.iot.enums.ThingModelDataType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * **跨模块契约**（task-23 §4.6 重构后）：接入侧（access）交付的是**字符串值**，落列由 iot 侧按
 * 物模型 {@code data_type} 唯一决定——**和字符串长什么样无关**。
 *
 * <p>旧契约（v3）是「解码串形态 → 词法猜列 + {@code forceText} 补丁」；那条链路已彻底删除。
 * 本用例钉死新契约：同一条解码串（如 {@code "123456"}）在 {@code data_type=string} 下必须落
 * {@code value_text}，在 {@code data_type=long} 下必须落 {@code value_double}——列只随类型变、不随值变。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class AccessDecodedValueColumnContractTest {

    /** 供 9 类型矩阵使用的多形态值。 */
    private static final List<String> VALUE_FORMS =
        List.of("123456", "007", "1.5", "true", "2026-10-13", "{\"a\":1}", "[1,2]", "");

    @Test
    @DisplayName("★ 9 类 × 多形态值：声明类型决定列，同一类型多值列恒定")
    void declaredTypeMustDecideColumnRegardlessOfValueForm() {
        assertThat(ValueColumn.from(ThingModelDataType.INT)).isEqualTo(ValueColumn.NUMERIC);
        assertThat(ValueColumn.from(ThingModelDataType.LONG)).isEqualTo(ValueColumn.NUMERIC);
        assertThat(ValueColumn.from(ThingModelDataType.DECIMAL)).isEqualTo(ValueColumn.NUMERIC);
        assertThat(ValueColumn.from(ThingModelDataType.BOOL)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.STRING)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.ENUM)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.DATE_TIME)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.JSON_OBJECT)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.ARRAY)).isEqualTo(ValueColumn.TEXT);
    }

    @Test
    @DisplayName("★ 文本类 6 型：任意值形态都落 value_text（含纯数字 123456 / 007）")
    void allTextTypesMustAlwaysLandInTextColumn() {
        List<ThingModelDataType> textTypes = List.of(ThingModelDataType.BOOL, ThingModelDataType.STRING,
            ThingModelDataType.ENUM, ThingModelDataType.DATE_TIME, ThingModelDataType.JSON_OBJECT,
            ThingModelDataType.ARRAY);
        for (ThingModelDataType type : textTypes) {
            for (String form : VALUE_FORMS) {
                ReadingValueMapper.MappedValue mapped = ReadingValueMapper.map(form, ValueColumn.from(type));
                assertThat(mapped.column())
                    .as("类型 %s 的 %s 必须落 value_text", type, form)
                    .isEqualTo(ReadingValueMapper.COLUMN_TEXT);
                assertThat(mapped.numericValue()).isNull();
            }
        }
    }

    @Test
    @DisplayName("★ 数值类 3 型：可解析形态落 value_double；不可解析形态显式拒绝（不落文本）")
    void numericTypesMustMaterializeNumericFormsAndRejectOthers() {
        List<ThingModelDataType> numericTypes =
            List.of(ThingModelDataType.INT, ThingModelDataType.LONG, ThingModelDataType.DECIMAL);
        for (ThingModelDataType type : numericTypes) {
            for (String form : List.of("123456", "007", "1.5", "-7", "1e3")) {
                ReadingValueMapper.MappedValue mapped = ReadingValueMapper.map(form, ValueColumn.from(type));
                assertThat(mapped.column())
                    .as("类型 %s 的可解析值 %s 必须落 value_double", type, form)
                    .isEqualTo(ReadingValueMapper.COLUMN_DOUBLE);
                assertThat(mapped.numericValue()).isNotNull();
            }
            for (String bad : List.of("true", "abc", "")) {
                assertThatThrownBy(() -> ReadingValueMapper.map(bad, ValueColumn.from(type)))
                    .as("类型 %s 的不可解析值 %s 必须显式失败（不落文本列）", type, bad)
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @DisplayName("★ 同一解码串在 string 与 long 下分属不同列（类型权威，值形态无权）")
    void sameDecodedStringMustFollowDeclaredType() {
        ReadingValueMapper.MappedValue asString = ReadingValueMapper.map("123456", ValueColumn.TEXT);
        assertThat(asString.column()).isEqualTo(ReadingValueMapper.COLUMN_TEXT);
        assertThat(asString.numericValue()).isNull();

        ReadingValueMapper.MappedValue asLong = ReadingValueMapper.map("123456", ValueColumn.NUMERIC);
        assertThat(asLong.column()).isEqualTo(ReadingValueMapper.COLUMN_DOUBLE);
        assertThat(asLong.numericValue()).isEqualTo(123456d);
    }

    @Test
    @DisplayName("★ 2^53 边界：仍写 value_double，precision_lost 可判定（不换列、不丢弃）")
    void twoPower53MustStayNumericButFlagPrecisionLoss() {
        assertThat(NumericValues.parse("9007199254740992")).isNotNull();
        assertThat(NumericValues.isPrecisionLost("9007199254740992")).isFalse();

        assertThat(NumericValues.parse("9007199254740993"))
            .as("超过 2^53 的整数仍可物化为 double（已丢精度）").isNotNull();
        assertThat(NumericValues.isPrecisionLost("9007199254740993")).isTrue();
    }
}
