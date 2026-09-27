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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行期命令实例（段 B）：**这一次下发的请求与它的回执**。
 *
 * <p>与 {@link IotCommand}（**物模型定义**，挂 {@code service_id}）严格区分：后者是"这类命令长什么样"、
 * 发布后不可变；前者是"什么时候对哪台设备下发了什么、结果如何"、只增不改（除状态机推进）。</p>
 *
 * <p><b>幂等键 {@code uk_iot_command_instance_request(tenant_id, request_id)}</b>：这里的 {@code requestId}
 * 由**平台**生成（与 {@code iot_mqtt_ingest_receipt} 不同——那张表的 requestId 由**设备**生成，所以键里
 * 必须有 device_id）。回执路径按 {@code (tenant_id, request_id)} 更新，先按 {@code device_id} 反查租户。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
@TableName("iot_command_instance")
public class IotCommandInstance extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 物模型命令定义 ID（{@code iot_command.id}；属性类为空）。 */
    private Long commandId;

    /** 命令/属性标识（冗余自物模型，便于无关联查询与审计）。 */
    private String identifier;

    /** 类型码（{@link cn.ypbin.admin.iot.enums.CommandKind#getCode()}，非 ordinal）。 */
    private String kind;

    /** 请求 ID（幂等键；平台生成，随 payload 下发）。 */
    private String requestId;

    /** 下行主题（定向靠主题；官方无按 clientid 定向的端点）。 */
    private String topic;

    /** 下行报文体（JSON，含 requestId）。 */
    private String payload;

    /** 上行回执体（JSON，**原样存储**含设备时间戳与 data；不含凭据）。 */
    private String replyPayload;

    /** 状态码（{@link cn.ypbin.admin.iot.enums.CommandInstanceStatus#getCode()}，非 ordinal）。 */
    private String statusCode;

    /** 失败原因码（{@link cn.ypbin.admin.iot.enums.CommandErrorCode#getCode()}）。 */
    private String errorCode;

    /** 失败说明（面向人的文案，**不含凭据**；设备回执失败时保留设备给的 message）。 */
    private String errorMsg;

    /** 超时（毫秒；取物模型 timeout_ms，缺省用全局默认）。 */
    private Integer timeoutMs;

    /** 已重发次数（仅手动重发计数，不做自动重试）。 */
    private Integer retryCount;

    /** EMQX publish 返回的消息 ID（溯源用）。 */
    private String emqxMessageId;

    /** 来源码（{@link cn.ypbin.admin.iot.enums.CommandSource#getCode()}）。 */
    private String source;

    /** 下发人（来源为控制台时）。 */
    private Long operatorUserId;

    /** 实际投递到 EMQX 的时刻。 */
    private LocalDateTime sentAt;

    /** 终态时刻（**平台落库时间**；设备给的 ts 原样保存在 {@link #replyPayload} 里，两者都留）。 */
    private LocalDateTime finishedAt;
}
