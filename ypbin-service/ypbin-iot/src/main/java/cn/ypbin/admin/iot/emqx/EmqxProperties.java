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

    /** 内置库认证链的 id（官方形如 {@code password_based:built_in_database}；冒号在 URL 里需转义）。 */
    public static final String DEFAULT_AUTHENTICATION_ID = "password_based:built_in_database";

    /** 默认连接超时（毫秒）。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;

    /** 默认读超时（毫秒）。 */
    public static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    /** 默认下行 QoS（QoS1 = 至少一次，官方语义可能重复，靠 requestId 幂等）。 */
    public static final int DEFAULT_DOWNLINK_QOS = 1;

    /** 默认下行命令超时（毫秒）：与入站动作的 {@code request_ttl=30s} 对齐，见字段注释。 */
    public static final int DEFAULT_COMMAND_TIMEOUT_MS = 30_000;

    /** 默认超时扫描周期（毫秒）。 */
    public static final int DEFAULT_COMMAND_SCAN_INTERVAL_MS = 15_000;

    /** 默认单轮扫描候选上限（与断档扫描同量级）。 */
    public static final int DEFAULT_COMMAND_SCAN_BATCH_SIZE = 200;

    /** 本环境是否已接入 MQTT broker。 */
    private boolean enabled = false;

    /** 管理面基址（生产机侧出向隧道的地址，见 docs/EMQX-INTEGRATION.md §2）。 */
    private String baseUrl;

    /** 管理面 API Key（真实凭据：只允许来自容器 env，不入库/不入日志）。 */
    private String apiKey;

    /** 管理面 API Secret（同上）。 */
    private String apiSecret;

    /** 内置库认证链 id。 */
    private String authenticationId = DEFAULT_AUTHENTICATION_ID;

    /** 连接超时（毫秒；远程调用必须显式超时）。 */
    @Min(100)
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;

    /** 读超时（毫秒）。 */
    @Min(100)
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

    /** 下行发布 QoS（默认 1）。 */
    @Min(0)
    @Max(2)
    private int downlinkQos = DEFAULT_DOWNLINK_QOS;

    /** 下行发布是否 retain（默认 false：命令类消息不该变成保留消息）。 */
    private boolean downlinkRetain = false;

    /**
     * 下行命令的默认超时（毫秒）。
     *
     * <p><b>为什么默认 30s</b>：与入站动作的 {@code request_ttl=30s} 对齐——更短会在 EMQX 还在重发时就把
     * 命令判超时（"其实还能到"却判失败）；更长会让"设备真没回执"的判据钝化。评审确认口径：
     * **默认超时 ≥ EMQX request_ttl**（本值相等，即最小允许值）。</p>
     */
    @Min(1000)
    private int defaultCommandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS;

    /** 超时扫描周期（毫秒；周期批量扫描，**不自动重试**）。 */
    @Min(1000)
    private int commandScanIntervalMs = DEFAULT_COMMAND_SCAN_INTERVAL_MS;

    /** 单轮超时扫描最多处理的候选数（防一次扫描把库与线程拖住，下轮继续）。 */
    @Min(1)
    @Max(5000)
    private int commandScanBatchSize = DEFAULT_COMMAND_SCAN_BATCH_SIZE;

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
