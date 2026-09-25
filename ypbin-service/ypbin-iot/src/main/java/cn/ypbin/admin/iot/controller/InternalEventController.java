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

import cn.ypbin.admin.iot.event.EventIngestReq;
import cn.ypbin.admin.iot.event.EventIngestResult;
import cn.ypbin.admin.iot.service.IotEventService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行期事件上报内部端点（access → iot，G6）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （只有 {@code X-Internal-Token}，没有租户身份；服务侧按设备解析租户后再写，见
 * {@link cn.ypbin.admin.iot.service.impl.IotEventServiceImpl} 的类注释）。</p>
 *
 * <p><b>幂等</b>：上报项必须带 {@code idempotentKey}，重复投递不会产生重复行；
 * 响应里的 {@code duplicated} 明确告诉调用方「哪些被去重了」，便于它安全清理重放队列。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@RestController
@RequestMapping("/internal/events")
@RequiredArgsConstructor
public class InternalEventController {

    private final IotEventService iotEventService;

    /**
     * 接收一批运行期事件。
     *
     * @param req 上报请求（单批上限见 {@link EventIngestReq#MAX_BATCH_SIZE}）
     * @return 落库/去重/丢弃条数
     */
    @PostMapping
    public R<EventIngestResult> ingest(@Valid @RequestBody EventIngestReq req) {
        return R.ok(iotEventService.ingest(req));
    }
}
