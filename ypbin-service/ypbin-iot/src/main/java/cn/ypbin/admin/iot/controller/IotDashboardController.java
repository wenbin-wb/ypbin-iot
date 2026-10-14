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

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp;
import cn.ypbin.admin.iot.service.IotDashboardService;
import cn.ypbin.starter.core.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 运行总览接口。
 *
 * <p><b>面向所有登录用户</b>：总览只做本租户运行态势的只读聚合，不承载单资源操作，因此不复用
 * {@code iot:device:list} 之类的细分权限码——挂 {@link SaCheckLogin} 即可（未登录一律拦截）。
 * 数据由 MyBatis-Plus 租户插件按当前租户隔离，服务内不手写 {@code tenant_id} 过滤。</p>
 *
 * <p>路径由网关 {@code Path=/iot/**} + {@code StripPrefix=1} 转发：客户端调
 * {@code GET /iot/dashboard/overview}，服务内看到 {@code /dashboard/overview}。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class IotDashboardController {

    private final IotDashboardService iotDashboardService;

    /**
     * 获取运行总览（设备、消息、告警、分布与趋势一次拉齐）。
     *
     * @return 运行总览
     */
    @GetMapping("/overview")
    @SaCheckLogin
    public R<DashboardOverviewResp> overview() {
        return R.ok(iotDashboardService.overview());
    }
}
