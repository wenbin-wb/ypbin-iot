/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备凭据的**秘密侧**存储（只存哈希，明文只在签发响应里出现一次）。
 *
 * <p><b>为什么单独一张表，而不是往 {@code iot_device} 上加一列</b>：
 * {@code iot_device} 的实体在各种读路径（分页、详情、接入规格下发）里被整体序列化或整体下发，
 * 秘密一旦成为它的字段，泄露就不再取决于「我这次有没有 set」，而取决于「下游有没有人顺手序列化」。
 * 把秘密放在**从不参与设备查询**的表里，泄露面是结构性封死的；代价是签发/轮换/校验各自多一次
 * 按 {@code device_id} 的等值查询。</p>
 *
 * <p><b>每个设备一行</b>（唯一键 {@code (tenant_id, device_id)}）：轮换 = 原地更新版本与秘密，
 * 吊销 = 清空秘密列（{@code password_hash} 置空 ⇒ 任何口令都匹配不上），
 * 因此本表**不需要删除语义**，也就不依赖逻辑删除：见 {@code docs/DEVICE-CREDENTIAL.md}。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
@TableName("iot_device_credential")
public class IotDeviceCredential extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（{@code iot_device.id}）。 */
    private Long deviceId;

    /** 本行凭据对应的版本号（必须与 {@code iot_device.credential_version} 一致，否则视为过期）。 */
    private Integer credentialVersion;

    /** MQTT 用户名（{@code {tenantId}.{deviceId}}，设计 §5.3 的稳定标识）。 */
    private String username;

    /** 口令哈希算法（含盐位置），取值见 {@code DevicePasswordHasher#ALGO_SHA256_SUFFIX}。 */
    private String passwordAlgo;

    /** 盐（hex）；吊销后置空字符串。 */
    private String passwordSalt;

    /** 口令哈希（hex）；吊销后置空字符串（空哈希不可能与任何口令匹配）。 */
    private String passwordHash;
}
