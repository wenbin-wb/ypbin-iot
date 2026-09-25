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
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.admin.iot.values.LatestValueQueryService;
import cn.ypbin.starter.core.model.R;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备最新值查询端点（补 G1：最新值原本「只写不读」）。
 *
 * <p>网关路由 {@code /iot/**} 且 StripPrefix=1 ⇒ 前端调用路径为
 * {@code /iot/devices/{deviceId}/latest}。权限码 {@code iot:device:latest} 与设备读取同域，
 * 已登记在 {@code deploy/sql/007-iot-data.sql} 与对应迁移脚本（由权限码门禁与菜单授权门禁双兜底）。</p>
 *
 * <p>与历史时序的分工：本端点只给「最新一条」；要回溯请用
 * {@code GET /iot/devices/{deviceId}/series}。返回空列表表示「该设备还没有最新值」
 * （未上报，或 Redis 未装配/不可用）——不会用假数据填格子。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@RestController
@RequestMapping("/devices/{deviceId}/latest")
@RequiredArgsConstructor
public class IotLatestValueController {

    private final LatestValueQueryService latestValueQueryService;

    /**
     * 查询某设备全部点位的最新值。
     *
     * @param deviceId 设备 ID
     * @return 最新值列表（按点位标识升序；无数据为空列表）
     */
    @GetMapping
    @SaCheckPermission("iot:device:latest")
    public R<List<LatestValueResp>> latest(@PathVariable("deviceId") Long deviceId) {
        return R.ok(latestValueQueryService.listLatest(deviceId));
    }
}
