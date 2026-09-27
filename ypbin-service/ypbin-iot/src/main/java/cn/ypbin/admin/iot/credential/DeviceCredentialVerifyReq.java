/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.credential;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备凭据校验请求（内部端点入参：{@code POST /internal/device-credential/verify}）。
 *
 * <p>形态对齐 EMQX 官方 **HTTP 认证源** 的请求字段（设计 §3.5 F31：官方请求模板里就有
 * {@code ${username}} 与口令），这样 broker 接入后可直接把该端点当认证后端用，
 * 不需要再发明一套契约。</p>
 *
 * <p>⚠️ {@code password} 是明文口令，**不得**出现在日志、异常消息或审计快照里
 * （端点实现只记设备 id 与版本号）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class DeviceCredentialVerifyReq {

    /** MQTT 用户名（{@code {tenantId}.{deviceId}}）。 */
    @NotBlank
    private String username;

    /** 待校验的明文口令。 */
    @NotBlank
    private String password;
}
