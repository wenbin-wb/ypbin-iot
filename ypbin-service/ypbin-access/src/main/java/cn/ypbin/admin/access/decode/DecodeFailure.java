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
 * 读数解码失败原因（丢弃该读数时**必须**能被指标与日志区分开）。
 *
 * <p>为什么要把原因枚举化：采集侧的解码失败只有「丢了多少条」是**不可运维**的——现场需要一眼看出
 * 是「帧格式不对」「键配错了」还是「物模型类型没配」，否则只能去猜。枚举的 {@code code} 直接做
 * 指标 {@code iot.access.decode.failure} 的 {@code reason} 标签值，取值集合有界（不会打爆时序库）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public enum DecodeFailure {

    /** 载荷长度为 0（设备发了空帧）。 */
    EMPTY_PAYLOAD("empty-payload", "载荷为空"),

    /** 载荷不是合法 UTF-8 文本（二进制/寄存器帧走不了文本解码）。 */
    NOT_UTF8("not-utf8", "载荷不是合法 UTF-8 文本"),

    /** 文本帧里没有任何 {@code KEY=VALUE} 字段（帧格式与配置的解码方式不符）。 */
    NOT_KEY_VALUE_FRAME("not-key-value-frame", "帧不是 KEY=VALUE 文本格式"),

    /** 帧里没有该点位声明的键（点位地址配错，或设备没发这个点）。 */
    KEY_NOT_FOUND("key-not-found", "帧中没有该点位声明的键"),

    /** 键在帧里，但值为空（如 {@code TEMP=}）。 */
    EMPTY_VALUE("empty-value", "键对应的值为空"),

    /** 值不是该属性数据类型要求的数值形态（含整数越界）。 */
    NOT_NUMERIC("not-numeric", "值不是合法数值"),

    /** 值不是可识别的布尔形态（{@code true/false/1/0}）。 */
    NOT_BOOLEAN("not-boolean", "值不是布尔形态"),

    /** 物模型属性数据类型缺失或不在已知 9 类之内 ⇒ 不猜，丢弃。 */
    UNKNOWN_DATA_TYPE("unknown-data-type", "属性数据类型缺失或未知");

    private final String code;

    private final String desc;

    DecodeFailure(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码（指标标签值与日志用；小写连字符，稳定标识，改文案不改它）。
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
