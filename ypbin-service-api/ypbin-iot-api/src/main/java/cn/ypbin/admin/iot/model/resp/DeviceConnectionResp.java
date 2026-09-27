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

import lombok.Getter;
import lombok.Setter;

/**
 * 设备**接入信息**响应（装配「接入信息」弹窗与二维码用；**不含口令**）。
 *
 * <p>之所以要单独一个端点：设备侧要用到的信息除了用户名还有 broker 地址、clientId、
 * 上下行主题前缀——把它们散在客户端拼装，就会出现「平台改了主题规范、设备侧脚本没跟着改」
 * 的静默不一致。这里由服务端按 {@code DeviceMqttNaming} 统一给出。</p>
 *
 * <p><b>broker 字段的可信度边界（如实说明）</b>：{@code emqxEnabled=false} 时 broker 主机/端口
 * 可能为空——那是**真实状态**（EMQX 尚未接入本环境），不是占位值；客户端必须据此提示
 * 「当前环境未接入 broker」，而不是拿空地址去连。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class DeviceConnectionResp {

    /** 设备主键。 */
    private Long deviceId;

    /** MQTT 用户名（{@code {tenantId}.{deviceId}}）。 */
    private String username;

    /** clientId（与用户名同源）。 */
    private String clientId;

    /** 上行主题前缀（{@code ypbin/v1/{tenantId}/{deviceId}/up/}）。 */
    private String topicUpPrefix;

    /** 下行主题前缀（{@code ypbin/v1/{tenantId}/{deviceId}/down/}）。 */
    private String topicDownPrefix;

    /** 是否已签发凭据（未签发时设备无法连接，客户端应先引导签发）。 */
    private Boolean credentialIssued;

    /** 当前凭据版本号；未签发时为空。 */
    private Integer credentialVersion;

    /** 当前凭据是否可用（已签发且未吊销）。 */
    private Boolean credentialValid;

    /** 本环境是否已接入 MQTT broker（{@code ypbin.emqx.enabled}）；false 时下面的地址字段无意义。 */
    private Boolean emqxEnabled;

    /** broker 主机（未接入时为空）。 */
    private String brokerHost;

    /** broker 端口（未接入时为空）。 */
    private Integer brokerPort;

    /** broker 是否启用 TLS（未接入时为空）。 */
    private Boolean brokerTlsEnabled;
}
