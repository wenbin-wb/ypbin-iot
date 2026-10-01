/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.admin.iot.service.IotDeviceService;
import cn.ypbin.admin.iot.service.IotEventService;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPointResp;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryReq;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryService;
import cn.ypbin.admin.iot.values.LatestValueQueryService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API 门面：设备域只读（看板 #11 门面，O-1/O-2/O-3/O-6 + O-4 的设备告警）。
 *
 * <p>薄适配：只做路径映射 + 权限码声明，业务逻辑零复制，全部委托既有 Service
 * （与管理面 {@code IotDeviceController} 等同源）。租户隔离、D0.5 不存在性语义、
 * 只读无副作用，均由被委托的 Service 承担（前置核验 U-1 已逐方法核验）。</p>
 *
 * <p>刻意不映射：命令下发（O-7，需 C1–C4 先落地，见 PREFLIGHT §1.3）、设备标签/映射/
 * 分组/维护窗的写端点（首期不开放）。本包禁止写映射（门禁断言）。</p>
 */
@RestController
@RequestMapping("/open-api/v1/devices")
@RequiredArgsConstructor
public class OpenApiDeviceController {

    private final IotDeviceService iotDeviceService;
    private final LatestValueQueryService latestValueQueryService;
    private final TimeSeriesQueryService timeSeriesQueryService;
    private final IotEventService iotEventService;
    private final AvailabilityService availabilityService;
    private final AlertInstanceService alertInstanceService;

    /** O-1 设备台账查询。 */
    @GetMapping
    @SaCheckPermission("iot:device:list")
    public R<PageResult<IotDeviceResp>> page(IotDeviceQuery query) {
        return R.ok(iotDeviceService.pageDevices(query));
    }

    /** O-2 最新值查询。 */
    @GetMapping("/{deviceId}/latest")
    @SaCheckPermission("iot:device:latest")
    public R<List<LatestValueResp>> latest(@PathVariable("deviceId") Long deviceId) {
        return R.ok(latestValueQueryService.listLatest(deviceId));
    }

    /** O-3 历史时序查询（存储未启用时 Service 抛错，不返回空列表假阴性）。 */
    @GetMapping("/{deviceId}/series")
    @SaCheckPermission("iot:series:get")
    public R<List<TimeSeriesPointResp>> series(@PathVariable("deviceId") Long deviceId,
                                               @Valid TimeSeriesQueryReq req) {
        return R.ok(timeSeriesQueryService.query(deviceId, req));
    }

    /** O-6 设备事件查询。 */
    @GetMapping("/{deviceId}/events")
    @SaCheckPermission("iot:device:list")
    public R<PageResult<EventLogResp>> events(@PathVariable("deviceId") Long deviceId,
                                              @Valid EventLogQuery query) {
        return R.ok(iotEventService.pageEvents(deviceId, query));
    }

    /** O-6 可用率查询（日期格式与管理面逐字一致）。 */
    @GetMapping("/{deviceId}/availability")
    @SaCheckPermission("iot:availability:get")
    public R<AvailabilityResp> availability(@PathVariable("deviceId") Long deviceId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return R.ok(availabilityService.query(deviceId, from, to));
    }

    /** O-4 某设备告警查询（与管理面同构：先按 path 覆盖 query.deviceId）。 */
    @GetMapping("/{deviceId}/alerts")
    @SaCheckPermission("iot:alert:list")
    public R<PageResult<AlertInstanceResp>> deviceAlerts(@PathVariable("deviceId") Long deviceId,
                                                         @Valid AlertInstanceQuery query) {
        query.setDeviceId(deviceId);
        return R.ok(alertInstanceService.page(query));
    }

    /** O-4 某设备告警摘要。 */
    @GetMapping("/{deviceId}/alerts/summary")
    @SaCheckPermission("iot:alert:list")
    public R<AlertSummaryResp> deviceAlertSummary(@PathVariable("deviceId") Long deviceId) {
        return R.ok(alertInstanceService.summary(deviceId));
    }
}
