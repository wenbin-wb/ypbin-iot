/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备凭据**签发/轮换**响应（一次性明文）。
 *
 * <p>这是整个凭据功能里**唯一**承载明文口令的模型，且只在签发/轮换的响应里出现一次：
 * 服务端与中间件都只存哈希（{@code iot_device_credential.password_hash}），
 * 丢了只能重新签发（= 旧口令立即失效）。</p>
 *
 * <p>⚠️ 因此本类**不得**被写进操作日志、不得进审计快照、不得进异常消息。
 * 端点上的 {@code @Log} 必须显式排除请求体与响应体（见 {@code DeviceCredentialController}）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class DeviceCredentialIssuedResp {

    /** 设备主键。 */
    private Long deviceId;

    /** MQTT 用户名（{@code {tenantId}.{deviceId}}）。 */
    private String username;

    /** 本次签发的版本号（首次为 1，之后每次 +1）。 */
    private Integer credentialVersion;

    /** 本次签发时刻。 */
    private LocalDateTime credentialIssuedAt;

    /** 一次性明文口令：只在本次响应里出现，服务端不保存、无法再次读出。 */
    private String password;
}
