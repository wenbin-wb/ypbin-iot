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

import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * MQTT 入站薄适配端点的请求体（EMQX Rule Engine 的 HTTP 动作按本契约拼 body）。
 *
 * <p><b>契约（P0：一条上行消息 = 一台设备的一小批读数）</b>：</p>
 * <pre>{@code
 * {"requestId":"r-9300012-1758768000000",
 *  "items":[{"deviceId":"9300012","propertyId":"temperature","value":"23.4",
 *            "quality":"GOOD","ts":1758768000000,"pollIntervalMs":30000}]}
 * }</pre>
 *
 * <p><b>刻意没有 tenantId</b>：租户由服务端**按设备行反查**（设备 ID 来自 MQTT 主题段，已由 ACL +
 * 规则 SQL 的身份一致性校验保证）。报文里若带了 {@code tenantId} 会被 Jackson 忽略而**不生效**——
 * 这是「不信任报文里的租户」的可测试形态：投一个 {@code tenantId=9} 上去，数据只会落在设备真实租户下。</p>
 *
 * <p><b>为什么 {@code requestId} 是必填</b>：QoS1 是「至少一次」、桥接还有 {@code max_retries} 重发，
 * 没有幂等键就一定会重复写最新值/时序。设备端生成、平台只做去重（见 {@code IotMqttIngestReceipt}）。</p>
 *
 * <p><b>本类只承载数据</b>：字段级校验放在 {@code MqttReadingIngestServiceImpl} 的纯内存校验里
 * （**不使用 Bean Validation 注解**——注解校验失败会被全局异常处理器转成 HTTP 200 + {@code R.code}，
 * 正是入站链路必须避免的静默丢数据形态，见设计 §6.5 的「400 到底怎么出」）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@Getter
@Setter
public class MqttReadingIngestReq {

    /** 请求 ID 上限（与 {@code iot_mqtt_ingest_receipt.request_id} 列宽一致）。 */
    public static final int MAX_REQUEST_ID_LENGTH = 64;

    /** 请求 ID 形态：字母/数字/下划线/点/冒号/连字符（与点位标识同一套字符口径，便于规则引擎侧拼接）。 */
    public static final String REQUEST_ID_PATTERN =
        "[A-Za-z0-9_.:-]{1," + MAX_REQUEST_ID_LENGTH + "}";

    /** 单条 MQTT 消息允许承载的读数条数上限（一条消息 = 一台设备的一小批，防单报文放大）。 */
    public static final int MAX_ITEMS = 50;

    /** 请求体字节数上限（防超大 payload 进内存；超限在解析前按 4xx 拒绝）。 */
    public static final int MAX_BODY_LENGTH = 64 * 1024;

    private static final Pattern REQUEST_ID = Pattern.compile(REQUEST_ID_PATTERN);

    /** 设备侧请求 ID（幂等键；同设备内唯一）。 */
    private String requestId;

    /** 读数清单（元素为既有 {@link ReadingObservationDto}，契约复用不另造）。 */
    private List<ReadingObservationDto> items = new ArrayList<>();

    /**
     * 读数清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<ReadingObservationDto> getItems() {
        return items == null ? List.of() : items;
    }

    /**
     * 请求 ID 形态是否合法。
     *
     * @param requestId 请求 ID
     * @return 合法返回 {@code true}
     */
    public static boolean isValidRequestId(String requestId) {
        return requestId != null && REQUEST_ID.matcher(requestId).matches();
    }
}
