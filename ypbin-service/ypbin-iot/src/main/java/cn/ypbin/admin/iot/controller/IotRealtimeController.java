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

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.ypbin.admin.iot.realtime.IotRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * IoT 实时事件流端点（SSE）。
 *
 * <p>事件名与负载契约见 {@link IotRealtimeService}。订阅按请求线程的租户上下文隔离：
 * 租户用户只收本租户事件，平台用户（无租户上下文）收全量。仅要求登录（{@link SaCheckLogin}）：
 * 数据面已按租户过滤，不需要额外业务权限码。</p>
 *
 * <p><b>路径与鉴权口径**保持不变**</b>（`GET /iot/realtime/stream` + `@SaCheckLogin`）：
 * 2026-10-15 task-18 把连接管理/心跳/跨实例扇出上收到 ypbin-starter，但**对外契约一字未变**——
 * 前端 `EventSource` 连的仍是本路径，事件名仍为 latest / device-status / alert。</p>
 *
 * <p><b>为什么不复用 starter 的通用订阅端点</b>：starter 的 `/ypbin/sse/subscribe` 由它能识别的
 * `SseUserIdResolver` 鉴权，与 IoT 的业务契约（本路径 + Sa-Token 登录）不同。同时暴露两个端点
 * 会多出一个绕过业务鉴权口径的入口，故在 `deploy/nacos/ypbin-iot.yaml` 里显式设置
 * `ypbin.sse.register-endpoint=false`，只保留本控制器。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@RestController
@RequestMapping("/realtime")
@RequiredArgsConstructor
public class IotRealtimeController {

    private final IotRealtimeService realtimeService;

    /**
     * 订阅实时事件流。
     *
     * @return SSE 发射器（事件：latest / device-status / alert；15s 心跳 comment）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckLogin
    public SseEmitter stream() {
        return realtimeService.subscribe();
    }
}
