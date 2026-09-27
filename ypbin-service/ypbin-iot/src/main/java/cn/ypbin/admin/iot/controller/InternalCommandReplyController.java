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

import cn.ypbin.admin.iot.command.CommandReplyReq;
import cn.ypbin.admin.iot.command.CommandReplyResult;
import cn.ypbin.admin.iot.service.CommandInstanceService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备命令回执内部端点（{@code up/reply} 回流，设计 §7.1）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由既有 {@code InternalTokenGuardWebConfig} 的守卫保护
 * （只有 {@code X-Internal-Token}、**没有租户身份**；服务侧按 {@code deviceId} 反查租户后再写，
 * 与 {@code AvailabilityServiceImpl}/{@code IotEventServiceImpl} 同构）。</p>
 *
 * <p><b>响应遵循本仓惯例</b>（HTTP 200 + {@code R.code}）：{@code /internal/mqtt/**} 是**唯一**被批准的
 * 真状态码破例范围（设计决策 D2），本端点**不属于它**。EMQX 侧对该端点的重试由规则/动作配置决定，
 * 语义上重复回执是**幂等**的（重复不改终态），因此不需要 4xx 来阻止重试。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@RestController
@RequestMapping("/internal/command-replies")
@RequiredArgsConstructor
public class InternalCommandReplyController {

    private final CommandInstanceService commandInstanceService;

    /**
     * 受理一条设备回执。
     *
     * @param req 回执（{@code deviceId}/{@code requestId}/{@code code}/{@code message}/{@code data}/{@code ts}）
     * @return 受理结果（accepted/duplicated/discarded）
     */
    @PostMapping
    public R<CommandReplyResult> reply(@Valid @RequestBody CommandReplyReq req) {
        return R.ok(commandInstanceService.applyReply(req));
    }
}
