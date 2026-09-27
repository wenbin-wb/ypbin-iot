/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;

/**
 * MQTT 入站薄适配服务（设备 → EMQX → 平台）。
 *
 * <p><b>职责边界（只有两件）</b>：契约适配（JSON → 既有 {@code ReadingIngestReq}）+ 非法即整批拒绝
 * （在动库之前）。**落库链路完全复用** {@code AvailabilityService.ingest}——活性/断档、最新值、时序、
 * 影子一律走既有实现，本服务不新增任何写入路径。</p>
 *
 * <p><b>入参为什么是原始字符串而不是已绑定的对象</b>：{@code @RequestBody} + Bean Validation 的
 * 校验失败会被全局异常处理器转成 HTTP 200 + {@code R.code}（本仓统一异常口径），而本端点按设计决策
 * D2 必须让对端（EMQX 连接器）看到**真 4xx**。因此解析与校验都由本服务自己做，控制器不做绑定。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public interface MqttReadingIngestService {

    /**
     * 受理一条 MQTT 上行报文的读数。
     *
     * @param rawBody 原始请求体（EMQX HTTP 动作产出的 JSON）
     * @return 受理结果（重复投递时 {@code duplicated=true} 且不重复落库）
     * @throws MqttIngestRejectionException 报文/契约非法（控制器据此返回原始 HTTP 4xx）
     */
    MqttReadingIngestResult ingest(String rawBody);
}
