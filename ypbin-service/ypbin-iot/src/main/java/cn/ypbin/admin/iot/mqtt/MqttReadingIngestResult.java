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

import lombok.Getter;
import lombok.Setter;

/**
 * MQTT 入站受理结果（成功路径的响应体；失败路径是**真 HTTP 4xx/503**，不由本类承载）。
 *
 * <p><b>为什么把 {@code duplicated} 明确回给对端</b>：EMQX 只看状态码、不看响应体，所以这个字段
 * 不是给 EMQX 用的，而是给**排障与验收**用的——重投同一 {@code requestId} 时响应必须能自证
 * 「这次没有重复落库」（验收口径 ④）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@Getter
@Setter
public class MqttReadingIngestResult {

    /** 设备 ID。 */
    private Long deviceId;

    /** 设备侧请求 ID（幂等键）。 */
    private String requestId;

    /** 本次（或首次）受理的读数条数。 */
    private int accepted;

    /** 本批被落库链路丢弃的读数条数（点位未映射/孤儿映射等；成功路径下才可能非 0）。 */
    private int dropped;

    /** 是否为重复投递（true = 命中幂等回执，本次未调用落库链路）。 */
    private boolean duplicated;

    /**
     * 首次受理的成功结果。
     *
     * @param deviceId  设备 ID
     * @param requestId 请求 ID
     * @param accepted  受理条数
     * @param dropped   丢弃条数
     * @return 结果
     */
    public static MqttReadingIngestResult accepted(Long deviceId, String requestId, int accepted,
                                                   int dropped) {
        MqttReadingIngestResult result = new MqttReadingIngestResult();
        result.deviceId = deviceId;
        result.requestId = requestId;
        result.accepted = accepted;
        result.dropped = dropped;
        result.duplicated = false;
        return result;
    }

    /**
     * 命中幂等回执的结果（沿用首次受理的条数）。
     *
     * @param deviceId  设备 ID
     * @param requestId 请求 ID
     * @param itemCount 首次受理的条数
     * @return 结果
     */
    public static MqttReadingIngestResult duplicated(Long deviceId, String requestId,
                                                     Integer itemCount) {
        MqttReadingIngestResult result = new MqttReadingIngestResult();
        result.deviceId = deviceId;
        result.requestId = requestId;
        result.accepted = itemCount == null ? 0 : itemCount;
        result.dropped = 0;
        result.duplicated = true;
        return result;
    }
}
