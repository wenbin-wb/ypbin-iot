/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.admin.iot.service.IotEventService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备运行期事件查询（G6：设备详情「事件」区块的数据出口）。
 *
 * <p>权限码沿用 {@code iot:device:list}（事件是设备页内的读能力，与影子/标签/可用率/历史曲线
 * 同一处理：不新造权限码，避免多一个「挂着却没人能授」的码）。</p>
 *
 * <p>时间范围左闭右开（{@code from} 含、{@code to} 不含），与断档/时序查询同口径。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@RestController
@RequestMapping("/devices/{deviceId}/events")
@RequiredArgsConstructor
public class IotDeviceEventController {

    private final IotEventService iotEventService;

    /**
     * 分页查询某设备的事件实例（按事件发生时刻倒序）。
     *
     * @param deviceId 设备 ID
     * @param query    查询条件（from/to/level/page/pageSize）
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:device:list")
    public R<PageResult<EventLogResp>> page(@PathVariable Long deviceId,
                                            @Valid EventLogQuery query) {
        return R.ok(iotEventService.pageEvents(deviceId, query));
    }
}
