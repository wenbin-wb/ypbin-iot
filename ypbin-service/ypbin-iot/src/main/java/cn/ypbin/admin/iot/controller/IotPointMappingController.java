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

import cn.ypbin.admin.iot.model.req.IotPointMappingReq;
import cn.ypbin.admin.iot.model.resp.IotPointMappingResp;
import cn.ypbin.admin.iot.service.IotPointMappingService;
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
 * IoT 点位映射接口（§3.9，挂在设备下）。
 *
 * <p>路径 {@code /devices/{id}/points} 与设计 §13 一致；映射必须引用已发布版本的属性。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/devices/{deviceId}/points")
@RequiredArgsConstructor
public class IotPointMappingController {

    private final IotPointMappingService iotPointMappingService;

    /**
     * 查询设备下的点位映射列表。
     *
     * @param deviceId 设备主键
     * @return 点位映射列表
     */
    @GetMapping
    @SaCheckPermission("iot:point:list")
    public R<List<IotPointMappingResp>> list(@PathVariable Long deviceId) {
        return R.ok(iotPointMappingService.listByDevice(deviceId));
    }

    /**
     * 新增点位映射。
     *
     * @param deviceId 设备主键
     * @param req      点位映射信息
     * @return 新映射主键
     */
    @PostMapping
    @SaCheckPermission("iot:point:create")
    @Idempotent
    @Log("新增 IoT 点位映射")
    public R<Long> create(@PathVariable Long deviceId, @Valid @RequestBody IotPointMappingReq req) {
        req.setDeviceId(deviceId);
        return R.ok(iotPointMappingService.create(req));
    }

    /**
     * 编辑点位映射。
     *
     * @param deviceId 设备主键
     * @param id       映射主键
     * @param req      点位映射信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:point:update")
    @Idempotent
    @Log("编辑 IoT 点位映射")
    public R<Void> update(@PathVariable Long deviceId, @PathVariable Long id,
                          @Valid @RequestBody IotPointMappingReq req) {
        iotPointMappingService.update(id, req);
        return R.ok();
    }

    /**
     * 删除点位映射。
     *
     * @param deviceId 设备主键
     * @param id       映射主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:point:delete")
    @Idempotent
    @Log("删除 IoT 点位映射")
    public R<Void> remove(@PathVariable Long deviceId, @PathVariable Long id) {
        iotPointMappingService.remove(id);
        return R.ok();
    }
}
