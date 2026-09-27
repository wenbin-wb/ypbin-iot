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
 * 设备凭据**元信息**响应（查看端点）。
 *
 * <p><b>绝不返回口令或哈希</b>——这不是「本次没 set」的约定，而是结构上不可能：
 * 本类没有、也不允许有承载秘密的字段（由 {@code DeviceCredentialSecretLeakTest} 用源码+序列化双向钉住）。
 * 口令只在签发那一次响应里出现，丢了就轮换（见 {@link DeviceCredentialIssuedResp}）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class DeviceCredentialResp {

    /** 设备主键。 */
    private Long deviceId;

    /** MQTT 用户名（{@code {tenantId}.{deviceId}}；未签发时为空）。 */
    private String username;

    /** 凭据版本号；{@code null} 表示从未签发。 */
    private Integer credentialVersion;

    /** 当前凭据签发时刻；未签发时为空。 */
    private LocalDateTime credentialIssuedAt;

    /** 凭据吊销时刻；未吊销时为空。 */
    private LocalDateTime credentialRevokedAt;

    /** 是否已签发（{@code credentialVersion} 与凭据引用都非空）。 */
    private Boolean issued;

    /** 是否**当前可用**（已签发且未吊销）。不等价于「口令正确」——那是校验端点的判据。 */
    private Boolean valid;
}
