/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 影子值比较口径的门禁（delta 的唯一判据）。
 *
 * <p>这里咬的是「reported 存字符串、desired 可能是数字」这条真实差异：若比较口径退化成
 * {@code Objects.equals}，{@code "25.0"} 与 {@code 25.0} 会被判不等 ⇒ delta 永远清不掉。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ShadowValueComparatorTest {

    @Test
    @DisplayName("数值等价：数字/数字字符串/不同 scale 一律按数值比较")
    void numericValuesMustCompareByValue() {
        assertThat(ShadowValueComparator.equal("25.0", 25)).isTrue();
        assertThat(ShadowValueComparator.equal("25", 25.0d)).isTrue();
        assertThat(ShadowValueComparator.equal(25, 25L)).isTrue();
        assertThat(ShadowValueComparator.equal("0.30", "0.3")).isTrue();
        assertThat(ShadowValueComparator.equal("-5", -5.0)).isTrue();
    }

    @Test
    @DisplayName("数值不等：真正不同的值必须判不等（防归一化把 delta 抹平）")
    void differentNumericValuesMustNotBeEqual() {
        assertThat(ShadowValueComparator.equal("0.30000000000000004", 0.3d)).isFalse();
        assertThat(ShadowValueComparator.equal("26", 25)).isFalse();
        assertThat(ShadowValueComparator.equal("-0", 0)).as("BigDecimal 的 -0 与 0 compareTo 为 0").isTrue();
    }

    @Test
    @DisplayName("布尔与字符串布尔归一；普通字符串原样比较（不 trim）")
    void booleanAndStringMustNormalize() {
        assertThat(ShadowValueComparator.equal("true", true)).isTrue();
        assertThat(ShadowValueComparator.equal("FALSE", false)).isTrue();
        assertThat(ShadowValueComparator.equal("OFF", "OFF")).isTrue();
        assertThat(ShadowValueComparator.equal("OFF", "off")).isFalse();
        assertThat(ShadowValueComparator.equal("A ", "A")).as("不 trim：属性值的前后空格可能有语义").isFalse();
    }

    @Test
    @DisplayName("null 只与 null 等价；未知类型退回字符串口径不抛异常")
    void nullAndFallback() {
        assertThat(ShadowValueComparator.equal(null, null)).isTrue();
        assertThat(ShadowValueComparator.equal(null, "x")).isFalse();
        assertThat(ShadowValueComparator.equal("x", null)).isFalse();
        assertThat(ShadowValueComparator.equal("", "")).isTrue();
    }

    @Test
    @DisplayName("对象/数组递归比较（键序无关；数字仍按数值）")
    void nestedStructuresMustCompareDeeply() {
        assertThat(ShadowValueComparator.equal(Map.of("a", 1, "b", "x"), Map.of("b", "x", "a", "1.0")))
            .isTrue();
        assertThat(ShadowValueComparator.equal(List.of(1, "2"), List.of("1", 2))).isTrue();
        assertThat(ShadowValueComparator.equal(List.of(1, 2), List.of(2, 1))).isFalse();
        assertThat(ShadowValueComparator.equal(Map.of("a", 1), Map.of("a", 2))).isFalse();
    }
}
