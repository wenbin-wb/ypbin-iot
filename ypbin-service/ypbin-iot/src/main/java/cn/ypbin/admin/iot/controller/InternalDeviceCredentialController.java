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

import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyReq;
import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyResp;
import cn.ypbin.admin.iot.service.DeviceCredentialService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备凭据校验内部端点（{@code POST /internal/device-credential/verify}）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （只有 {@code X-Internal-Token}，**没有租户身份**；租户由用户名里的第一段解析后再进上下文，
 * 与 {@code AvailabilityServiceImpl} 的内部端点同一口径）。</p>
 *
 * <p><b>为什么要专门开一个校验端点</b>：本环境未部署 EMQX（资源决策见设计文档末节），
 * 若不给平台侧留校验路径，「吊销后不可用」这条要求就**没有任何可验证的判据**——
 * 只能靠「库里那列非空」自证，那是状态而不是行为。该端点同时是将来 EMQX
 * **HTTP 认证源**（官方支持，设计 §3.5 F31）的后端形态，不需要再发明契约。</p>
 *
 * <p>⚠️ 响应遵循本仓「统一 HTTP 200 + {@code R.code}」惯例：{@code /internal/mqtt/**} 是
 * **唯一**被批准的破例范围（设计 §1.1 决策 D2），本端点不属于它。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@RestController
@RequestMapping("/internal/device-credential")
@RequiredArgsConstructor
public class InternalDeviceCredentialController {

    private final DeviceCredentialService deviceCredentialService;

    /**
     * 校验设备用户名 + 口令。
     *
     * @param req 校验请求（用户名 + 明文口令）
     * @return allow/deny 及拒绝原因码（**不含口令或哈希**）
     */
    @PostMapping("/verify")
    public R<DeviceCredentialVerifyResp> verify(@Valid @RequestBody DeviceCredentialVerifyReq req) {
        return R.ok(deviceCredentialService.verify(req));
    }
}
