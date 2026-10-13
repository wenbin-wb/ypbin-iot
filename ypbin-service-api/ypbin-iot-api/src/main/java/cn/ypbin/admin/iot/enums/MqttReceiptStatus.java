/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * MQTT 入站回执的**行性质**（消息跟踪二批；对应 {@code iot_mqtt_ingest_receipt.receipt_status}）。
 *
 * <p><b>为什么另起一个枚举而不是复用既有 {@code status}</b>：表里的 {@code status TINYINT} 是通用的
 * 「启用/停用」列（{@code insertReceipt} 硬编码 1）。二批要表达的是「这一批被受理了还是被整批拒绝」，
 * 与启用/停用是两个正交维度，混用会让「停用」与「拒绝」再也分不清。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public enum MqttReceiptStatus {

    /** 本批已受理（回执落库成功；可能伴随部分读数被丢弃，见 {@code persisted_rows}）。 */
    ACCEPTED("accepted", "已受理"),

    /** 本批被整批拒绝（{@code persisted_rows=0}，原因见 {@code discard_reason}）。 */
    REJECTED("rejected", "整批被拒");

    private final String code;

    private final String desc;

    MqttReceiptStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 行性质码（存库值与接口值一致，**不用 ordinal**）。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }
}
