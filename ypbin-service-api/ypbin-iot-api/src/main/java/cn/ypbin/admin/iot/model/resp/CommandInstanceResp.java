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
 * 命令实例视图（下发响应与历史列表共用；**不含任何凭据**）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
public class CommandInstanceResp {

    /** 主键。 */
    private Long id;

    /** 设备 ID。 */
    private Long deviceId;

    /** 类型码。 */
    private String kind;

    /** 目标标识。 */
    private String identifier;

    /** 请求 ID（幂等键）。 */
    private String requestId;

    /** 客户端幂等键（看板 #11 O-7 C2；创建时提供则回显）。 */
    private String clientRequestId;

    /** 下行主题。 */
    private String topic;

    /** 下行报文体。 */
    private String payload;

    /** 上行回执体（原样保存）。 */
    private String replyPayload;

    /** 状态码。 */
    private String statusCode;

    /** 失败原因码。 */
    private String errorCode;

    /** 失败说明。 */
    private String errorMsg;

    /** 超时（毫秒）。 */
    private Integer timeoutMs;

    /** 已重发次数。 */
    private Integer retryCount;

    /** EMQX 消息 ID。 */
    private String emqxMessageId;

    /** 来源码。 */
    private String source;

    /** 下发人。 */
    private Long operatorUserId;

    /** 投递时刻。 */
    private LocalDateTime sentAt;

    /** 终态时刻（平台时间）。 */
    private LocalDateTime finishedAt;

    /** 创建时刻。 */
    private LocalDateTime createTime;
}
