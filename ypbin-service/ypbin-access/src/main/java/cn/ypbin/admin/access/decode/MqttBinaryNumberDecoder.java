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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参考实现：MQTT {@code payload-format=binary} 的**定宽数值**解码（有符号/无符号整数、IEEE754 浮点）。
 *
 * <p><b>适用边界（不凭空发明工业协议）</b>：本解码器只处理「负载本身就是一个定宽标量」这一最常见形态
 * （长度 {@code 1/2/4/8} 字节、大小端、整数/无符号整数/浮点）。它不是通用协议解析器：CBOR/protobuf/
 * 自定义帧、多字段负载、BCD、字内字节交换（BADC/CDAB）等一律<b>不认领或显式失败</b>，
 * 需要时由业务侧另注册 {@link BytePayloadDecoder} bean。</p>
 *
 * <p><b>认领规则（可配置，见 {@link AccessDecodeProperties}）</b>：</p>
 * <ol>
 *   <li>{@code ypbin.access.decode.mqtt-binary.enabled=false}（默认）⇒ <b>不认领任何点位</b>
 *       ⇒ 行为与「未注册解码器」逐字一致；</li>
 *   <li>协议必须是 {@code mqtt}；</li>
 *   <li>{@code points[raw_address]} 命中则用它；否则用 {@code default-spec}；两者都没有 ⇒ 不认领；</li>
 *   <li>认领后字节长度必须等于声明长度，否则仍是显式失败（{@code length-mismatch}）——
 *       不补零、不截断。</li>
 * </ol>
 *
 * <p><b>产出形态（交给宿主既有规范化链路）</b>：有符号/无符号整数 ⇒ {@code Long}
 * （8 字节无符号用 {@link BigInteger} 保精度）；float32 ⇒ {@code new BigDecimal(Float.toString(f))}、
 * float64 ⇒ {@code BigDecimal.valueOf(d)}（最短可回读表示，理由同 {@code VALUE-DECODE-DESIGN.md} §4.4.7）；
 * NaN/±Inf ⇒ 显式失败 {@link DecodeFailure#NOT_FINITE}，不落库。</p>
 *
 * <p><b>失败即显式</b>：解码失败返回 {@link DecodeOutcome#failed(DecodeFailure)}，由
 * {@code PointMappingDataListener} 产出 BAD 质量 + {@code iot.access.payload.decode.failure} 计数 +
 * WARN（不 dump 负载内容）。本类自身不抛异常（除非实现被改坏，由调用方兜底为 {@code decoder-error}）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class MqttBinaryNumberDecoder implements BytePayloadDecoder {

    /** 解码器标识（稳定，日志/排障用）。 */
    public static final String ID = "mqtt-binary-number";

    private final AccessDecodeProperties.MqttBinary properties;

    /**
     * 构造并**在启动期自检配置**（enabled=true 时非法规格直接拒绝启动，不静默忽略）。
     *
     * @param properties MQTT binary 解码配置（可空 ⇒ 等价于未启用）
     */
    public MqttBinaryNumberDecoder(AccessDecodeProperties.MqttBinary properties) {
        this.properties = properties == null ? new AccessDecodeProperties.MqttBinary() : properties;
        validate(this.properties);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(DecodeContext context) {
        if (context == null || !properties.isEnabled()) {
            return false;
        }
        if (!IotProtocol.MQTT.getCode().equals(context.protocol())) {
            return false;
        }
        return specFor(context.rawAddress()) != null;
    }

    @Override
    public DecodeOutcome decode(byte[] payload, DecodeContext context) {
        if (payload == null) {
            return DecodeOutcome.failed(DecodeFailure.INVALID_VALUE);
        }
        AccessDecodeProperties.NumberSpec spec = specFor(context == null ? null : context.rawAddress());
        if (spec == null) {
            return DecodeOutcome.failed(DecodeFailure.CONFIG_MISSING);
        }
        int expected = spec.getLength();
        if (payload.length != expected) {
            // 不补零、不截断：长度不符是可定位的配置/设备问题，不是可猜的容错
            return DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH);
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload)
            .order(spec.getByteOrder() == AccessDecodeProperties.Endianness.LITTLE
                ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        return switch (spec.getNumberType()) {
            case INT -> DecodeOutcome.ok(readSigned(buffer, expected));
            case UINT -> DecodeOutcome.ok(readUnsigned(buffer, payload, spec, expected));
            case FLOAT -> readFloat(buffer, expected);
        };
    }

    /**
     * 该地址命中的解码规格：先精确匹配 {@code points}，否则回退 {@code default-spec}。
     *
     * @param rawAddress 协议原始地址（MQTT 主题）
     * @return 规格；都不命中返回 {@code null}
     */
    private AccessDecodeProperties.NumberSpec specFor(String rawAddress) {
        if (rawAddress != null) {
            AccessDecodeProperties.NumberSpec mapped = properties.getPoints().get(rawAddress);
            if (mapped != null) {
                return mapped;
            }
        }
        return properties.getDefaultSpec();
    }

    /** 有符号整数：按声明长度读二进制补码（1/2/4/8）。 */
    private static long readSigned(ByteBuffer buffer, int length) {
        return switch (length) {
            case 1 -> buffer.get();
            case 2 -> buffer.getShort();
            case 4 -> buffer.getInt();
            default -> buffer.getLong();
        };
    }

    /**
     * 无符号整数：1/2/4 字节用掩码升为 {@code long}（不出现负数），8 字节用 {@link BigInteger} 保 64 位精度。
     */
    private static Object readUnsigned(ByteBuffer buffer, byte[] payload,
                                       AccessDecodeProperties.NumberSpec spec, int length) {
        if (length == 8) {
            // long 装不下 2^64-1；BigInteger(1, ...) 按无符号解释，字节序需先归一为高字节在前
            return new BigInteger(1, spec.getByteOrder() == AccessDecodeProperties.Endianness.LITTLE
                ? reversed(payload) : payload);
        }
        return switch (length) {
            case 1 -> buffer.get() & 0xFFL;
            case 2 -> buffer.getShort() & 0xFFFFL;
            default -> buffer.getInt() & 0xFFFFFFFFL;
        };
    }

    /** 浮点：4 字节 IEEE754 单精度 / 8 字节双精度；NaN/±Inf 按显式失败，不落库。 */
    private static DecodeOutcome readFloat(ByteBuffer buffer, int length) {
        if (length == 4) {
            float value = Float.intBitsToFloat(buffer.getInt());
            if (!Float.isFinite(value)) {
                return DecodeOutcome.failed(DecodeFailure.NOT_FINITE);
            }
            // float32 取最短可回读十进制（不用 double 展开的 17 位噪声，见 §4.4.7）
            return DecodeOutcome.ok(new BigDecimal(Float.toString(value)));
        }
        double value = Double.longBitsToDouble(buffer.getLong());
        if (!Double.isFinite(value)) {
            return DecodeOutcome.failed(DecodeFailure.NOT_FINITE);
        }
        return DecodeOutcome.ok(BigDecimal.valueOf(value));
    }

    /** 小端 8 字节无符号的字节序归一（BigInteger 只按高字节在前解释）。 */
    private static byte[] reversed(byte[] payload) {
        byte[] result = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            result[i] = payload[payload.length - 1 - i];
        }
        return result;
    }

    /** 启动期配置自检：enabled=true 时至少一个规格，且每个规格都合法（失败即拒绝启动）。 */
    private static void validate(AccessDecodeProperties.MqttBinary props) {
        if (!props.isEnabled()) {
            return;
        }
        List<String> issues = new ArrayList<>();
        if (props.getDefaultSpec() == null && props.getPoints().isEmpty()) {
            issues.add("enabled=true 但 default-spec 与 points 都为空（至少声明一个解码规格）");
        }
        if (props.getDefaultSpec() != null) {
            validateSpec("default-spec", props.getDefaultSpec(), issues);
        }
        for (Map.Entry<String, AccessDecodeProperties.NumberSpec> entry : props.getPoints().entrySet()) {
            if (entry.getValue() == null) {
                issues.add("points." + entry.getKey() + " 未给出规格");
            } else {
                validateSpec("points." + entry.getKey(), entry.getValue(), issues);
            }
        }
        if (!issues.isEmpty()) {
            throw new IllegalStateException("[access] MQTT binary 解码配置自检未通过（"
                + AccessDecodeProperties.PREFIX + ".mqtt-binary）：" + String.join("；", issues));
        }
    }

    /** 单个规格自检：类型/长度/字节序必填且组合合法，错误文案可执行。 */
    private static void validateSpec(String path, AccessDecodeProperties.NumberSpec spec,
                                     List<String> issues) {
        if (spec.getNumberType() == null) {
            issues.add(path + ".number-type 必填（int|uint|float）");
            return;
        }
        if (spec.getLength() == null) {
            issues.add(path + ".length 必填（int/uint 1/2/4/8；float 4/8）");
            return;
        }
        int length = spec.getLength();
        switch (spec.getNumberType()) {
            case INT, UINT -> {
                if (length != 1 && length != 2 && length != 4 && length != 8) {
                    issues.add(path + ".length=" + length + " 非法（int/uint 支持 1/2/4/8）");
                }
            }
            case FLOAT -> {
                if (length != 4 && length != 8) {
                    issues.add(path + ".length=" + length + " 非法（float 支持 4/8）");
                }
            }
        }
        if (spec.getByteOrder() == null) {
            issues.add(path + ".byte-order 必填（big|little）");
        }
    }
}
