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

import java.math.BigDecimal;

/**
 * 数值文本的解析与精度判据（task-23 §4.6.5）：落 {@code value_double} 前的显式失败/精度损失判定。
 *
 * <p>与 {@link ReadingValueMapper}（列物化）分开：本类只回答「这个字符串能不能作为声明数值物化」
 * 与「它的声明值是否超出 double 的整数精确范围」，不碰列、不碰 IO。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class NumericValues {

    /**
     * double 可精确表示的整数上界 2^53：声明值绝对值**超过**它仍写数值列，但会丢整数精度 ⇒ 计数
     * {@code iot.timeseries.value.precision_lost} + WARN（§4.6.5，不改为文本列、不丢弃）。
     */
    public static final double MAX_EXACT_INTEGER_IN_DOUBLE = 9_007_199_254_740_992d;

    private NumericValues() {
    }

    /**
     * 严格解析十进制数值：去首尾空白后用 {@link BigDecimal} 解析（它天然拒绝 {@code NaN}/{@code Infinity}、
     * Java 专有后缀 {@code 1d}/{@code 1f} 等非协议形态），再转 {@code double}；非有限返回 {@code null}。
     *
     * @param raw 读数字符串（可空）
     * @return 可表示的数值；不可解析/非有限返回 {@code null}
     */
    public static Double parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
        double parsed = decimal.doubleValue();
        return Double.isFinite(parsed) ? parsed : null;
    }

    /**
     * 是否超出 double 的整数精确表示范围（**声明值**绝对值 &gt; 2^53）。
     *
     * <p>必须在 {@link BigDecimal} 上比较、不能用已解析的 {@code double}：例如 {@code 2^53 + 1}
     * 转 double 会先被四舍五入成 {@code 2^53}，用 double 判据会漏报。这是**精度损失告警**的判据，
     * 不是换列判据——仍写 {@code value_double} + 计数 + WARN（§4.6.5）。</p>
     *
     * @param raw 声明值字符串（可空/非法返回 {@code false}，非法值由 {@link #parse} 先行拦截）
     * @return 丢整数精度返回 {@code true}
     */
    public static boolean isPrecisionLost(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        try {
            return new BigDecimal(value).abs()
                .compareTo(BigDecimal.valueOf(MAX_EXACT_INTEGER_IN_DOUBLE)) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
