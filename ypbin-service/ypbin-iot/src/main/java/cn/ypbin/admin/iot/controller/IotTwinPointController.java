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
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
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
 * 数字孪生点位接口（设备锚点 + 属性绑定，挂在场景下）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes/{sceneId}/points")
@RequiredArgsConstructor
public class IotTwinPointController {

    private final IotTwinPointService twinPointService;

    /**
     * 查询场景下全部点位（含绑定）。
     *
     * @param sceneId 场景主键
     * @return 点位列表
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinPointResp>> list(@PathVariable Long sceneId) {
        return R.ok(twinPointService.listByScene(sceneId));
    }

    /**
     * 新增点位（可同时带绑定）。
     *
     * @param sceneId 场景主键
     * @param req     点位信息
     * @return 新点位主键
     */
    @PostMapping
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("新增数字孪生点位")
    public R<Long> create(@PathVariable Long sceneId, @Valid @RequestBody IotTwinPointReq req) {
        return R.ok(twinPointService.create(sceneId, req));
    }

    /**
     * 编辑点位并整体替换其绑定。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @param req     点位信息
     * @return 空响应
     */
    @PutMapping("/{pointId}")
    @SaCheckPermission("iot:twin:update")
    @Idempotent
    @Log("编辑数字孪生点位")
    public R<Void> update(@PathVariable Long sceneId, @PathVariable Long pointId,
                          @Valid @RequestBody IotTwinPointReq req) {
        twinPointService.update(sceneId, pointId, req);
        return R.ok();
    }

    /**
     * 删除点位（连同其绑定）。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @return 空响应
     */
    @DeleteMapping("/{pointId}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生点位")
    public R<Void> remove(@PathVariable Long sceneId, @PathVariable Long pointId) {
        twinPointService.remove(sceneId, pointId);
        return R.ok();
    }
}
