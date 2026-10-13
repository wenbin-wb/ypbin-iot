/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

/**
 * 消息跟踪的**链路阶段**（设计 {@code docs/MESSAGE-TRACE-DESIGN.md} §3.3 与附二批）。
 *
 * <p><b>一期只列"能从既有数据推导出来"的阶段</b>：{@code UP_PERSISTED}（不存在独立的"已落库"
 * 记录，见设计 §3.3.2）与 {@code UP_DISCARDED}（被丢弃的批在库中零痕迹，见设计 §3.3.1）
 * 一期**结构性不可得**，因此当时不在此枚举里——没有取值就没有地方塞假数据。</p>
 *
 * <p><b>消息跟踪二批（已实施）补齐写入侧字段后两者可呈现</b>：
 * {@link #UP_PERSISTED} 由 {@code iot_mqtt_ingest_receipt.persisted_at != null} 推导；
 * {@link #UP_DISCARDED} 由 {@code receipt_status = rejected} 推导。二者仍遵循 §3.3.3 的
 * 「一行 = 一条目」纪律（一条回执行只产生一个条目，不展开多余刻度）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum TraceStage {

    /** 下发已受理（{@code iot_command_instance} 行的 {@code create_time}；插入时即 {@code status_code=pending}）。 */
    DOWN_ENQUEUED("down-enqueued", "下发已受理"),

    /** 已成功投递到 EMQX（{@code sent_at} 非空；语义是"投递**成功**"，不是"已尝试"）。 */
    DOWN_PUBLISHED("down-published", "已投递到 EMQX"),

    /** 设备回执（{@code finished_at} 非空且进了终态）。 */
    DOWN_ACK("down-ack", "设备回执"),

    /** 上行入站受理（{@code iot_mqtt_ingest_receipt} 行；**仅 MQTT 通道**，HTTP 出口不写回执）。 */
    UP_RECEIVED("up-received", "上行已受理"),

    /** 上行整批已落库（二批：回执行的 {@code persisted_at} 非空；展示"已落库 N/M 条"）。 */
    UP_PERSISTED("up-persisted", "上行已落库"),

    /** 上行整批被拒（二批：回执行的 {@code receipt_status = rejected}；原因见 {@code discard_reason}）。 */
    UP_DISCARDED("up-discarded", "上行被丢弃"),

    /** 设备事件上报（{@code iot_event_log} 行）。 */
    EVENT_REPORTED("event-reported", "设备事件"),

    /** 设备离线/断档（{@code outage_event} 行）。 */
    DEVICE_OFFLINE("device-offline", "设备离线");

    private final String code;

    private final String desc;

    TraceStage(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 阶段码（**接口与前端一律用它，绝不用 ordinal**）。
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

    /**
     * 方向：上行（设备→平台）还是下行（平台→设备）。
     *
     * @return 方向码
     */
    public TraceDirection getDirection() {
        return switch (this) {
            case DOWN_ENQUEUED, DOWN_PUBLISHED, DOWN_ACK -> TraceDirection.DOWN;
            case UP_RECEIVED, UP_PERSISTED, UP_DISCARDED, EVENT_REPORTED -> TraceDirection.UP;
            case DEVICE_OFFLINE -> TraceDirection.INTERNAL;
        };
    }
}
