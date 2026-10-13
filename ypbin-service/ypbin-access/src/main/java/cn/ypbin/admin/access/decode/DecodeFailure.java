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

/**
 * 宿主字节载荷解码失败原因（指标 {@code iot.access.payload.decode.failure} 的 {@code reason} 标签值）。
 *
 * <p>取值集合有界（不会打爆时序库），且与 {@code NormalizeFailure}（物模型类型规范化失败）分工明确：
 * 本枚举覆盖「原始字节 → 规范值」这一段（协议负载形态问题），{@link cn.ypbin.admin.access.egress.NormalizeFailure}
 * 覆盖「规范值 → 物模型类型」那一段（类型语义问题）。两段都可能失败，但原因不混计。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public enum DecodeFailure {

    /** 字节长度与声明的数值类型/位宽不匹配（不补零、不截断）。 */
    LENGTH_MISMATCH("length-mismatch", "字节长度与声明的解码规格不匹配"),

    /** 按声明形态解释出的浮点是 NaN/±Inf（不是有效测量，不落库）。 */
    NOT_FINITE("not-finite", "浮点值为 NaN/±Inf"),

    /** 字节串无法按声明的数值类型解释（含实现自身的格式约束不满足）。 */
    INVALID_VALUE("invalid-value", "字节串无法按声明的规格解释"),

    /** 该点位没有可用的解码规格（解码器被直接调用或配置缺失）。 */
    CONFIG_MISSING("config-missing", "该点位没有可用的解码配置"),

    /** 解码器抛出异常（实现缺陷；宿主捕获后显式暴露，不吞异常）。 */
    DECODER_ERROR("decoder-error", "解码器抛出异常（实现缺陷，需修复）");

    private final String code;

    private final String desc;

    DecodeFailure(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码（指标标签值与日志用；稳定标识，改文案不改它）。
     *
     * @return 原因码
     */
    public String getCode() {
        return code;
    }

    /**
     * 原因描述（面向运维的中文说明）。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }
}
