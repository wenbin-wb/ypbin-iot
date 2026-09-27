/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * MQTT 入站幂等回执（EMQX 入站链路）：一条上行 MQTT 消息 = 一台设备的一小批读数 = 一个 {@code requestId}。
 *
 * <p><b>为什么需要一张回执表</b>：入站链路的重复来源是**协议层**而不是调用方手滑——QoS1 是「至少一次」，
 * 桥接还有 {@code max_retries} + {@code request_ttl} 的自行重发；而落库出口是「最新值 Hash 覆盖写 +
 * IoTDB 追加行 + 活性刷新」，重复执行会重复写。幂等键落在数据库唯一键上，才拦得住并发重投。</p>
 *
 * <p><b>唯一键为什么含 {@code device_id}</b>：{@code requestId} 由**设备**生成，不同设备之间的取值
 * 不保证互不相同（现场很可能每台设备都从 {@code 1} 开始计数）。设计文档里 {@code iot_command_instance}
 * 用 {@code (tenant_id, request_id)} 是因为那里的 {@code requestId} 由**平台**生成、全局唯一；本表的
 * {@code requestId} 来自设备，因此必须带上设备维度——否则 A 设备的 {@code 1} 会把 B 设备的 {@code 1} 顶掉
 * （静默丢数据，且告警不报）。这个差异是刻意的，不是抄漏。</p>
 *
 * <p><b>回执只记「这批被受理了」</b>：{@code item_count} 是首次受理时通过校验的条数；重投时原样读回、
 * 不重算（重算会随时间漂移：点位映射可能在两次投递之间被改）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@Getter
@Setter
@TableName("iot_mqtt_ingest_receipt")
public class IotMqttIngestReceipt extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id；来自 MQTT 主题段，非报文声明）。 */
    private Long deviceId;

    /** 设备侧请求 ID（幂等键；同一设备内唯一）。 */
    private String requestId;

    /** 首次受理时通过校验的读数条数。 */
    private Integer itemCount;
}
