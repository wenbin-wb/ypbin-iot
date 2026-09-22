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

import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.starter.core.model.R;
import cn.dev33.satoken.annotation.SaCheckPermission;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逐台设备可用率查询（M-2 口径的对外出口）。
 *
 * <p>时间参数用平台统一格式 {@code yyyy-MM-dd HH:mm:ss}；两端都可省略（省略时终点取数据库当前时间、
 * 起点按配置的默认窗口推算）。把区间给反会**报错**而不是静默交换——否则一次参数笔误会得到一份
 * 看起来正常的报表。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@RestController
@RequestMapping("/devices/{deviceId}/availability")
@RequiredArgsConstructor
public class IotAvailabilityController {

    private final AvailabilityService availabilityService;

    /**
     * 查询设备可用率与断档明细。
     *
     * @param deviceId 设备 ID
     * @param from     窗口起点（可空）
     * @param to       窗口终点（可空）
     * @return 可用率视图
     */
    @GetMapping
    @SaCheckPermission("iot:availability:get")
    public R<AvailabilityResp> query(@PathVariable Long deviceId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return R.ok(availabilityService.query(deviceId, from, to));
    }
}
