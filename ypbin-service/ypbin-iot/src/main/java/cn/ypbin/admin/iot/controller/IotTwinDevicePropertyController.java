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
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.starter.core.model.R;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生配置页的「设备可绑定属性」接口。
 *
 * <p>为什么单独一个控制器而不是塞进点位控制器：它的主语是<b>设备</b>而不是场景，
 * 路径也挂在 {@code /twin/devices/{deviceId}} 下。混进场景控制器会让路径语义与
 * {@code @RequestMapping} 冲突。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/devices")
@RequiredArgsConstructor
public class IotTwinDevicePropertyController {

    private final IotTwinPointService twinPointService;

    /**
     * 查询设备可绑定的物模型属性（含「是否已配置采集点位」）。
     *
     * <p>设备未绑定产品时返回空数组（正常空态，不是错误）。
     *
     * @param deviceId 设备主键
     * @return 属性列表
     */
    @GetMapping("/{deviceId}/properties")
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinDevicePropertyResp>> properties(@PathVariable Long deviceId) {
        return R.ok(twinPointService.listDeviceProperties(deviceId));
    }
}
