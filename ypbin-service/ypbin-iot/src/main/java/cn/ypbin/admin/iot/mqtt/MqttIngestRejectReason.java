/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mqtt;

/**
 * MQTT 入站整批拒绝的原因码。
 *
 * <p>每一个码都同时是「给 EMQX 看的 4xx 理由」与「给运维看的定位线索」：EMQX 只认状态码，
 * 但运维需要知道为什么被拒。码值进响应体（不含任何输入原文，避免把不可信内容回显成日志注入面），
 * 细节进服务端日志（经 {@code LogSanitizer} 脱敏）。</p>
 *
 * <p><b>为什么单独枚举而不是复用既有异常码</b>：本仓统一异常码（{@code GlobalErrorCode}）是给
 * 「HTTP 200 + R.code」信封用的，而本端点按设计 D2 破例返回真状态码；两者混用会让「哪些码是
 * 真的 HTTP 语义」再次说不清。这里只服务破例范围内的对端（EMQX）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public enum MqttIngestRejectReason {

    /** 请求体不是合法 JSON。 */
    INVALID_JSON("INVALID_JSON", "请求体不是合法 JSON"),

    /** 请求体超过上限。 */
    BODY_TOO_LARGE("BODY_TOO_LARGE", "请求体超过上限"),

    /** 缺少 requestId 或形态非法。 */
    REQUEST_ID_INVALID("REQUEST_ID_INVALID", "requestId 缺失或形态非法"),

    /** 读数清单为空。 */
    ITEMS_EMPTY("ITEMS_EMPTY", "读数清单为空"),

    /** 读数条数超过单消息上限。 */
    ITEMS_TOO_MANY("ITEMS_TOO_MANY", "单条消息的读数条数超过上限"),

    /** 单条读数为空或结构非法（如 JSON 数组里出现 null 元素）。 */
    ITEM_INVALID("ITEM_INVALID", "读数条目为空或结构非法"),

    /** 设备 ID 缺失/非正/同一消息里混了多台设备。 */
    DEVICE_ID_INVALID("DEVICE_ID_INVALID", "设备 ID 缺失、非法或一条消息混了多台设备"),

    /** 点位标识形态非法（字符集/长度）。 */
    PROPERTY_ID_INVALID("PROPERTY_ID_INVALID", "点位标识形态非法"),

    /** 质量码不在白名单内。 */
    QUALITY_INVALID("QUALITY_INVALID", "质量码不在白名单内"),

    /** 读数值缺失或过长。 */
    VALUE_INVALID("VALUE_INVALID", "读数值缺失或过长"),

    /** 读数时刻缺失/非正/超出允许的时钟偏移。 */
    TS_INVALID("TS_INVALID", "读数时刻缺失、非正或超出允许的时钟偏移"),

    /** 采集周期缺失/非正/过大。 */
    POLL_INTERVAL_INVALID("POLL_INTERVAL_INVALID", "采集周期缺失、非正或过大"),

    /** 报文声明的设备不存在（或不在任何租户）。 */
    DEVICE_NOT_FOUND("DEVICE_NOT_FOUND", "设备不存在"),

    /** 整批读数都被落库链路的点位映射校验拒绝（未映射/孤儿映射），没有一条可用。 */
    NO_ACCEPTED_ITEM("NO_ACCEPTED_ITEM", "整批读数都未通过点位映射校验");

    private final String code;

    private final String desc;

    MqttIngestRejectReason(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明（面向人，不含输入原文）。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }
}
