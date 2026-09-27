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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TCP 文本帧解码器单测（**数据形态的源头**：库里最终是数值还是垃圾，由这里决定）。
 *
 * <p>用例逐条对应「解码失败不静默」与「不猜类型」两条要求：帧格式不符、键缺失、长度不足、
 * 二进制（大小端寄存器）帧、未知类型都必须给出**具名失败原因**；文本类型必须原样保留
 * （{@code serialNo} 这类编号不许被转成数字）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class TextFrameValueDecoderTest {

    private static final String TYPE_DECIMAL = "decimal";

    private static final String TYPE_INT = "int";

    private static final String TYPE_BOOL = "bool";

    private static final String TYPE_STRING = "string";

    private final TextFrameValueDecoder decoder = new TextFrameValueDecoder();

    @Test
    @DisplayName("只处理 tcp：其它协议码一律不认领（否则会去解 Modbus/OPC UA 已解好的值）")
    void supportsOnlyTcp() {
        assertThat(decoder.supports("tcp")).isTrue();
        assertThat(decoder.supports("modbus")).isFalse();
        assertThat(decoder.supports("mqtt")).isFalse();
        assertThat(decoder.supports(null)).isFalse();
    }

    @Test
    @DisplayName("★ 数值点：TEMP=23.5 → BigDecimal 23.5（字符串形态是干净数值 ⇒ 落 value_double 而非文本列）")
    void decimalFrameShouldDecodeToCanonicalNumericValue() {
        DecodeOutcome outcome = decoder.decode(frame("TEMP=23.5,SEQ=12"), "TEMP", TYPE_DECIMAL);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(new BigDecimal("23.5"));
        // 这条断言就是「曲线能画出来」的根：出口用 String.valueOf(value)，形态必须是纯数值串
        assertThat(String.valueOf(outcome.value())).isEqualTo("23.5").matches("-?\\d+(\\.\\d+)?");
    }

    @Test
    @DisplayName("整型点：HUM=45 → Long 45（纯十进制，超 long 范围不截断而是失败）")
    void integerFrameShouldDecodeToLong() {
        assertThat(decoder.decode(frame("HUM=45"), "HUM", TYPE_INT).value()).isEqualTo(45L);
        assertThat(decoder.decode(frame("HUM=45.6"), "HUM", TYPE_INT).failure())
            .isEqualTo(DecodeFailure.NOT_NUMERIC);
        assertThat(decoder.decode(frame("HUM=99999999999999999999"), "HUM", TYPE_INT).failure())
            .as("超出 long 范围必须失败，不得静默截断").isEqualTo(DecodeFailure.NOT_NUMERIC);
    }

    @Test
    @DisplayName("布尔点：true/false（忽略大小写）与 1/0 统一规范化；其它形态失败")
    void booleanFrameShouldBeCanonicalized() {
        assertThat(decoder.decode(frame("SW=TRUE"), "SW", TYPE_BOOL).value()).isEqualTo("true");
        assertThat(decoder.decode(frame("SW=false"), "SW", TYPE_BOOL).value()).isEqualTo("false");
        assertThat(decoder.decode(frame("SW=1"), "SW", TYPE_BOOL).value()).isEqualTo("true");
        assertThat(decoder.decode(frame("SW=0"), "SW", TYPE_BOOL).value()).isEqualTo("false");
        assertThat(decoder.decode(frame("SW=ON"), "SW", TYPE_BOOL).failure())
            .isEqualTo(DecodeFailure.NOT_BOOLEAN);
    }

    @Test
    @DisplayName("★ 文本点：serialNo 原样保留（含前导零），绝不被「顺手转成数字」")
    void textFrameMustStayText() {
        assertThat(decoder.decode(frame("SN=007"), "SN", TYPE_STRING).value()).isEqualTo("007");
        assertThat(decoder.decode(frame("SN=SN-0001"), "SN", TYPE_STRING).value())
            .isEqualTo("SN-0001");
        // enum/date_time/json_object/array 也归文本：不做隐式转换
        assertThat(decoder.decode(frame("MODE=cool"), "MODE", "enum").value()).isEqualTo("cool");
        assertThat(decoder.decode(frame("D=2026-09-27"), "D", "date_time").value())
            .isEqualTo("2026-09-27");
    }

    @Test
    @DisplayName("帧解析：键大小写不敏感/两侧空白忽略；, ; 与换行都算分隔符；重复键先出现者生效")
    void frameParsingShouldBeDeterministic() {
        assertThat(decoder.decode(frame("temp = 23.5 , SEQ=1"), "TEMP", TYPE_DECIMAL).value())
            .isEqualTo(new BigDecimal("23.5"));
        assertThat(decoder.decode(frame("A=1;B=2"), "B", TYPE_INT).value()).isEqualTo(2L);
        assertThat(decoder.decode(frame("A=1\nB=2\n"), "B", TYPE_INT).value()).isEqualTo(2L);
        assertThat(decoder.decode(frame("TEMP=1,TEMP=2"), "TEMP", TYPE_INT).value())
            .as("重复键取先出现者：字段顺序抖动不得改变取值").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 帧里没有该点位的键 ⇒ KEY_NOT_FOUND（点位地址配错，丢弃+计数而不是拿别的值顶替）")
    void missingKeyMustFail() {
        DecodeOutcome outcome = decoder.decode(frame("TEMP=23.5,SEQ=1"), "HUM", TYPE_DECIMAL);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failure()).isEqualTo(DecodeFailure.KEY_NOT_FOUND);
        assertThat(outcome.value()).isNull();
    }

    @Test
    @DisplayName("★ 长度不足（空载荷 / 空值）与帧格式不符：各自具名失败，绝不抛异常")
    void insufficientOrMalformedPayloadMustFailNamed() {
        assertThat(decoder.decode(new byte[0], "TEMP", TYPE_DECIMAL).failure())
            .isEqualTo(DecodeFailure.EMPTY_PAYLOAD);
        assertThat(decoder.decode(frame("TEMP="), "TEMP", TYPE_DECIMAL).failure())
            .isEqualTo(DecodeFailure.EMPTY_VALUE);
        assertThat(decoder.decode(frame("23.5"), "TEMP", TYPE_DECIMAL).failure())
            .as("没有 KEY=VALUE 字段的帧不是本约定的帧格式").isEqualTo(DecodeFailure.NOT_KEY_VALUE_FRAME);
    }

    @Test
    @DisplayName("★ 二进制寄存器帧（大小端字节序列）走不了文本解码 ⇒ NOT_UTF8（不得产出 U+FFFD 脏文本）")
    void binaryRegisterFrameMustFailAsNotUtf8() {
        // 0xFF/0xFE 是 UTF-8 中永不出现的字节（单字节控制区间里 0x00~0x17 反而**是**合法 UTF-8，
        // 别用它们当"二进制帧"——那样命中的会是「帧格式不符」而不是本用例要守的 NOT_UTF8）
        byte[] registerFrame = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x02};
        DecodeOutcome outcome = decoder.decode(registerFrame, "holding:0", TYPE_DECIMAL);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failure()).isEqualTo(DecodeFailure.NOT_UTF8);
    }

    @Test
    @DisplayName("★ 类型缺失/未知 ⇒ UNKNOWN_DATA_TYPE（不猜数值还是文本）")
    void unknownDataTypeMustFail() {
        assertThat(decoder.decode(frame("TEMP=23.5"), "TEMP", null).failure())
            .isEqualTo(DecodeFailure.UNKNOWN_DATA_TYPE);
        assertThat(decoder.decode(frame("TEMP=23.5"), "TEMP", "double").failure())
            .as("物模型 9 类之外的 code 一律不认（double 不在 9 类里，decimal 才是）")
            .isEqualTo(DecodeFailure.UNKNOWN_DATA_TYPE);
    }

    @Test
    @DisplayName("非字节载荷原样透传：Modbus 数值 / MQTT 已解码文本不受本层影响")
    void nonBytePayloadMustPassThrough() {
        assertThat(decoder.decode(42, "holding:1", TYPE_INT).value()).isEqualTo(42);
        assertThat(decoder.decode("ON", "some/topic", TYPE_STRING).value()).isEqualTo("ON");
        assertThat(decoder.decode(null, "holding:1", TYPE_INT).value()).isNull();
    }

    private static byte[] frame(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
