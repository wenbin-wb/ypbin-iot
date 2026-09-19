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

import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.admin.iot.service.IotDeviceService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 设备台账接口。
 *
 * <p>权限码形如 {@code iot:device:list}（与 admin 其它业务域一致：{@code 域:资源:动作}）。
 * 路径是<b>纯资源路径</b>：网关按 {@code Path=/iot/**} + {@code StripPrefix=1} 转发，
 * 因此客户端调 {@code /iot/devices}、服务内看到 {@code /devices}（与 ai/system 同构）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class IotDeviceController {

    private final IotDeviceService iotDeviceService;

    /**
     * 分页查询设备。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:device:list")
    public R<PageResult<IotDeviceResp>> page(IotDeviceQuery query) {
        return R.ok(iotDeviceService.pageDevices(query));
    }

    /**
     * 新增设备。
     *
     * @param req 设备信息
     * @return 新设备主键
     */
    @PostMapping
    @SaCheckPermission("iot:device:create")
    @Idempotent
    @Log("新增 IoT 设备")
    public R<Long> create(@Valid @RequestBody IotDeviceReq req) {
        return R.ok(iotDeviceService.createDevice(req));
    }

    /**
     * 删除设备。
     *
     * @param id 设备主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:device:delete")
    @Idempotent
    @Log("删除 IoT 设备")
    public R<Void> remove(@PathVariable Long id) {
        iotDeviceService.removeDevice(id);
        return R.ok();
    }
}
