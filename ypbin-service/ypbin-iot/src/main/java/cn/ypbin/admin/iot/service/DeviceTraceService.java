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

import cn.ypbin.admin.iot.model.query.DeviceTraceQuery;
import cn.ypbin.admin.iot.model.resp.DeviceTraceResp;

/**
 * 设备「消息跟踪」服务（看板 #8，设计 `docs/MESSAGE-TRACE-DESIGN.md`）——**只读聚合**。
 *
 * <p><b>本能力一期不写任何数据</b>：时间线完全由既有的三张链路表
 * （`iot_command_instance` / `iot_mqtt_ingest_receipt` / `iot_event_log` / `outage_event`）
 * 在**读侧**聚合而成 ⇒ 不新增表、不改写入路径、不动采集链路。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public interface DeviceTraceService {

    /**
     * 查询某设备在时间窗内的消息时间线（按时间倒序）。
     *
     * <p>设备不存在或**不属于当前租户**时抛业务异常（不区分二者：区分等于泄露"这个 id 是否存在"）。
     * 空结果返回**空集合**（绝不返回 `null`）。</p>
     *
     * @param deviceId 设备主键
     * @param query    时间窗与筛选条件
     * @return 时间线（含是否被截断的显式标记）
     */
    DeviceTraceResp timeline(Long deviceId, DeviceTraceQuery query);
}
