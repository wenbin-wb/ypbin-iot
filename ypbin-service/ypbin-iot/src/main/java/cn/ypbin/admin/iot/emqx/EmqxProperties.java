/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

import cn.ypbin.admin.iot.credential.DevicePasswordGenerator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * EMQX 接入参数（前缀 {@code ypbin.emqx}；键名与 {@code docs/EMQX-INGRESS-DESIGN.md} §8.4 一致）。
 *
 * <p><b>本轮只落「平台自持凭据」与「接入信息装配」需要的那几项</b>：
 * 设备口令长度、broker 地址（供「接入信息」弹窗与二维码）。管理面 API Key / 下发 QoS 等
 * 要等 EMQX 真正接入时再加——先把不需要 broker 的部分做出来，是决策 D3「降级形态」的落点。</p>
 *
 * <p><b>{@code enabled=false} 不等于「静默降级」</b>：它表示「本环境没有 broker」，
 * 接入信息端点会如实回 {@code emqxEnabled=false}（客户端据此提示未接入），而**平台侧凭据的
 * 签发/校验完全不依赖 broker**（哈希在平台自持），因此签发/查看/重置/吊销四个端点在
 * {@code enabled=false} 下**照常可用**。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = EmqxProperties.PREFIX)
public class EmqxProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.emqx";

    /** 默认设备口令长度（字节）：32 字节 = 256 位熵 ⇒ base64url 43 字符（设计 §5.3/§8.4）。 */
    public static final int DEFAULT_CREDENTIAL_PASSWORD_LENGTH = 32;

    /** 本环境是否已接入 MQTT broker。 */
    private boolean enabled = false;

    /** broker 主机（供接入信息装配；未接入时为空）。 */
    private String brokerHost;

    /** broker 端口（供接入信息装配；未接入时为空）。 */
    private Integer brokerPort;

    /** broker 是否启用 TLS（1883=false / 8883=true）。 */
    private boolean brokerTlsEnabled = false;

    /**
     * 设备口令随机字节数。
     *
     * <p>范围由 {@link DevicePasswordGenerator} 的上下界约束：配小了直接拒绝绑定
     * （{@code @Min}/{@code @Max} 在绑定期即失败，不等到第一次签发才发现）。</p>
     */
    @Min(DevicePasswordGenerator.MIN_PASSWORD_BYTES)
    @Max(DevicePasswordGenerator.MAX_PASSWORD_BYTES)
    private int credentialPasswordLength = DEFAULT_CREDENTIAL_PASSWORD_LENGTH;
}
