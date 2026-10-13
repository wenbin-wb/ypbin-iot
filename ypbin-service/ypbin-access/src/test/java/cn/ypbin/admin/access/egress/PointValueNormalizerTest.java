/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 宿主物模型类型规范化的纯逻辑单测：**与切换前 {@code TextFrameValueDecoder#canonicalize} 逐字等价**。
 *
 * <p>为什么这些断言要写「切换前是什么、切换后还是什么」：帧解析搬到 starter 0.3.0 的
 * {@code key-value} 后，类型规范化被搬迁到本类；若搬迁时改了取值/失败口径，会静默改变落库形态与
 * 缩放结果。本测试把 9 类物模型类型的成功形态与 3 类失败原因全部钉住。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class PointValueNormalizerTest {

    @Test
    @DisplayName("int/long：纯十进制 → Long（前导零按 Long.valueOf 口径去掉）；非数值/越界 → NOT_NUMERIC")
    void integersMustNormalizeStrictly() {
        assertThat(PointValueNormalizer.normalize("45", "int").value()).isEqualTo(45L);
        assertThat(PointValueNormalizer.normalize("007", "long").value()).isEqualTo(7L);
        assertThat(PointValueNormalizer.normalize("-12", "int").value()).isEqualTo(-12L);

        assertThat(PointValueNormalizer.normalize("1.5", "int").failure())
            .isEqualTo(NormalizeFailure.NOT_NUMERIC);
        assertThat(PointValueNormalizer.normalize("99999999999999999999", "long").failure())
            .as("超 long 范围不截断（静默截断比丢弃更糟）")
            .isEqualTo(NormalizeFailure.NOT_NUMERIC);
        assertThat(PointValueNormalizer.normalize("0x10", "int").failure())
            .isEqualTo(NormalizeFailure.NOT_NUMERIC);
    }

    @Test
    @DisplayName("decimal：形态校验后交给 BigDecimal（不经过 double）；非法形态 → NOT_NUMERIC")
    void decimalsMustUseBigDecimal() {
        assertThat(PointValueNormalizer.normalize("23.5", "decimal").value())
            .isEqualTo(new BigDecimal("23.5"));
        assertThat((BigDecimal) PointValueNormalizer.normalize("1e3", "decimal").value())
            .isEqualByComparingTo(new BigDecimal("1e3"));

        assertThat(PointValueNormalizer.normalize("1.2.3", "decimal").failure())
            .isEqualTo(NormalizeFailure.NOT_NUMERIC);
        assertThat(PointValueNormalizer.normalize("NaN", "decimal").failure())
            .as("NaN/Infinity 不是协议侧数据形态").isEqualTo(NormalizeFailure.NOT_NUMERIC);
    }

    @Test
    @DisplayName("bool：true/false/1/0（忽略大小写）→ true/false 文本；其它 → NOT_BOOLEAN")
    void booleansMustBeCanonicalized() {
        assertThat(PointValueNormalizer.normalize("true", "bool").value()).isEqualTo("true");
        assertThat(PointValueNormalizer.normalize("TRUE", "bool").value()).isEqualTo("true");
        assertThat(PointValueNormalizer.normalize("1", "bool").value()).isEqualTo("true");
        assertThat(PointValueNormalizer.normalize("false", "bool").value()).isEqualTo("false");
        assertThat(PointValueNormalizer.normalize("0", "bool").value()).isEqualTo("false");

        assertThat(PointValueNormalizer.normalize("maybe", "bool").failure())
            .isEqualTo(NormalizeFailure.NOT_BOOLEAN);
        assertThat(PointValueNormalizer.normalize("2", "bool").failure())
            .isEqualTo(NormalizeFailure.NOT_BOOLEAN);
    }

    @Test
    @DisplayName("string/enum/date_time/json_object/array：文本原样，绝不被顺手转成数字")
    void textTypesMustStayText() {
        for (String dataType : new String[] {"string", "enum", "date_time", "json_object", "array"}) {
            assertThat(PointValueNormalizer.normalize("007", dataType).value())
                .as("dataType=%s 必须原样保留前导零", dataType)
                .isEqualTo("007");
        }
    }

    @Test
    @DisplayName("dataType 缺失/未知 → UNKNOWN_DATA_TYPE（不猜数值还是文本）")
    void unknownDataTypeMustFail() {
        assertThat(PointValueNormalizer.normalize("23.5", null).failure())
            .isEqualTo(NormalizeFailure.UNKNOWN_DATA_TYPE);
        assertThat(PointValueNormalizer.normalize("23.5", "").failure())
            .isEqualTo(NormalizeFailure.UNKNOWN_DATA_TYPE);
        assertThat(PointValueNormalizer.normalize("23.5", "not-a-type").failure())
            .isEqualTo(NormalizeFailure.UNKNOWN_DATA_TYPE);
    }

    @Test
    @DisplayName("非 String 原值原样透传：Modbus/OPC UA 等已带类型的值不受本类影响")
    void nonStringValuesMustPassThrough() {
        assertThat(PointValueNormalizer.normalize(250, "decimal").value()).isEqualTo(250);
        assertThat(PointValueNormalizer.normalize(23.5d, "decimal").value()).isEqualTo(23.5d);
        assertThat(PointValueNormalizer.normalize(null, "decimal").value()).isNull();
        assertThat(PointValueNormalizer.normalize(250, "decimal").success()).isTrue();
    }

    @Test
    @DisplayName("成功结果与失败结果互斥：success() 与 failure() 口径一致")
    void outcomeMustBeConsistent() {
        PointValueNormalizer.Outcome ok = PointValueNormalizer.normalize("45", "int");
        assertThat(ok.success()).isTrue();
        assertThat(ok.failure()).isNull();

        PointValueNormalizer.Outcome failed = PointValueNormalizer.normalize("x", "int");
        assertThat(failed.success()).isFalse();
        assertThat(failed.failure()).isEqualTo(NormalizeFailure.NOT_NUMERIC);
        assertThat(failed.value()).isNull();
    }
}
