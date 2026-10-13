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

/**
 * **宿主物模型类型规范化**失败原因（丢弃该读数时**必须**能被指标与日志区分开）。
 *
 * <p><b>与 SDK 失败的分工</b>：帧解析/字段选择失败（非 UTF-8、无键值字段、selector 未命中）由
 * starter 的 {@code TcpValueSelector} 以 <b>BAD 质量</b>显式表达，并计入 SDK 侧
 * {@code iot.tcp.*} 指标，宿主原样穿透、不再重复计数；本枚举只覆盖 <b>SDK 不负责</b>的那一段——
 * 「帧里取到的文本不是物模型 dataType 要求的形态」（SDK 类注释明确：类型属于宿主物模型语义）。</p>
 *
 * <p>原因码做指标 {@code iot.access.value.normalize.failure} 的 {@code reason} 标签值，取值集合有界
 * （不会打爆时序库）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public enum NormalizeFailure {

    /** 值不是该属性数据类型要求的数值形态（含整数越界）。 */
    NOT_NUMERIC("not-numeric", "值不是合法数值"),

    /** 值不是可识别的布尔形态（{@code true/false/1/0}）。 */
    NOT_BOOLEAN("not-boolean", "值不是布尔形态"),

    /** 物模型属性数据类型缺失或不在已知 9 类之内 ⇒ 不猜，丢弃。 */
    UNKNOWN_DATA_TYPE("unknown-data-type", "属性数据类型缺失或未知");

    private final String code;

    private final String desc;

    NormalizeFailure(String code, String desc) {
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
