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
 * 运行期事件实例（G6）：设备上报的一次事件，与物模型事件<b>定义</b>（{@link IotEvent}，挂 {@code service_id}）
 * 是两回事——前者是「什么时候发生了什么」，后者是「这类事件长什么样」。
 *
 * <p><b>为什么不复用 {@code iot_event}</b>：物模型定义随产品草稿态演进、发布后不可变，而运行期实例
 * 是只增不改的流水；把两者塞进一张表会让「发布新版本」与「历史事件」互相牵制。</p>
 *
 * <p><b>幂等由数据库唯一键兜底</b>：{@code uk_iot_event_log_idem(tenant_id, device_id, idempotent_key)}。
 * 服务层先按 {@code idempotent_key} 批量查重（省一次写入），但并发重投只有唯一键拦得住——因此
 * 批量插入用 {@code ON DUPLICATE KEY UPDATE id = id}（命中唯一键时不改任何列，幂等且不抛异常）。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
@TableName("iot_event_log")
public class IotEventLog extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 事件标识（对应物模型 {@code iot_event.identifier}；无对应定义时是上报方自定码）。 */
    private String eventCode;

    /** 事件名称（上报方可选给出，便于无物模型定义时展示）。 */
    private String eventName;

    /** 事件级别码（{@link cn.ypbin.admin.iot.event.EventLevel#getCode()}，非 ordinal）。 */
    private String level;

    /** 事件参数（JSON 文本，由上报方给出；服务端不解析、不校验结构）。 */
    private String params;

    /** 事件发生时刻（上报方给，不是入库时刻）。 */
    private LocalDateTime eventTs;

    /** 幂等键（同租户同设备内唯一）。 */
    private String idempotentKey;
}
