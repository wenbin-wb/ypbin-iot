/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 条件组快照编解码的用例（真 AND 语义配套，2026-10-15）。
 *
 * <p>快照是「为什么这条告警响了」的唯一逐条件证据，必须同时满足：可解析（页面/通知能逐条读出来）、
 * 抗脏值（读数原值可能含分隔符与控制字符）、长度有上界且截断可见（列宽 4000，超长不得静默丢）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class AlertConditionSnapshotTest {

    private static AlertConditionSnapshot.ConditionReading reading(String propertyId, String operator,
                                                                  String threshold,
                                                                  AlertValueVerdict verdict,
                                                                  String value) {
        return new AlertConditionSnapshot.ConditionReading(propertyId, operator,
            threshold == null ? null : new BigDecimal(threshold), verdict, value);
    }

    @Test
    @DisplayName("编码/解码往返：逐条件保序、阈值与判定不丢失")
    void encodeDecodeRoundTrip() {
        List<AlertConditionSnapshot.ConditionReading> readings = List.of(
            reading("temperature", "GT", "30", AlertValueVerdict.OUT_OF_RANGE, "85.5"),
            reading("humidity", "LT", "20", AlertValueVerdict.IN_RANGE, "25"));
        String encoded = AlertConditionSnapshot.encode(readings);
        assertThat(encoded).isEqualTo("temperature|GT|30|OUT_OF_RANGE|85.5;humidity|LT|20|IN_RANGE|25");
        // 「原样展示」：85.5 不被四舍五入、阈值 30 不带多余尾零
        assertThat(encoded).contains("85.5");

        List<AlertConditionSnapshot.ConditionReading> decoded = AlertConditionSnapshot.decode(encoded);
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).propertyId()).isEqualTo("temperature");
        assertThat(decoded.get(0).operatorCode()).isEqualTo("GT");
        assertThat(decoded.get(0).threshold()).isEqualByComparingTo("30");
        assertThat(decoded.get(0).verdict()).isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
        assertThat(decoded.get(0).value()).isEqualTo("85.5");
        assertThat(decoded.get(1).verdict()).isEqualTo(AlertValueVerdict.IN_RANGE);
    }

    @Test
    @DisplayName("抗脏值：读数里的分隔符/控制字符被清洗，value 放最后一段 ⇒ 字段边界不错位")
    void dirtyValueIsSanitized() {
        String encoded = AlertConditionSnapshot.encode(List.of(
            reading("temperature", "GT", "30", AlertValueVerdict.OUT_OF_RANGE, "1|2;3\n4")));
        assertThat(encoded).isEqualTo("temperature|GT|30|OUT_OF_RANGE|1 2 3 4");
        assertThat(AlertConditionSnapshot.decode(encoded)).hasSize(1);
        assertThat(AlertConditionSnapshot.decode(encoded).get(0).value()).isEqualTo("1 2 3 4");
    }

    @Test
    @DisplayName("长度上界：按整行截断并显式追加 省略(+N) 标记，绝不静默丢弃")
    void truncationIsExplicit() {
        List<AlertConditionSnapshot.ConditionReading> readings = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            readings.add(reading("property-" + index, "GT", "30",
                AlertValueVerdict.OUT_OF_RANGE, "35"));
        }
        String encoded = AlertConditionSnapshot.encode(readings, 120);
        assertThat(encoded.length()).isLessThanOrEqualTo(120);
        assertThat(encoded).contains("…(+");
        // 截断标记里的 N 必须等于被省略的行数，且已写入的行仍然可解码
        int omitted = Integer.parseInt(encoded.substring(encoded.lastIndexOf("(+") + 2,
            encoded.lastIndexOf(")")));
        List<AlertConditionSnapshot.ConditionReading> decoded = AlertConditionSnapshot.decode(encoded);
        assertThat(decoded.size() + omitted).isEqualTo(readings.size());
    }

    @Test
    @DisplayName("解码宽容：空/畸形快照返回空列表或跳过畸形行，绝不抛异常（展示数据不该拖垮页面/投递）")
    void decodeIsTolerant() {
        assertThat(AlertConditionSnapshot.decode(null)).isEmpty();
        assertThat(AlertConditionSnapshot.decode("")).isEmpty();
        assertThat(AlertConditionSnapshot.decode("   ")).isEmpty();
        // 字段数不足的畸形行跳过；合法的行保留
        assertThat(AlertConditionSnapshot.decode("bad-row;temperature|GT|30|OUT_OF_RANGE|35"))
            .hasSize(1);
        // 未知判定码 ⇒ null（展示成「不可判定」），不猜一个结果
        assertThat(AlertConditionSnapshot.decode("temperature|GT|30|NO_SUCH|35").get(0).verdict())
            .isNull();
        // 阈值被改成非数字 ⇒ null（不抛）
        assertThat(AlertConditionSnapshot.decode("temperature|GT|abc|IN_RANGE|35").get(0).threshold())
            .isNull();
    }

    @Test
    @DisplayName("可读描述：阈值/读数原样、判定用中文；单条件与多条件文案都能人读")
    void describeIsHumanReadable() {
        assertThat(reading("temperature", "GT", "30", AlertValueVerdict.OUT_OF_RANGE, "85.5").describe())
            .isEqualTo("temperature > 30，读到 85.5（越界）");
        assertThat(reading("humidity", "LT", "20", AlertValueVerdict.IN_RANGE, "25").describe())
            .contains("未越界");
        // 条件非法 / 无读数时用 - 兜底，不出现 null
        assertThat(reading("x", "BAD", null, null, null).describe()).doesNotContain("null");
    }

    @Test
    @DisplayName("空组编码为空串（不返回 null），解码空串返回空列表")
    void emptyGroupIsSafe() {
        assertThat(AlertConditionSnapshot.encode(List.of())).isEmpty();
        assertThat(AlertConditionSnapshot.encode(null)).isEmpty();
    }
}
