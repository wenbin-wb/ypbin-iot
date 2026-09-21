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

import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import cn.ypbin.admin.iot.service.IotDeviceTagService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 设备标签接口（§3.11，挂在设备下）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/devices/{deviceId}/tags")
@RequiredArgsConstructor
public class IotDeviceTagController {

    private final IotDeviceTagService iotDeviceTagService;

    /**
     * 查询设备下的标签列表。
     *
     * @param deviceId 设备主键
     * @return 标签列表
     */
    @GetMapping
    @SaCheckPermission("iot:tag:list")
    public R<List<IotDeviceTagResp>> list(@PathVariable Long deviceId) {
        return R.ok(iotDeviceTagService.listByDevice(deviceId));
    }

    /**
     * 新增标签。
     *
     * @param deviceId 设备主键
     * @param req      标签信息
     * @return 新标签主键
     */
    @PostMapping
    @SaCheckPermission("iot:tag:create")
    @Idempotent
    @Log("新增 IoT 设备标签")
    public R<Long> create(@PathVariable Long deviceId, @Valid @RequestBody IotDeviceTagReq req) {
        return R.ok(iotDeviceTagService.create(deviceId, req));
    }

    /**
     * 编辑标签。
     *
     * @param deviceId 设备主键
     * @param id       标签主键
     * @param req      标签信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:tag:update")
    @Idempotent
    @Log("编辑 IoT 设备标签")
    public R<Void> update(@PathVariable Long deviceId, @PathVariable Long id,
                          @Valid @RequestBody IotDeviceTagReq req) {
        iotDeviceTagService.update(id, req);
        return R.ok();
    }

    /**
     * 删除标签。
     *
     * @param deviceId 设备主键
     * @param id       标签主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:tag:delete")
    @Idempotent
    @Log("删除 IoT 设备标签")
    public R<Void> remove(@PathVariable Long deviceId, @PathVariable Long id) {
        iotDeviceTagService.remove(id);
        return R.ok();
    }
}
