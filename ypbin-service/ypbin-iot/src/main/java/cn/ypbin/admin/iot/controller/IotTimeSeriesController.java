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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPointResp;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryReq;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史时序查询端点（§5.2.1 查询路径；M-2 数据面）。
 *
 * <p>网关路由 {@code /iot/**} 且 StripPrefix=1 ⇒ 前端调用路径为
 * {@code /iot/devices/{deviceId}/series}。</p>
 *
 * <p>当前后端存储（IoTDB）尚未接入：本端点会返回**业务错误**说明「未启用」，
 * 而不是返回空列表——空列表会被误读成「这段时间没数据」（见 {@code TimeSeriesQueryService}）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@RestController
@RequestMapping("/devices/{deviceId}/series")
@RequiredArgsConstructor
public class IotTimeSeriesController {

    private final TimeSeriesQueryService timeSeriesQueryService;

    /**
     * 查询某设备某点位的历史时序。
     *
     * @param deviceId 设备 ID
     * @param req      查询条件（点位必填；时间范围为 epoch 毫秒；limit 有上限）
     * @return 时序点列表（升序）
     */
    @GetMapping
    @SaCheckPermission("iot:series:get")
    public R<List<TimeSeriesPointResp>> series(@PathVariable("deviceId") Long deviceId,
                                               @Valid TimeSeriesQueryReq req) {
        return R.ok(timeSeriesQueryService.query(deviceId, req));
    }
}
