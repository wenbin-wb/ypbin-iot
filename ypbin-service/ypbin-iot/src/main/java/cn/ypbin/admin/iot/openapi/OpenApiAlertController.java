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
 * 开放 API 门面：告警查询只读（看板 #11 门面，O-4）。
 *
 * <p>薄适配，委托 {@link AlertInstanceService}（与管理面 {@code IotAlertController}
 * 的读路径同源；未启用告警时 Service 抛错，不返回空列表）。</p>
 *
 * <p>刻意不映射：{@code /active-counts}（设计未盘点端点一律不开放）、ack/silence/
 * 规则 CRUD（写 + 通知副作用，首期不开放）。</p>
 */
@RestController
@RequestMapping("/open-api/v1/alerts")
@RequiredArgsConstructor
public class OpenApiAlertController {

    private final AlertInstanceService alertInstanceService;

    /** O-4 告警分页查询（整租户）。 */
    @GetMapping
    @SaCheckPermission("iot:alert:list")
    public R<PageResult<AlertInstanceResp>> page(@Valid AlertInstanceQuery query) {
        return R.ok(alertInstanceService.page(query));
    }

    /** O-4 告警详情（含投递记录）。 */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:alert:list")
    public R<AlertInstanceResp> detail(@PathVariable("id") Long id) {
        return R.ok(alertInstanceService.detail(id));
    }

    /** O-4 告警概览摘要（整租户）。 */
    @GetMapping("/summary")
    @SaCheckPermission("iot:alert:list")
    public R<AlertSummaryResp> summary() {
        return R.ok(alertInstanceService.summary(null));
    }
}
