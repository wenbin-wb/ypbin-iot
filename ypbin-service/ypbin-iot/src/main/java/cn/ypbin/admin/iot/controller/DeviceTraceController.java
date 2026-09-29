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

import cn.ypbin.admin.iot.model.query.DeviceTraceQuery;
import cn.ypbin.admin.iot.model.resp.DeviceTraceResp;
import cn.ypbin.admin.iot.service.DeviceTraceService;
import cn.ypbin.starter.core.model.R;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备「消息跟踪」接口（看板 #8，设计 `docs/MESSAGE-TRACE-DESIGN.md` §5）。
 *
 * <p><b>路径与网关口径</b>：客户端调 `/iot/devices/{id}/messages`，网关 {@code StripPrefix=1} 后
 * 落到本控制器的 `/devices/{deviceId}/messages`（与 {@code IotCommandController} 同构）。</p>
 *
 * <p>🔴 <b>权限码为什么是 `iot:debug:get` 而不是 `iot:device:list`</b>（设计 §5.1，</p>
 * 这条在复核中被发现是本能力**最容易做错**的地方）：
 * <ul>
 *   <li>本端点返回的条目里**包含下行命令的标识、归因码、以及"设备回执原文"的入口** ——
 *       这些数据在既有系统里由 {@code iot:debug:get} 守着
 *       （{@code IotCommandController#page}，见 `migration/2026-10-02-iot-command-instance.sql`）；</li>
 *   <li>`iot:device:list` 是**设备列表页的菜单级权限**（凡能看到设备列表的角色都有），
 *       用它去返回命令侧数据是**权限降级** ✗；</li>
 *   <li>仓内已有明文门禁「**查询与下发绝不能同码**」
 *       （{@code IotCommandControllerGateTest}）——本端点属"查询"侧，取较严的既有码才自洽。</li>
 * </ul>
 *
 * <p>本控制器沿用「HTTP 200 + {@code R.code}」惯例：业务性失败（设备不存在/时间窗非法/
 * 筛选码未知）一律走信封，由全局异常处理器统一成 200。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceTraceController {

    private final DeviceTraceService deviceTraceService;

    /**
     * 查询某设备在时间窗内的消息时间线（按时间倒序）。
     *
     * @param deviceId 设备主键
     * @param query    时间窗与筛选条件（时间窗缺省为"当前时刻往前 1 小时"）
     * @return 时间线
     */
    @GetMapping("/{deviceId}/messages")
    @SaCheckPermission("iot:debug:get")
    public R<DeviceTraceResp> timeline(@PathVariable Long deviceId,
                                       @Valid DeviceTraceQuery query) {
        return R.ok(deviceTraceService.timeline(deviceId, query));
    }
}
