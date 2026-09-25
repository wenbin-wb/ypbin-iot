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

import cn.ypbin.admin.iot.event.EventIngestReq;
import cn.ypbin.admin.iot.event.EventIngestResult;
import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.starter.crud.model.PageResult;

/**
 * 运行期事件服务（G6）：上报（幂等）与查询。
 *
 * @author wenbin
 * @since 2026-09-28
 */
public interface IotEventService {

    /**
     * 接收一批运行期事件（内部端点；租户按设备解析，不信任请求体里的租户）。
     *
     * @param req 上报请求（单批上限见 {@link EventIngestReq#MAX_BATCH_SIZE}）
     * @return 落库/去重/丢弃的条数明细
     */
    EventIngestResult ingest(EventIngestReq req);

    /**
     * 分页查询某设备的事件实例（按事件发生时刻倒序）。
     *
     * @param deviceId 设备 ID
     * @param query    查询条件（时间范围左闭右开、级别过滤、分页）
     * @return 分页结果（无数据时 {@code items} 为空集合，绝不返回 null）
     */
    PageResult<EventLogResp> pageEvents(Long deviceId, EventLogQuery query);
}
