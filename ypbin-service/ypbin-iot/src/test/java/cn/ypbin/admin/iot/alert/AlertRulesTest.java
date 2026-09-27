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

import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertValueType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 点位阈值判定口径的用例（设计 §3.1 的 T1–T6 + §2.2.3 的四条不可判定）。
 *
 * <p><b>为什么这批用例是本设计的第一道防线</b>：判定语义里最危险的两个错误方向——
 * 「把不可判定当成越界」（误报刷屏）与「把不可判定当成未越界」（静默漏报）——**都不会报错、不会有
 * 编译期提示**，只能靠用例咬住。因此每条都写清「给定 ⇒ 期望」。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertRulesTest {

    /** 一条规则条件（默认数值域）。 */
    private static IotAlertRulePoint condition(String operator, String threshold, String deadband) {
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setPropertyId("temperature");
        point.setOperator(operator);
        point.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        point.setValueType(AlertValueType.NUMERIC.getCode());
        point.setDeadband(deadband == null ? null : new BigDecimal(deadband));
        return point;
    }

    private static AlertValueVerdict verdict(IotAlertRulePoint point, String raw) {
        return AlertRules.verdict(point, raw, AlertRules.QUALITY_GOOD, 1_000L, 1_000L, 60_000L);
    }

    @Test
    @DisplayName("T1 规则 GT 30 读到 35 ⇒ 越界")
    void t1GreaterThanTriggers() {
        assertThat(verdict(condition("GT", "30", null), "35"))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
    }

    @Test
    @DisplayName("T2 规则 GT 30 读到 30 ⇒ **不触发**（GT 是严格大于，边界值必须钉住）")
    void t2BoundaryOfGreaterThanDoesNotTrigger() {
        assertThat(verdict(condition("GT", "30", null), "30"))
            .isEqualTo(AlertValueVerdict.IN_RANGE);
    }

    @Test
    @DisplayName("T3 规则 GTE 30 读到 30 ⇒ 触发（边界值必须钉住）")
    void t3BoundaryOfGreaterOrEqualTriggers() {
        assertThat(verdict(condition("GTE", "30", null), "30"))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
    }

    @Test
    @DisplayName("T4 读到「abc」⇒ 不可判定（skipped_non_numeric），**不得**当 0、不得当越界")
    void t4NonNumericIsUndecidable() {
        AlertValueVerdict result = verdict(condition("GT", "30", null), "abc");
        assertThat(result).isEqualTo(AlertValueVerdict.NON_NUMERIC);
        assertThat(result.isDecidable()).isFalse();
        assertThat(result.getMetricName()).isEqualTo("iot.alert.evaluate.skipped_non_numeric");
        // 变异哨兵：把「不可判定」当成越界或未越界都会让下面两条断言失败
        assertThat(result.isOutOfRange()).isFalse();
        assertThat(result).isNotEqualTo(AlertValueVerdict.IN_RANGE);
    }

    @Test
    @DisplayName("T5 值合法但质量位非 GOOD ⇒ 不可判定（skipped_bad_quality）")
    void t5BadQualityIsUndecidable() {
        AlertValueVerdict result = AlertRules.verdict(condition("GT", "30", null), "35", "BAD", 1_000L,
            1_000L, 60_000L);
        assertThat(result).isEqualTo(AlertValueVerdict.BAD_QUALITY);
        // 质量位缺失同样不可判定（不能因为「没给质量」就当成好数据）
        assertThat(AlertRules.verdict(condition("GT", "30", null), "35", null, 1_000L, 1_000L, 60_000L))
            .isEqualTo(AlertValueVerdict.BAD_QUALITY);
        // 大小写不敏感：链路里出现过小写 good
        assertThat(AlertRules.verdict(condition("GT", "30", null), "35", "good", 1_000L, 1_000L, 60_000L))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
    }

    @Test
    @DisplayName("T6 值越界但读数时刻陈旧 ⇒ 不可判定（skipped_stale），陈旧**不等于**恢复")
    void t6StaleIsUndecidable() {
        AlertValueVerdict result = AlertRules.verdict(condition("GT", "30", null), "35",
            AlertRules.QUALITY_GOOD, 1_000L, 100_000L, 60_000L);
        assertThat(result).isEqualTo(AlertValueVerdict.STALE);
        assertThat(result.isDecidable()).isFalse();
        // 恰好等于 TTL 时**不算**陈旧（边界：判据是「超过」，不是「达到」）
        assertThat(AlertRules.verdict(condition("GT", "30", null), "35", AlertRules.QUALITY_GOOD, 1_000L,
            61_000L, 60_000L)).isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
        // 读数时刻缺失同样不可判定（没有时刻的「最新值」无法判时效）
        assertThat(AlertRules.verdict(condition("GT", "30", null), "35", AlertRules.QUALITY_GOOD, null,
            100_000L, 60_000L)).isEqualTo(AlertValueVerdict.STALE);
    }

    @Test
    @DisplayName("T6-补充：缺失最新值 ⇒ missed，不是「未越界」")
    void missingSampleIsUndecidable() {
        assertThat(AlertValueVerdict.MISSING.isDecidable()).isFalse();
        assertThat(AlertValueVerdict.MISSING.getMetricName()).isEqualTo("iot.alert.evaluate.skipped_missing");
    }

    @Test
    @DisplayName("T7 恢复判定：不配回差时「不满足触发条件」即恢复（25 对 GT 30 成立）")
    void t7RecoveryWithoutDeadband() {
        IotAlertRulePoint point = condition("GT", "30", null);
        assertThat(AlertRules.recovered(point, new BigDecimal("25"))).isTrue();
        assertThat(AlertRules.recovered(point, new BigDecimal("30"))).isTrue();
        assertThat(AlertRules.recovered(point, new BigDecimal("35"))).isFalse();
    }

    @Test
    @DisplayName("回差（滞回）：未越过回差**不恢复**，越过才恢复——防阈值附近的触发/恢复风暴")
    void deadbandHoldsAlertNearThreshold() {
        IotAlertRulePoint upper = condition("GT", "80", "5");
        assertThat(AlertRules.recovered(upper, new BigDecimal("78"))).isFalse();
        assertThat(AlertRules.recovered(upper, new BigDecimal("75"))).isFalse();
        assertThat(AlertRules.recovered(upper, new BigDecimal("74.9"))).isTrue();

        IotAlertRulePoint lower = condition("LT", "10", "2");
        assertThat(AlertRules.recovered(lower, new BigDecimal("11"))).isFalse();
        assertThat(AlertRules.recovered(lower, new BigDecimal("12"))).isFalse();
        assertThat(AlertRules.recovered(lower, new BigDecimal("12.1"))).isTrue();
    }

    @Test
    @DisplayName("回差对「等于/不等于」的作用：EQ 需要偏离超过回差，NE 回到相等即恢复")
    void deadbandForEqualityOperators() {
        assertThat(AlertRules.recovered(condition("EQ", "1", "0.2"), new BigDecimal("1.1"))).isFalse();
        assertThat(AlertRules.recovered(condition("EQ", "1", "0.2"), new BigDecimal("1.5"))).isTrue();
        assertThat(AlertRules.recovered(condition("NE", "1", "5"), new BigDecimal("1"))).isTrue();
    }

    @Test
    @DisplayName("条件非法 ⇒ 不可判定且**不恢复**（宁可挂着被人看见，也不能静默清掉告警）")
    void invalidConditionNeverRecovers() {
        assertThat(verdict(condition("GT", null, null), "35"))
            .isEqualTo(AlertValueVerdict.INVALID_CONDITION);
        assertThat(verdict(condition("BAD", "30", null), "35"))
            .isEqualTo(AlertValueVerdict.INVALID_CONDITION);
        assertThat(AlertRules.recovered(condition("BAD", "30", null), new BigDecimal("35"))).isFalse();
        assertThat(AlertRules.recovered(condition("GT", null, null), new BigDecimal("35"))).isFalse();
    }

    @Test
    @DisplayName("布尔点位：只允许等于/不等于，且 true/false/1/0 显式解析")
    void booleanPointSemantics() {
        IotAlertRulePoint eq = condition("EQ", "1", null);
        eq.setValueType(AlertValueType.BOOLEAN.getCode());
        assertThat(AlertRules.verdict(eq, "true", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
        assertThat(AlertRules.verdict(eq, "1", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
        assertThat(AlertRules.verdict(eq, "false", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.IN_RANGE);
        assertThat(AlertRules.verdict(eq, "TRUE", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
        // 布尔点位配大小比较 ⇒ 条件非法（不做「true 当 1」的隐式转换）
        IotAlertRulePoint gt = condition("GT", "0", null);
        gt.setValueType(AlertValueType.BOOLEAN.getCode());
        assertThat(AlertRules.verdict(gt, "true", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.INVALID_CONDITION);
        // 布尔解析不出来同样不可判定
        assertThat(AlertRules.verdict(eq, "yes", AlertRules.QUALITY_GOOD, 1L, 1L, 60_000L))
            .isEqualTo(AlertValueVerdict.NON_NUMERIC);
    }

    @Test
    @DisplayName("数值解析拒绝 NaN/Infinity 这类「合法 double 但比较恒假」的写法")
    void numericParsingRejectsDoubleSpecialValues() {
        assertThat(AlertRules.parseNumeric("NaN")).isNull();
        assertThat(AlertRules.parseNumeric("Infinity")).isNull();
        assertThat(AlertRules.parseNumeric("-Infinity")).isNull();
        assertThat(AlertRules.parseNumeric(" 35 ")).isEqualByComparingTo("35");
        assertThat(AlertRules.parseNumeric("0.1")).isEqualByComparingTo("0.1");
        assertThat(AlertRules.parseNumeric("")).isNull();
        assertThat(AlertRules.parseNumeric(null)).isNull();
    }

    @Test
    @DisplayName("阈值用 DECIMAL 比较：0.1 不因二进制近似而在边界上给出反直觉结果")
    void decimalComparisonIsExact() {
        IotAlertRulePoint point = condition("GT", "0.1", null);
        assertThat(verdict(point, "0.1")).isEqualTo(AlertValueVerdict.IN_RANGE);
        assertThat(verdict(point, "0.10000000000000000001")).isEqualTo(AlertValueVerdict.OUT_OF_RANGE);
    }

    @Test
    @DisplayName("去重键形态：规则键 ruleId:deviceId:propertyId，断档键带事件 ID")
    void dedupKeyShape() {
        assertThat(AlertRules.dedupKey(7L, 9L, "temperature")).isEqualTo("7:9:temperature");
        assertThat(AlertRules.dedupKey(7L, 9L, null)).isEqualTo("7:9:-");
        assertThat(AlertRules.dedupKey(7L, 9L, "  ")).isEqualTo("7:9:-");
        assertThat(AlertRules.outageDedupKey(9L, 42L)).isEqualTo("OUTAGE:9:42");
        // 断档键与规则键不会互相碰撞（前缀不同）
        assertThat(AlertRules.outageDedupKey(9L, 42L))
            .isNotEqualTo(AlertRules.dedupKey(AlertRules.RULE_ID_OUTAGE, 9L, "42"));
    }

    @Test
    @DisplayName("阈值快照：把符号与阈值（含回差）落成一句话，供「当时为何报警」解释")
    void thresholdSnapshotExplains() {
        assertThat(AlertRules.thresholdSnapshot(condition("GT", "80.00", null))).isEqualTo("> 80");
        assertThat(AlertRules.thresholdSnapshot(condition("LTE", "10", "0.5"))).isEqualTo("<= 10（回差 0.5）");
        assertThat(AlertRules.thresholdSnapshot(null)).isNull();
        assertThat(AlertRules.thresholdSnapshot(condition("BAD", "10", null))).isNull();
    }

    @Test
    @DisplayName("全比较符穷举：GT/GTE/LT/LTE/EQ/NE 在阈值上下与阈值处各自的结果")
    void allOperatorsExhaustive() {
        assertThat(AlertRules.conditionHolds(AlertOperator.GT, new BigDecimal("10"), new BigDecimal("11"))).isTrue();
        assertThat(AlertRules.conditionHolds(AlertOperator.GT, new BigDecimal("10"), new BigDecimal("10"))).isFalse();
        assertThat(AlertRules.conditionHolds(AlertOperator.GTE, new BigDecimal("10"), new BigDecimal("10"))).isTrue();
        assertThat(AlertRules.conditionHolds(AlertOperator.LT, new BigDecimal("10"), new BigDecimal("9"))).isTrue();
        assertThat(AlertRules.conditionHolds(AlertOperator.LT, new BigDecimal("10"), new BigDecimal("10"))).isFalse();
        assertThat(AlertRules.conditionHolds(AlertOperator.LTE, new BigDecimal("10"), new BigDecimal("10"))).isTrue();
        assertThat(AlertRules.conditionHolds(AlertOperator.EQ, new BigDecimal("10"), new BigDecimal("10.0"))).isTrue();
        assertThat(AlertRules.conditionHolds(AlertOperator.NE, new BigDecimal("10"), new BigDecimal("10.0"))).isFalse();
    }
}
