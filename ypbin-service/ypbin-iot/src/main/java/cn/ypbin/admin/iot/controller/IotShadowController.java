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

import cn.ypbin.admin.iot.model.req.IotShadowReq;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;
import cn.ypbin.admin.iot.service.IotShadowService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 设备影子接口（§3.10，挂在设备下）。
 *
 * <p>路径 {@code /devices/{id}/shadow} 与设计 §13 一致；读取返回 reported+desired 合并视图。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/devices/{deviceId}/shadow")
@RequiredArgsConstructor
public class IotShadowController {

    private final IotShadowService iotShadowService;

    /**
     * 读取影子（reported 优先，无则回退 desired）。
     *
     * @param deviceId 设备主键
     * @return 影子响应
     */
    @GetMapping
    @SaCheckPermission("iot:shadow:get")
    public R<IotShadowResp> get(@PathVariable Long deviceId) {
        return R.ok(iotShadowService.get(deviceId));
    }

    /**
     * 写入期望值（desired）。
     *
     * @param deviceId 设备主键
     * @param req      期望值
     * @return 空响应
     */
    @PutMapping
    @SaCheckPermission("iot:shadow:update")
    @Idempotent
    @Log("更新 IoT 设备影子期望值")
    public R<Void> updateDesired(@PathVariable Long deviceId, @Valid @RequestBody IotShadowReq req) {
        req.setDeviceId(deviceId);
        iotShadowService.updateDesired(req);
        return R.ok();
    }
}
