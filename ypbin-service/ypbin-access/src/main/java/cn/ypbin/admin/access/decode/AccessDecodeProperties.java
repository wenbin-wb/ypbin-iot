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

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 宿主字节载荷解码参数（前缀 {@code ypbin.access.decode}）。
 *
 * <p>当前只有参考实现 {@link MqttBinaryNumberDecoder} 的配置（MQTT
 * {@code payload-format=binary} 定宽数值）。<b>默认关闭</b>：不配就等价于「未注册解码器」，
 * {@code byte[]} 仍被出口闸门丢弃并计 {@code iot.access.egress.invalid}（与今天逐字一致）。</p>
 *
 * <p>配置示例（Nacos {@code ypbin-access.yaml}）：</p>
 * <pre>{@code
 * ypbin:
 *   access:
 *     decode:
 *       mqtt-binary:
 *         enabled: true
 *         default-spec:
 *           number-type: uint
 *           length: 2
 *           byte-order: big
 *         points:
 *           sensors/temp:
 *             number-type: float
 *             length: 4
 *             byte-order: little
 * }</pre>
 *
 * <p>{@code points} 按 {@code raw_address}（MQTT 下是实际主题）精确匹配并覆盖
 * {@code default-spec}；两者都不命中 ⇒ 该点位不被认领 ⇒ 落回出口闸门。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AccessDecodeProperties.PREFIX)
public class AccessDecodeProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.access.decode";

    /** MQTT binary 定宽数值解码配置。 */
    private MqttBinary mqttBinary = new MqttBinary();

    /**
     * MQTT binary 解码配置。
     *
     * @author wenbin
     * @since 2026-10-13
     */
    @Getter
    @Setter
    public static class MqttBinary {

        /** 是否启用（默认 false ⇒ 不注册任何 MQTT binary 解码能力）。 */
        private boolean enabled = false;

        /** 默认解码规格（未在 {@link #points} 单独声明的点位用它）。 */
        private NumberSpec defaultSpec;

        /** 按 {@code raw_address}（MQTT 主题）覆盖的解码规格。 */
        private Map<String, NumberSpec> points = new LinkedHashMap<>();
    }

    /**
     * 一个定宽数值解码规格：数值类型 + 字节长度 + 字节序。
     *
     * @author wenbin
     * @since 2026-10-13
     */
    @Getter
    @Setter
    public static class NumberSpec {

        /** 数值类型（必填）。 */
        private NumberType numberType;

        /** 字节长度（必填；int/uint 支持 1/2/4/8，float 支持 4/8）。 */
        private Integer length;

        /** 字节序（缺省 big）。 */
        private Endianness byteOrder = Endianness.BIG;
    }

    /**
     * 数值类型。
     *
     * @author wenbin
     * @since 2026-10-13
     */
    public enum NumberType {

        /** 有符号整数（two's complement）。 */
        INT("int", "有符号整数"),

        /** 无符号整数。 */
        UINT("uint", "无符号整数"),

        /** IEEE754 浮点（4 字节 float32 / 8 字节 float64）。 */
        FLOAT("float", "浮点");

        private final String code;

        private final String desc;

        NumberType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        /**
         * 编码。
         *
         * @return 编码
         */
        public String getCode() {
            return code;
        }

        /**
         * 描述。
         *
         * @return 描述
         */
        public String getDesc() {
            return desc;
        }
    }

    /**
     * 字节序（避免与 {@code java.nio.ByteOrder} 同名歧义，故叫 Endianness）。
     *
     * @author wenbin
     * @since 2026-10-13
     */
    public enum Endianness {

        /** 高字节在前。 */
        BIG("big", "高字节在前"),

        /** 低字节在前。 */
        LITTLE("little", "低字节在前");

        private final String code;

        private final String desc;

        Endianness(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        /**
         * 编码。
         *
         * @return 编码
         */
        public String getCode() {
            return code;
        }

        /**
         * 描述。
         *
         * @return 描述
         */
        public String getDesc() {
            return desc;
        }
    }
}
