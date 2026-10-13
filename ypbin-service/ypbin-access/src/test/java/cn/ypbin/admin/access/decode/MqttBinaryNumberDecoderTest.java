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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.access.decode.AccessDecodeProperties.Endianness;
import cn.ypbin.admin.access.decode.AccessDecodeProperties.NumberSpec;
import cn.ypbin.admin.access.decode.AccessDecodeProperties.NumberType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 参考解码器单测：MQTT binary 定宽数值（有符号/无符号/浮点 × 大小端 × 长度），
 * 以及配置自检（fail-fast，不静默忽略）。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class MqttBinaryNumberDecoderTest {

    private static final String TOPIC = "sensors/temp";

    @Test
    @DisplayName("id 稳定")
    void idIsStable() {
        assertThat(new MqttBinaryNumberDecoder(disabled()).id())
            .isEqualTo(MqttBinaryNumberDecoder.ID);
    }

    @Test
    @DisplayName("认领规则：未启用/非 MQTT/无规格/空上下文一律不认领")
    void supportsOnlyConfiguredMqttBinary() {
        assertThat(new MqttBinaryNumberDecoder(disabled())
            .supports(context("mqtt", TOPIC))).isFalse();
        assertThat(decoder(spec(NumberType.UINT, 2, Endianness.BIG), null)
            .supports(context("tcp", TOPIC))).isFalse();
        assertThat(decoder(spec(NumberType.UINT, 2, Endianness.BIG), null)
            .supports(null)).isFalse();
        // default-spec 存在 ⇒ 任意 mqtt 主题都认领
        assertThat(decoder(spec(NumberType.UINT, 2, Endianness.BIG), null)
            .supports(context("mqtt", TOPIC))).isTrue();
    }

    @Test
    @DisplayName("points 覆盖 default-spec；未命中且无 default ⇒ 不认领")
    void pointsOverrideDefault() {
        MqttBinaryNumberDecoder decoder = decoder(spec(NumberType.UINT, 2, Endianness.BIG),
            Map.of("other/topic", spec(NumberType.INT, 4, Endianness.LITTLE)));

        assertThat(decoder.supports(context("mqtt", TOPIC))).as("未在 points、但有 default ⇒ 认领").isTrue();
        assertThat(decoder.supports(context("mqtt", "other/topic"))).isTrue();
        // 无 default、points 未命中 ⇒ 不认领
        MqttBinaryNumberDecoder noDefault = decoder(null,
            Map.of("other/topic", spec(NumberType.INT, 4, Endianness.LITTLE)));
        assertThat(noDefault.supports(context("mqtt", TOPIC))).isFalse();
        // points 命中的位宽与 default 不同：按 points 解（4 字节 little int）
        DecodeOutcome outcome = noDefault.decode(bytes(0x01, 0x02, 0x03, 0x04),
            context("mqtt", "other/topic"));
        assertThat(outcome.value()).isEqualTo(0x04030201L);
    }

    @Test
    @DisplayName("有符号整数（INT）：8/16/32/64 位 × 大小端")
    void signedIntegers() {
        assertThat(decode(NumberType.INT, 1, Endianness.BIG, 0x80)).isEqualTo(-128L);
        assertThat(decode(NumberType.INT, 1, Endianness.LITTLE, 0x80)).isEqualTo(-128L);
        assertThat(decode(NumberType.INT, 2, Endianness.BIG, 0xFF, 0xFE)).isEqualTo(-2L);
        assertThat(decode(NumberType.INT, 2, Endianness.LITTLE, 0xFE, 0xFF)).isEqualTo(-2L);
        assertThat(decode(NumberType.INT, 4, Endianness.BIG, 0xFF, 0xFF, 0xFF, 0xFE))
            .isEqualTo(-2L);
        assertThat(decode(NumberType.INT, 4, Endianness.LITTLE, 0xFE, 0xFF, 0xFF, 0xFF))
            .isEqualTo(-2L);
        assertThat(decode(NumberType.INT, 8, Endianness.BIG,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE)).isEqualTo(-2L);
        assertThat(decode(NumberType.INT, 8, Endianness.LITTLE,
            0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)).isEqualTo(-2L);
    }

    @Test
    @DisplayName("无符号整数（UINT）：1/2/4 字节不出现负数；8 字节用 BigInteger 保精度")
    void unsignedIntegers() {
        assertThat(decode(NumberType.UINT, 1, Endianness.BIG, 0xFF)).isEqualTo(255L);
        assertThat(decode(NumberType.UINT, 2, Endianness.BIG, 0xFF, 0xFF)).isEqualTo(65535L);
        assertThat(decode(NumberType.UINT, 2, Endianness.LITTLE, 0xFF, 0xFF)).isEqualTo(65535L);
        assertThat(decode(NumberType.UINT, 4, Endianness.BIG,
            0xFF, 0xFF, 0xFF, 0xFF)).isEqualTo(4294967295L);
        // 2^64-1：long 装不下，必须保精度
        assertThat(decode(NumberType.UINT, 8, Endianness.BIG,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
            .isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(decode(NumberType.UINT, 8, Endianness.LITTLE,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).isEqualTo(BigInteger.ONE);
        assertThat(decode(NumberType.UINT, 8, Endianness.BIG,
            0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0))
            .isEqualTo(new BigInteger("1311768467463790320"));
    }

    @Test
    @DisplayName("浮点（FLOAT）：4 字节 float32 / 8 字节 float64 × 大小端，取最短可回读表示")
    void floatingPoint() {
        // 1.5f = 0x3FC00000
        assertThat(decode(NumberType.FLOAT, 4, Endianness.BIG, 0x3F, 0xC0, 0x00, 0x00))
            .isEqualTo(new BigDecimal("1.5"));
        assertThat(decode(NumberType.FLOAT, 4, Endianness.LITTLE, 0x00, 0x00, 0xC0, 0x3F))
            .isEqualTo(new BigDecimal("1.5"));
        // 23.4f：简短可回读形态，不是 double 的 17 位噪声
        float f234 = 23.4f;
        assertThat(decoder(spec(NumberType.FLOAT, 4, Endianness.BIG), null)
            .decode(bytesOfFloat(f234), context("mqtt", TOPIC)).value())
            .isEqualTo(new BigDecimal(Float.toString(f234)));
        // 1.5d = 0x3FF8000000000000
        assertThat(decode(NumberType.FLOAT, 8, Endianness.BIG,
            0x3F, 0xF8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)).isEqualTo(new BigDecimal("1.5"));
        assertThat(decode(NumberType.FLOAT, 8, Endianness.LITTLE,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF8, 0x3F)).isEqualTo(new BigDecimal("1.5"));
    }

    @Test
    @DisplayName("NaN/±Inf 是显式失败而非值（float32 与 float64）")
    void nonFiniteIsExplicitFailure() {
        // float32 NaN = 0x7FC00000；+Inf = 0x7F800000
        assertThat(decoder(spec(NumberType.FLOAT, 4, Endianness.BIG), null)
            .decode(bytes(0x7F, 0xC0, 0x00, 0x00), context("mqtt", TOPIC)).failure())
            .isEqualTo(DecodeFailure.NOT_FINITE);
        assertThat(decoder(spec(NumberType.FLOAT, 4, Endianness.BIG), null)
            .decode(bytes(0x7F, 0x80, 0x00, 0x00), context("mqtt", TOPIC)).failure())
            .isEqualTo(DecodeFailure.NOT_FINITE);
        // float64 NaN = 0x7FF8000000000000
        assertThat(decoder(spec(NumberType.FLOAT, 8, Endianness.BIG), null)
            .decode(bytes(0x7F, 0xF8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), context("mqtt", TOPIC))
            .failure()).isEqualTo(DecodeFailure.NOT_FINITE);
    }

    @Test
    @DisplayName("长度不符/空载荷/无规格 ⇒ 显式失败（不补零、不截断、不猜）")
    void explicitFailures() {
        MqttBinaryNumberDecoder decoder = decoder(spec(NumberType.UINT, 4, Endianness.BIG), null);
        assertThat(decoder.decode(bytes(0x00, 0x01), context("mqtt", TOPIC)).failure())
            .isEqualTo(DecodeFailure.LENGTH_MISMATCH);
        assertThat(decoder.decode(null, context("mqtt", TOPIC)).failure())
            .isEqualTo(DecodeFailure.INVALID_VALUE);
        // 有规格但都不命中本地址（default 为空、points 只覆盖别处）⇒ config-missing
        MqttBinaryNumberDecoder noDefault = decoder(null,
            Map.of("other/topic", spec(NumberType.INT, 1, Endianness.BIG)));
        assertThat(noDefault.decode(bytes(0x00), context("mqtt", TOPIC)).failure())
            .isEqualTo(DecodeFailure.CONFIG_MISSING);
        assertThat(noDefault.decode(bytes(0x00), null).failure())
            .isEqualTo(DecodeFailure.CONFIG_MISSING);
    }

    @Test
    @DisplayName("配置自检 fail-fast：无规格/类型缺失/长度缺失/长度非法/字节序缺失 ⇒ 拒绝构造")
    void invalidConfigurationIsRejectedAtStartup() {
        assertThatThrownBy(() -> new MqttBinaryNumberDecoder(enabled(null, Map.of())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("至少声明一个解码规格");

        assertThatThrownBy(() -> decoder(spec(null, 2, Endianness.BIG), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("number-type 必填");

        assertThatThrownBy(() -> decoder(spec(NumberType.INT, null, Endianness.BIG), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("length 必填");

        assertThatThrownBy(() -> decoder(spec(NumberType.INT, 3, Endianness.BIG), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("length=3 非法");

        assertThatThrownBy(() -> decoder(spec(NumberType.FLOAT, 2, Endianness.BIG), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("float 支持 4/8");

        NumberSpec noOrder = spec(NumberType.INT, 2, Endianness.BIG);
        noOrder.setByteOrder(null);
        assertThatThrownBy(() -> decoder(noOrder, null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("byte-order 必填");

        Map<String, NumberSpec> nullPoint = new LinkedHashMap<>();
        nullPoint.put("x", null);
        assertThatThrownBy(() -> new MqttBinaryNumberDecoder(enabled(null, nullPoint)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("points.x 未给出规格");
    }

    @Test
    @DisplayName("enabled=false 时完全不校验（未启用的残缺配置不应阻断启动）")
    void disabledConfigurationSkipsValidation() {
        MqttBinaryNumberDecoder decoder = new MqttBinaryNumberDecoder(disabled());
        assertThat(decoder.supports(context("mqtt", TOPIC))).isFalse();
    }

    private static Object decode(NumberType type, int length, Endianness order, int... bytes) {
        return decoder(spec(type, length, order), null)
            .decode(bytes(bytes), context("mqtt", TOPIC)).value();
    }

    private static byte[] bytesOfFloat(float value) {
        return ByteBuffer.allocate(4).putInt(Float.floatToIntBits(value)).array();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static DecodeContext context(String protocol, String address) {
        return new DecodeContext("100", protocol, address, "topic", "decimal", "big");
    }

    private static NumberSpec spec(NumberType type, Integer length, Endianness order) {
        NumberSpec spec = new NumberSpec();
        spec.setNumberType(type);
        spec.setLength(length);
        spec.setByteOrder(order);
        return spec;
    }

    private static AccessDecodeProperties.MqttBinary disabled() {
        AccessDecodeProperties.MqttBinary properties = new AccessDecodeProperties.MqttBinary();
        properties.setEnabled(false);
        return properties;
    }

    private static AccessDecodeProperties.MqttBinary enabled(NumberSpec defaultSpec,
                                                             Map<String, NumberSpec> points) {
        AccessDecodeProperties.MqttBinary properties = new AccessDecodeProperties.MqttBinary();
        properties.setEnabled(true);
        properties.setDefaultSpec(defaultSpec);
        if (points != null) {
            properties.setPoints(points);
        }
        return properties;
    }

    private static MqttBinaryNumberDecoder decoder(NumberSpec defaultSpec,
                                                   Map<String, NumberSpec> points) {
        return new MqttBinaryNumberDecoder(enabled(defaultSpec, points));
    }
}
