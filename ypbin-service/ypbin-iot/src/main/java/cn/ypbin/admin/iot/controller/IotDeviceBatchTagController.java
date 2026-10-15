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

import cn.ypbin.admin.iot.model.req.IotDeviceBatchTagRemoveReq;
import cn.ypbin.admin.iot.model.req.IotDeviceBatchTagReq;
import cn.ypbin.admin.iot.model.resp.BatchTagResp;
import cn.ypbin.admin.iot.service.IotDeviceTagService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 设备标签批量运维接口（挂在设备集合下，路径与单台设备的 {@code /devices/{id}/tags} 区分）。
 *
 * <p><b>批量打标签的权限取 create/update 的并集（任一即可）</b>：一次请求按设备逐台看，
 * 可能部分设备是新增、部分设备是更新，写前无法拆成两种权限分别校验；若强制同时具备两个权限，
 * 只拥有其中一种的合法标签运维人员将无法完成混合批次。删除仍是独立的 {@code iot:tag:delete}，
 * 不与写值权限混用。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@RestController
@RequestMapping("/devices/batch-tags")
@RequiredArgsConstructor
public class IotDeviceBatchTagController {

    private final IotDeviceTagService iotDeviceTagService;

    /**
     * 批量给多台设备打同一个键值标签（已存在则更新值，不存在则新增；单次最多 500 台）。
     *
     * @param req 批量打标签请求
     * @return 新增/更新行数
     */
    @PostMapping
    @SaCheckPermission(value = {"iot:tag:create", "iot:tag:update"}, mode = SaMode.OR)
    @Idempotent
    @Log("批量设置 IoT 设备标签")
    public R<BatchTagResp> batchUpsertTag(@Valid @RequestBody IotDeviceBatchTagReq req) {
        return R.ok(iotDeviceTagService.batchUpsertTag(req.getDeviceIds(), req.getTagKey(),
            req.getTagValue()));
    }

    /**
     * 批量移除多台设备上的指定标签键（一次逻辑删除）。
     *
     * @param req 批量移除标签请求
     * @return 删除行数
     */
    @PostMapping("/remove")
    @SaCheckPermission("iot:tag:delete")
    @Idempotent
    @Log("批量移除 IoT 设备标签")
    public R<BatchTagResp> batchRemoveTags(@Valid @RequestBody IotDeviceBatchTagRemoveReq req) {
        return R.ok(iotDeviceTagService.batchRemoveTags(req.getDeviceIds(), req.getTagKeys()));
    }
}
