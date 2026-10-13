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

import cn.ypbin.admin.iot.enums.ThingModelDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 落列 SSOT 自检（task-23 §4.6.2）：物模型 9 类 → 目标列的**唯一映射表**。
 *
 * <p>穷举 switch 的编译期守卫无法在运行时直接断言，这里用「遍历 {@link ThingModelDataType#values()}
 * 逐个断言映射」补一道运行期守卫：新增第 10 类时若忘了更新 {@code ValueColumn.from}，本用例会转红。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ValueColumnTest {

    @Test
    @DisplayName("★ 9 类物模型类型 → 目标列：int/long/decimal 数值；其余 6 类文本")
    void dataTypeMatrixMustMatchSpec() {
        assertThat(ValueColumn.from(ThingModelDataType.INT)).isEqualTo(ValueColumn.NUMERIC);
        assertThat(ValueColumn.from(ThingModelDataType.LONG)).isEqualTo(ValueColumn.NUMERIC);
        assertThat(ValueColumn.from(ThingModelDataType.DECIMAL)).isEqualTo(ValueColumn.NUMERIC);

        assertThat(ValueColumn.from(ThingModelDataType.BOOL)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.STRING)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.ENUM)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.DATE_TIME)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.JSON_OBJECT)).isEqualTo(ValueColumn.TEXT);
        assertThat(ValueColumn.from(ThingModelDataType.ARRAY)).isEqualTo(ValueColumn.TEXT);

        // 遍历全部枚举值：新增类型却漏改 switch 时，这里会因 from 抛异常/映射不符而转红
        for (ThingModelDataType type : ThingModelDataType.values()) {
            assertThat(ValueColumn.from(type)).as("类型 %s 必须有显式列映射", type).isNotNull();
        }
    }

    @Test
    @DisplayName("列名与 §4.6.2 一致，且不写第二份映射表")
    void columnNamesMustMatchPhysicalColumns() {
        assertThat(ValueColumn.NUMERIC.columnName()).isEqualTo("value_double");
        assertThat(ValueColumn.TEXT.columnName()).isEqualTo("value_text");
        assertThat(ValueColumn.values()).hasSize(2);
    }
}
