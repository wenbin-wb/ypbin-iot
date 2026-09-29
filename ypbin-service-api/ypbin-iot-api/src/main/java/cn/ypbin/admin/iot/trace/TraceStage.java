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
 * 消息跟踪的**链路阶段**（设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.3）。
 *
 * <p><b>⚠️ 这里只列"能从既有数据推导出来"的阶段。</b>设计 §3.3 明确：</p>
 * <ul>
 *   <li><b>`UP_PERSISTED` 不在此列</b>：不存在独立的"已落库"记录——回执行的存在
 *       ⟺ 已受理 ⟺ 已落库，三者是同一件事，无法区分（真正落库出口是 IoTDB/Redis，
 *       不写 MySQL）。见设计 §3.3.2。</li>
 *   <li><b>`UP_DISCARDED` 不在此列</b>：整批被拒时链路**刻意不写回执**
 *       （{@code MqttReadingIngestServiceImpl#apply}），被丢弃的批在库中**零痕迹**。
 *       见设计 §3.3.1。</li>
 * </ul>
 *
 * <p>⇒ 这两个断点一期**不呈现**。要呈现须走二批补齐写入侧字段——**在此之前不许造一个假状态** ✗
 * （本枚举的取值就是这条约束的落地形式：没有对应取值，就没有地方能塞假数据）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum TraceStage {

    /** 下发已受理（`iot_command_instance` 行的 `create_time`；插入时即 `status_code=pending`）。 */
    DOWN_ENQUEUED("down-enqueued", "下发已受理"),

    /** 已成功投递到 EMQX（`sent_at` 非空；语义是"投递**成功**"，不是"已尝试"）。 */
    DOWN_PUBLISHED("down-published", "已投递到 EMQX"),

    /** 设备回执（`finished_at` 非空且进了终态）。 */
    DOWN_ACK("down-ack", "设备回执"),

    /** 上行入站受理（`iot_mqtt_ingest_receipt` 行；**仅 MQTT 通道**，HTTP 出口不写回执）。 */
    UP_RECEIVED("up-received", "上行已受理"),

    /** 设备事件上报（`iot_event_log` 行）。 */
    EVENT_REPORTED("event-reported", "设备事件"),

    /** 设备离线/断档（`outage_event` 行）。 */
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
            case UP_RECEIVED, EVENT_REPORTED -> TraceDirection.UP;
            case DEVICE_OFFLINE -> TraceDirection.INTERNAL;
        };
    }
}
