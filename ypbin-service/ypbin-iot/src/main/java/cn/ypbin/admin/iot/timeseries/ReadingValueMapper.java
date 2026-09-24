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

/**
 * 读数 → 时序表的**列映射**（§5.2.1）：决定写 `value_double` 还是 `value_text`。
 *
 * <p>判定按**值的词法形态**（不查物模型 ⇒ 每批零额外查询）：</p>
 * <ul>
 *   <li>`true`/`false`（不分大小写）→ 文本列（§5.2.1：布尔以文本存放，保持「永不双写」）；</li>
 *   <li>整数且绝对值 ≤ 2^53 → 数值列；**超过 2^53 的整数走文本列**（double 只有 53 位有效精度，
 *       静默丢精度比落文本更糟）；</li>
 *   <li>小数/科学计数法（可被 {@link Double#parseDouble} 解析）→ 数值列；</li>
 *   <li>其余（字符串/枚举/JSON/字节数组 hex）→ 文本列。</li>
 * </ul>
 *
 * <p>⚠️ 与设计文档的差异已同步：§5.2.1 原写「按物模型属性类型决定」，实现改为**先按词法形态判定**；
 * 物模型类型的强制覆盖（当二者冲突时以物模型为准）登记为后续增量——避免在写入热路径上引入逐批查库。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public final class ReadingValueMapper {

    /** 文本列名（§5.2.1）。 */
    public static final String COLUMN_TEXT = "value_text";

    /** 数值列名（§5.2.1）。 */
    public static final String COLUMN_DOUBLE = "value_double";

    /** double 的精确整数上界 2^53（超过即丢精度 ⇒ 走文本列）。 */
    private static final long MAX_EXACT_LONG_IN_DOUBLE = 1L << 53;

    private ReadingValueMapper() {
    }

    /**
     * 映射结果：数值列用 {@link #numericValue()}，文本列用 {@link #text()}。
     *
     * @param column       目标列名
     * @param numericValue 数值（仅数值列有意义）
     * @param text         文本（仅文本列有意义）
     * @author wenbin
     * @since 2026-09-24
     */
    public record MappedValue(String column, double numericValue, String text) {

        /** 是否写数值列。 */
        public boolean numeric() {
            return COLUMN_DOUBLE.equals(column);
        }
    }

    /**
     * 按词的形态把读数映射到目标列。
     *
     * @param raw 读数原值（字符串化；调用方保证非空）
     * @return 映射结果
     */
    public static MappedValue map(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            // 空串没有数值语义，落文本列（保持"每条读数都可见"），不猜 0
            return new MappedValue(COLUMN_TEXT, 0d, raw);
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new MappedValue(COLUMN_TEXT, 0d, value.toLowerCase(java.util.Locale.ROOT));
        }
        boolean integerForm = looksLikeInteger(value);
        if (integerForm) {
            Long exactLong = parseExactLong(value);
            if (exactLong == null) {
                // 纯数字但超出 long 范围：走 double 会**静默丢精度**（单测实证）⇒ 文本列，不截断
                return new MappedValue(COLUMN_TEXT, 0d, value);
            }
            if (Math.abs(exactLong) <= MAX_EXACT_LONG_IN_DOUBLE) {
                return new MappedValue(COLUMN_DOUBLE, exactLong.doubleValue(), null);
            }
            // 超过 2^53：数值列会静默丢精度 ⇒ 落文本列
            return new MappedValue(COLUMN_TEXT, 0d, value);
        }
        if (looksLikeDecimal(value)) {
            try {
                double parsed = Double.parseDouble(value);
                if (Double.isFinite(parsed)) {
                    return new MappedValue(COLUMN_DOUBLE, parsed, null);
                }
            } catch (NumberFormatException ignored) {
                // 形态像小数但解析失败（如 "1.2.3"）：按文本处理
            }
        }
        return new MappedValue(COLUMN_TEXT, 0d, value);
    }

    /** 是否"纯十进制整数"形态（可选符号 + 至少一位数字）⇒ `1e3`/`0x10`/`1d` 都不算。 */
    private static boolean looksLikeInteger(String value) {
        int index = startsWithSign(value) ? 1 : 0;
        if (index >= value.length()) {
            return false;
        }
        for (int i = index; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否"十进制数值"形态：只允许数字与 `+ - . e E`，且至少一位数字。
     *
     * <p>为什么不用 {@code Double.parseDouble} 直接兜底：它接受 Java 专有后缀（`1d`/`1f`）与
     * `NaN`/`Infinity`，这些**不是协议侧的数据形态**，会被误当数值落数值列（单测实证）。</p>
     */
    private static boolean looksLikeDecimal(String value) {
        boolean digitSeen = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                digitSeen = true;
                continue;
            }
            if (ch != '+' && ch != '-' && ch != '.' && ch != 'e' && ch != 'E') {
                return false;
            }
        }
        return digitSeen;
    }

    private static boolean startsWithSign(String value) {
        return value.startsWith("-") || value.startsWith("+");
    }

    /** 严格整数解析（仅纯十进制整数形态；溢出返回 {@code null}）。 */
    private static Long parseExactLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
