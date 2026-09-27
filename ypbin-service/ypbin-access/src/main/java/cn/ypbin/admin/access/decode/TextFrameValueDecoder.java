/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

import cn.ypbin.admin.iot.enums.IotProtocol;
import cn.ypbin.admin.iot.enums.ThingModelDataType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * TCP 透传的**文本帧解码器**：把 {@code TEMP=23.5,SEQ=12} 这样的键值帧解成该点位的规范值。
 *
 * <p><b>帧约定</b>（过渡实现的口径，逐条都可被单测钉住）：</p>
 * <ol>
 *   <li>载荷按<b>严格 UTF-8</b> 解码：非法字节**不**替换成 U+FFFD，而是判 {@link DecodeFailure#NOT_UTF8}
 *       ——{@code new String(bytes, UTF_8)} 会静默产出替换字符，那正是「数据已变形但链路报成功」的来源
 *       （框架 MQTT 模块 {@code MqttPayloadFormat.isValidUtf8} 是同一取向）；</li>
 *   <li>帧由 {@code KEY=VALUE} 字段组成，字段之间用 {@code ,}／{@code ;}／换行分隔；</li>
 *   <li>该点位的键由点位映射的 {@code raw_address} 声明（TCP 透传下地址就是帧里的键，与 Modbus 用
 *       {@code holding:0}、OPC UA 用 NodeId、MQTT 用 topic 同理）；键名比较**大小写不敏感**且两侧去空白，
 *       重复键**先出现者生效**（取值确定，不受字段顺序抖动影响）；</li>
 *   <li>取到 token 后按<b>物模型数据类型</b>规范化：{@code int}/{@code long} ⇒ 整数，
 *       {@code decimal} ⇒ 十进制数（输出 {@link BigDecimal}，缩放/偏移可继续作用其上，落库时是干净数值串），
 *       {@code bool} ⇒ {@code true}/{@code false}，其余（{@code string}/{@code enum}/{@code date_time}/
 *       {@code json_object}/{@code array}）⇒ 文本原样（**不做任何隐式转换**：{@code serialNo} 这类必须仍是文本）；</li>
 *   <li>任何一步不满足 ⇒ 具名失败原因（丢弃 + 计数 + WARN），**不猜**、不兜底。</li>
 * </ol>
 *
 * <p><b>明确不做（如实声明，方案见 {@code docs/VALUE-DECODE-DESIGN.md}）</b>：Modbus 寄存器级的
 * 16/32 位、大小端（{@code byte_order} 与位宽/符号/位域参数在 {@code iot_point_mapping} 里尚不存在）
 * 与「整帧即值」（无键）的文本帧均**未实现**：前者参数不足，后者需要显式的地址类型声明。
 * 命中这类配置的读数会走「帧里找不到键 ⇒ 丢弃 + 计数 + WARN」，不会静默写垃圾。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public final class TextFrameValueDecoder implements ValueDecoder {

    /** 字段分隔符（逗号）。 */
    private static final char FIELD_SEPARATOR_COMMA = ',';

    /** 字段分隔符（分号）。 */
    private static final char FIELD_SEPARATOR_SEMICOLON = ';';

    /** 键值分隔符。 */
    private static final char KEY_VALUE_SEPARATOR = '=';

    /** 布尔真值字面量。 */
    private static final String BOOL_TRUE = "true";

    /** 布尔假值字面量。 */
    private static final String BOOL_FALSE = "false";

    /** 布尔真值的数字形态（寄存器/线圈常用）；统一规范化为 {@link #BOOL_TRUE}。 */
    private static final String BOOL_TRUE_NUMERIC = "1";

    /** 布尔假值的数字形态；统一规范化为 {@link #BOOL_FALSE}。 */
    private static final String BOOL_FALSE_NUMERIC = "0";

    @Override
    public boolean supports(String protocol) {
        return IotProtocol.TCP.getCode().equals(protocol);
    }

    @Override
    public DecodeOutcome decode(Object rawValue, String addressKey, String dataType) {
        if (!(rawValue instanceof byte[] payload)) {
            // 非字节载荷：其它协议模块已给出带类型的值（Modbus 数值 / MQTT 文本等）——原样透传，
            // 「其它协议/其它设备的取值路径不受影响」这条由此保证
            return DecodeOutcome.ok(rawValue);
        }
        if (payload.length == 0) {
            return DecodeOutcome.failed(DecodeFailure.EMPTY_PAYLOAD);
        }
        String text = decodeStrictUtf8(payload);
        if (text == null) {
            return DecodeOutcome.failed(DecodeFailure.NOT_UTF8);
        }
        Map<String, String> fields = parseFields(text);
        if (fields.isEmpty()) {
            return DecodeOutcome.failed(DecodeFailure.NOT_KEY_VALUE_FRAME);
        }
        if (addressKey == null || addressKey.isBlank()) {
            // 没有键就没有可对齐的坐标：丢弃而不是「帧里只有一个值就用它」（那是猜测）
            return DecodeOutcome.failed(DecodeFailure.KEY_NOT_FOUND);
        }
        String token = fields.get(normalizeKey(addressKey));
        if (token == null) {
            return DecodeOutcome.failed(DecodeFailure.KEY_NOT_FOUND);
        }
        if (token.isBlank()) {
            return DecodeOutcome.failed(DecodeFailure.EMPTY_VALUE);
        }
        return canonicalize(token, dataType);
    }

    /**
     * 解析文本帧为键值表（键已归一化为小写、去空白）。
     *
     * @param text 帧文本
     * @return 键值表；没有任何 {@code KEY=VALUE} 字段时返回空表
     */
    private static Map<String, String> parseFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        int index = 0;
        while (index < text.length()) {
            int end = index;
            while (end < text.length() && !isFieldSeparator(text.charAt(end))) {
                end++;
            }
            String field = text.substring(index, end).trim();
            index = end + 1;
            int separator = field.indexOf(KEY_VALUE_SEPARATOR);
            if (separator <= 0) {
                // 没有 '='（或键为空）的字段不是键值字段：跳过。整帧都如此则上层判「帧格式不符」
                continue;
            }
            String key = normalizeKey(field.substring(0, separator));
            if (key.isEmpty()) {
                continue;
            }
            // 先出现者生效：字段顺序抖动（设备端重构/网络重排）不得改变取值
            fields.putIfAbsent(key, field.substring(separator + 1).trim());
        }
        return fields;
    }

    /** 是否字段分隔符（逗号/分号/回车/换行）。 */
    private static boolean isFieldSeparator(char ch) {
        return ch == FIELD_SEPARATOR_COMMA || ch == FIELD_SEPARATOR_SEMICOLON
            || ch == '\r' || ch == '\n';
    }

    /** 键归一化：去首尾空白 + 转小写（Locale.ROOT，避免土耳其语 i 之类的区域差异）。 */
    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 按物模型数据类型规范化 token。
     *
     * @param token    帧里取到的原始 token（已去空白）
     * @param dataType 物模型属性数据类型 code（可空）
     * @return 规范值或具名失败
     */
    private static DecodeOutcome canonicalize(String token, String dataType) {
        ThingModelDataType type = ThingModelDataType.of(dataType);
        if (type == null) {
            // 类型缺失/未知 ⇒ 不猜数值还是文本（猜错会把文本写进数值列，或反之）
            return DecodeOutcome.failed(DecodeFailure.UNKNOWN_DATA_TYPE);
        }
        return switch (type) {
            case INT, LONG -> toInteger(token);
            case DECIMAL -> toDecimal(token);
            case BOOL -> toBoolean(token);
            default -> DecodeOutcome.ok(token);
        };
    }

    /** 整数：仅接受纯十进制整数形态，越界**不截断**（超 long 即失败）。 */
    private static DecodeOutcome toInteger(String token) {
        if (!looksLikeInteger(token)) {
            return DecodeOutcome.failed(DecodeFailure.NOT_NUMERIC);
        }
        try {
            return DecodeOutcome.ok(Long.valueOf(token));
        } catch (NumberFormatException ex) {
            // 纯数字但超出 long 范围：静默截断比丢弃更糟（与 ReadingValueMapper 的精度取向一致）
            return DecodeOutcome.failed(DecodeFailure.NOT_NUMERIC);
        }
    }

    /** 小数：形态校验通过后交给 BigDecimal（不经过 double，避免精度损失与 NaN/Infinity 形态）。 */
    private static DecodeOutcome toDecimal(String token) {
        if (!looksLikeDecimal(token)) {
            return DecodeOutcome.failed(DecodeFailure.NOT_NUMERIC);
        }
        try {
            return DecodeOutcome.ok(new BigDecimal(token));
        } catch (NumberFormatException ex) {
            // 形态像数值但 BigDecimal 不认（如 "1.2.3"）：如实失败，不做兜底转换
            return DecodeOutcome.failed(DecodeFailure.NOT_NUMERIC);
        }
    }

    /** 布尔：{@code true/false}（忽略大小写）与 {@code 1/0}，统一规范化为 {@code true/false} 文本。 */
    private static DecodeOutcome toBoolean(String token) {
        if (BOOL_TRUE.equalsIgnoreCase(token) || BOOL_TRUE_NUMERIC.equals(token)) {
            return DecodeOutcome.ok(BOOL_TRUE);
        }
        if (BOOL_FALSE.equalsIgnoreCase(token) || BOOL_FALSE_NUMERIC.equals(token)) {
            return DecodeOutcome.ok(BOOL_FALSE);
        }
        return DecodeOutcome.failed(DecodeFailure.NOT_BOOLEAN);
    }

    /** 是否纯十进制整数形态（可选符号 + 至少一位数字）。 */
    private static boolean looksLikeInteger(String token) {
        int index = startsWithSign(token) ? 1 : 0;
        if (index >= token.length()) {
            return false;
        }
        for (int i = index; i < token.length(); i++) {
            char ch = token.charAt(i);
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
     * {@code NaN}/{@code Infinity}，这些不是协议侧的数据形态（同取向见 {@code ReadingValueMapper}）。</p>
     */
    private static boolean looksLikeDecimal(String token) {
        boolean digitSeen = false;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
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

    private static boolean startsWithSign(String token) {
        return token.startsWith("+") || token.startsWith("-");
    }

    /**
     * 严格 UTF-8 解码：非法字节返回 {@code null}（**不**产出替换字符）。
     *
     * @param payload 载荷
     * @return 文本；非法 UTF-8 时返回 {@code null}
     */
    private static String decodeStrictUtf8(byte[] payload) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException ex) {
            // 非法字节序列不是「文本帧」：交给调用方按 NOT_UTF8 丢弃 + 计数，绝不静默产出 U+FFFD
            return null;
        }
    }
}
