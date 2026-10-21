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
import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;
import cn.ypbin.admin.iot.service.IotTwinRuntimeService;
import cn.ypbin.starter.core.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生运行态快照接口。
 *
 * <p>实时增量走既有的 {@code GET /iot/realtime/stream}（SSE 的 latest/device-status/alert
 * 三事件），本接口只提供「进入场景时的一次拉齐」。两者职责不同，不重复实现实时链路。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes/{sceneId}")
@RequiredArgsConstructor
public class IotTwinRuntimeController {

    private final IotTwinRuntimeService twinRuntimeService;

    /**
     * 组装场景运行态快照。
     *
     * @param sceneId 场景主键
     * @return 运行态快照
     */
    @GetMapping("/runtime")
    @SaCheckPermission("iot:twin:list")
    public R<IotTwinRuntimeResp> runtime(@PathVariable Long sceneId) {
        return R.ok(twinRuntimeService.runtime(sceneId));
    }
}
