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

import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupMemberResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupResp;
import cn.ypbin.admin.iot.service.IotDeviceGroupService;
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
 * IoT 设备分组接口（§3.11）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class IotDeviceGroupController {

    private final IotDeviceGroupService iotDeviceGroupService;

    /**
     * 查询全部分组。
     *
     * @return 分组列表
     */
    @GetMapping
    @SaCheckPermission("iot:group:list")
    public R<List<IotDeviceGroupResp>> list() {
        return R.ok(iotDeviceGroupService.listGroups());
    }

    /**
     * 新增分组。
     *
     * @param req 分组信息
     * @return 新分组主键
     */
    @PostMapping
    @SaCheckPermission("iot:group:create")
    @Idempotent
    @Log("新增 IoT 设备分组")
    public R<Long> create(@Valid @RequestBody IotDeviceGroupReq req) {
        return R.ok(iotDeviceGroupService.create(req));
    }

    /**
     * 编辑分组。
     *
     * @param id  分组主键
     * @param req 分组信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:group:update")
    @Idempotent
    @Log("编辑 IoT 设备分组")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IotDeviceGroupReq req) {
        iotDeviceGroupService.update(id, req);
        return R.ok();
    }

    /**
     * 删除分组。
     *
     * @param id 分组主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:group:delete")
    @Idempotent
    @Log("删除 IoT 设备分组")
    public R<Void> remove(@PathVariable Long id) {
        iotDeviceGroupService.remove(id);
        return R.ok();
    }

    /**
     * 查询分组下的设备成员。
     *
     * @param id 分组主键
     * @return 成员列表
     */
    @GetMapping("/{id}/members")
    @SaCheckPermission("iot:group:list")
    public R<List<IotDeviceGroupMemberResp>> listMembers(@PathVariable Long id) {
        return R.ok(iotDeviceGroupService.listMembers(id));
    }

    /**
     * 添加设备成员。
     *
     * @param id  分组主键
     * @param req 设备 ID
     * @return 成员主键
     */
    @PostMapping("/{id}/members")
    @SaCheckPermission("iot:group:update")
    @Idempotent
    @Log("向 IoT 设备分组添加设备")
    public R<Long> addMember(@PathVariable Long id, @Valid @RequestBody IotDeviceGroupMemberReq req) {
        return R.ok(iotDeviceGroupService.addMember(id, req));
    }

    /**
     * 移除设备成员。
     *
     * @param id       分组主键
     * @param memberId 成员主键
     * @return 空响应
     */
    @DeleteMapping("/{id}/members/{memberId}")
    @SaCheckPermission("iot:group:update")
    @Idempotent
    @Log("从 IoT 设备分组移除设备")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        iotDeviceGroupService.removeMember(memberId);
        return R.ok();
    }
}
