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
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import cn.ypbin.admin.iot.service.IotTwinSceneService;
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
 * 数字孪生场景接口（树形）。
 *
 * <p>路径由网关 {@code Path=/iot/**} + {@code StripPrefix=1} 转发：客户端调
 * {@code /iot/twin/scenes}，服务内看到 {@code /twin/scenes}。</p>
 *
 * <p><b>为什么场景不按资源挂细粒度权限码</b>（如「每个场景一个权限」）：场景是展示配置，
 * 不是设备/规则/凭据这类受控资源，同一租户内共享同一套配置是设计意图（与组态看板同口径）。
 * 四个权限码区分的是「读 / 建 / 改 / 删」四类动作。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes")
@RequiredArgsConstructor
public class IotTwinSceneController {

    private final IotTwinSceneService twinSceneService;

    /**
     * 查询全部场景（树形由客户端按 parentId 组装）。
     *
     * @return 场景列表（无数据为空数组）
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinSceneResp>> list() {
        return R.ok(twinSceneService.listScenes());
    }

    /**
     * 查询单个场景。
     *
     * @param id 场景主键
     * @return 场景详情
     */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:twin:list")
    public R<IotTwinSceneResp> detail(@PathVariable Long id) {
        return R.ok(twinSceneService.getScene(id));
    }

    /**
     * 新建场景。
     *
     * @param req 场景信息
     * @return 新场景主键
     */
    @PostMapping
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("新增数字孪生场景")
    public R<Long> create(@Valid @RequestBody IotTwinSceneReq req) {
        return R.ok(twinSceneService.create(req));
    }

    /**
     * 编辑场景。
     *
     * @param id  场景主键
     * @param req 场景信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:twin:update")
    @Idempotent
    @Log("编辑数字孪生场景")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IotTwinSceneReq req) {
        twinSceneService.update(id, req);
        return R.ok();
    }

    /**
     * 删除场景（存在子场景或点位时拒绝，不级联删除）。
     *
     * @param id 场景主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生场景")
    public R<Void> remove(@PathVariable Long id) {
        twinSceneService.remove(id);
        return R.ok();
    }
}
