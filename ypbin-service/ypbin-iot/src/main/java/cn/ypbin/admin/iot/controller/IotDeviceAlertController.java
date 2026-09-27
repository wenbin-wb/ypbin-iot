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
import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备视角的告警接口（设备详情抽屉的**第 6 个「告警」页签**）。
 *
 * <p>与 {@code /alerts?deviceId=} 是同一份数据、同一个服务，只是路径语义更贴合页签；
 * 「概览」页签的一行摘要用 {@code /devices/{id}/alerts/summary}（一次聚合查询，不拿列表长度当计数）。</p>
 *
 * <p>曲线缩略**不在这里**：展开行要看的「该点位近期曲线」复用既有的
 * {@code GET /iot/devices/{deviceId}/series}（同一套查询、同一套图表），不另造一套查询接口。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@RestController
@RequestMapping("/devices/{deviceId}/alerts")
@RequiredArgsConstructor
public class IotDeviceAlertController {

    private final AlertInstanceService alertInstanceService;

    /**
     * 分页查询某设备的告警（活动与历史同一接口，靠 {@code state} / {@code activeOnly} 区分）。
     *
     * @param deviceId 设备主键
     * @param query    查询条件
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:alert:list")
    public R<PageResult<AlertInstanceResp>> page(@PathVariable("deviceId") Long deviceId,
                                                 @Valid AlertInstanceQuery query) {
        query.setDeviceId(deviceId);
        return R.ok(alertInstanceService.page(query));
    }

    /**
     * 某设备的告警摘要（概览页签的一行摘要）。
     *
     * @param deviceId 设备主键
     * @return 摘要
     */
    @GetMapping("/summary")
    @SaCheckPermission("iot:alert:list")
    public R<AlertSummaryResp> summary(@PathVariable("deviceId") Long deviceId) {
        return R.ok(alertInstanceService.summary(deviceId));
    }
}
