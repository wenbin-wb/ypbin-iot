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

import cn.ypbin.admin.iot.enums.ThingModelDataType;
import java.math.BigDecimal;

/**
 * 读数值的**宿主物模型类型规范化**：把 SDK 交付的文本按 {@code iot_property.data_type} 变成
 * 规范形态（整数/十进制/布尔文本/原样文本）。
 *
 * <p><b>🔴 为什么它留在宿主、不是「过渡层残留」</b>（下一次审计别再把它当解码层删掉）：</p>
 * <ol>
 *   <li><b>它是物模型语义，不是帧解析</b>。starter 0.3.0 的 {@code payload-format=key-value}
 *       （{@code TcpValueSelector}）承接的是「UTF-8 + KEY=VALUE 拆分 + 按 {@code raw_address} 取键」
 *       这一段；其类注释明确写着：<b>「本类不做数据类型转换，帧里的值以 {@code String} 交付——
 *       类型属于宿主物模型语义。」</b>因此「文本 → 物模型类型」这一步**没有任何 SDK 承接方**。</li>
 *   <li><b>缩放的前置</b>：{@link AccessReading#applyScale} 只对 {@code Number}/{@code BigDecimal}
 *       生效；若直接把 SDK 的 {@code String} 交给它，{@code scaleFactor}/{@code offsetValue} 会
 *       <b>静默失效 ⇒ 读数错值</b>（不是格式问题）。</li>
 *   <li><b>落列形态的前置</b>：iot 侧 {@code ReadingValueMapper} 按**值的词法形态**决定写
 *       {@code value_double} 还是 {@code value_text}；布尔 {@code 1/0} 必须先规范成
 *       {@code true/false} 才会落文本列（与切换前一致）。</li>
 *   <li><b>拒绝语义</b>：{@code int}/{@code decimal} 收到非数值文本、{@code bool} 收到非法形态、
 *       {@code dataType} 缺失/未知时，切换前是「丢弃 + 计数 + WARN（不猜）」；这条纪律必须保留。</li>
 * </ol>
 *
 * <p><b>只作用于 GOOD 值</b>：SDK 的 BAD（selector 未命中/非 UTF-8/无键值字段）由调用方原样穿透，
 * 不进入本类、不产生规范化失败计数（失败不得被「洗白」成有效值）。</p>
 *
 * <p><b>边界</b>：非 {@code String} 原值原样透传（Modbus/OPC UA 等已给出带类型的值；调用方也只在
 * TCP 协议下调用本类）。</p>
 *
 * <p><b>实现来源</b>：本类的类型规范化逻辑与删除前的 {@code TextFrameValueDecoder#canonicalize}
 * <b>逐字等价</b>（帧解析部分已删除），以保证「切换前是什么、切换后还是什么」；见
 * {@code docs/VALUE-DECODE-DESIGN.md} §5。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class PointValueNormalizer {

    private PointValueNormalizer() {
    }

    /**
     * 规范化结果：成功时 {@code value} 为规范值；失败时 {@code failure} 非空（调用方据此丢弃 + 计数）。
     *
     * @param value   规范值（失败时为 {@code null}）
     * @param failure 失败原因（成功时为 {@code null}）
     * @author wenbin
     * @since 2026-10-13
     */
    public record Outcome(Object value, NormalizeFailure failure) {

        /** 是否成功。 */
        public boolean success() {
            return failure == null;
        }

        static Outcome ok(Object value) {
            return new Outcome(value, null);
        }

        static Outcome failed(NormalizeFailure failure) {
            return new Outcome(null, failure);
        }
    }

    /**
     * 按物模型数据类型规范化一个读数原值。
     *
     * <p>规则（与删除前的宿主解码层逐字一致）：{@code int}/{@code long} ⇒ 严格十进制整数（越界不截断，
     * 失败 {@link NormalizeFailure#NOT_NUMERIC}）；{@code decimal} ⇒ {@link BigDecimal}
     * （不经过 double，避免精度损失与 NaN/Infinity）；{@code bool} ⇒ {@code true/false} 文本
     * （接受 {@code true/false/1/0}，忽略大小写）；其余 6 类 ⇒ 文本原样（**不做任何隐式转换**）。</p>
     *
     * @param raw      原始值（TCP key-value 下是 SDK 交付的 {@code String}；非 String 原样透传）
     * @param dataType 物模型数据类型 code（可空）
     * @return 规范化结果（绝不返回 {@code null}）
     */
    public static Outcome normalize(Object raw, String dataType) {
        if (!(raw instanceof String valueText)) {
            // 非字节/非文本载荷：其它协议模块已给出带类型的值——原样透传，不参与规范化
            return Outcome.ok(raw);
        }
        ThingModelDataType type = ThingModelDataType.of(dataType);
        if (type == null) {
            // 类型缺失/未知 ⇒ 不猜数值还是文本（猜错会把文本写进数值列，或反之）
            return Outcome.failed(NormalizeFailure.UNKNOWN_DATA_TYPE);
        }
        return switch (type) {
            case INT, LONG -> toInteger(valueText);
            case DECIMAL -> toDecimal(valueText);
            case BOOL -> toBoolean(valueText);
            default -> Outcome.ok(valueText);
        };
    }

    /** 整数：仅接受纯十进制整数形态，越界**不截断**（超 long 即失败）。 */
    private static Outcome toInteger(String valueText) {
        if (!looksLikeInteger(valueText)) {
            return Outcome.failed(NormalizeFailure.NOT_NUMERIC);
        }
        try {
            return Outcome.ok(Long.valueOf(valueText));
        } catch (NumberFormatException ex) {
            // 纯数字但超出 long 范围：静默截断比丢弃更糟（与 ReadingValueMapper 的精度取向一致）
            return Outcome.failed(NormalizeFailure.NOT_NUMERIC);
        }
    }

    /** 小数：形态校验通过后交给 BigDecimal（不经过 double，避免精度损失与 NaN/Infinity 形态）。 */
    private static Outcome toDecimal(String valueText) {
        if (!looksLikeDecimal(valueText)) {
            return Outcome.failed(NormalizeFailure.NOT_NUMERIC);
        }
        try {
            return Outcome.ok(new BigDecimal(valueText));
        } catch (NumberFormatException ex) {
            // 形态像数值但 BigDecimal 不认（如 "1.2.3"）：如实失败，不做兜底转换
            return Outcome.failed(NormalizeFailure.NOT_NUMERIC);
        }
    }

    /** 布尔：{@code true/false}（忽略大小写）与 {@code 1/0}，统一规范化为 {@code true/false} 文本。 */
    private static Outcome toBoolean(String valueText) {
        if ("true".equalsIgnoreCase(valueText) || "1".equals(valueText)) {
            return Outcome.ok("true");
        }
        if ("false".equalsIgnoreCase(valueText) || "0".equals(valueText)) {
            return Outcome.ok("false");
        }
        return Outcome.failed(NormalizeFailure.NOT_BOOLEAN);
    }

    /** 是否纯十进制整数形态（可选符号 + 至少一位数字）。 */
    private static boolean looksLikeInteger(String valueText) {
        int index = startsWithSign(valueText) ? 1 : 0;
        if (index >= valueText.length()) {
            return false;
        }
        for (int i = index; i < valueText.length(); i++) {
            char ch = valueText.charAt(i);
            if (ch < '0' || ch > '9') {
                // 只认 ASCII 数字：全角数字/十六进制前缀都不是本层约定的数据形态
                return false;
            }
        }
        return true;
    }

    /**
     * 是否十进制数值形态：只允许数字与 {@code + - . e E}，且至少一位数字。
     *
     * <p>为什么不直接 {@code Double.parseDouble} 兜底：它接受 Java 专有后缀（{@code 1d}/{@code 1f}）与
     * {@code NaN}/{@code Infinity}，这些不是协议侧的数据形态。</p>
     */
    private static boolean looksLikeDecimal(String valueText) {
        boolean digitSeen = false;
        for (int i = 0; i < valueText.length(); i++) {
            char ch = valueText.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digitSeen = true;
                continue;
            }
            if (ch != '+' && ch != '-' && ch != '.' && ch != 'e' && ch != 'E') {
                return false;
            }
        }
        return digitSeen;
    }

    private static boolean startsWithSign(String valueText) {
        return valueText.startsWith("+") || valueText.startsWith("-");
    }
}
