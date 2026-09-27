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
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 通知投递记录（设计 §2.1 表 D）。
 *
 * <p><b>为什么通知要单独一张表</b>：通知是**唯一会出网、会失败、会重试**的环节（设计 §2.1 表 D）。
 * 把「判定结果」与「投递结果」分开存，才能在通知全挂时仍然看得到告警——告警本身不能因为邮件发不出去而丢。
 * 因此投递与状态推进是**分开的事务**（设计 §2.4 投递模型第 1 条）。</p>
 *
 * <p><b>幂等键</b>：{@code instance_id + event + channel + target + 轮次} ⇒ 唯一索引，防重试导致重复投递
 * （邮件重复发送对用户是明显的体验事故，设计 §2.4）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
@TableName("iot_alert_notification")
public class IotAlertNotification extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 告警实例 ID。 */
    private Long instanceId;

    /** 渠道码（{@link cn.ypbin.admin.iot.enums.AlertChannel#getCode()}）。 */
    private String channel;

    /** 收件人标识（用户 id / 邮箱）。 */
    private String target;

    /** 通知事件码（{@link cn.ypbin.admin.iot.enums.AlertNotifyEvent#getCode()}）。 */
    private String event;

    /**
     * 投递状态码（{@link cn.ypbin.admin.iot.enums.AlertNotifyStatus#getCode()}）。
     *
     * <p>⚠️ **与设计的偏差（改列名）**：设计表 D 把这一列叫 {@code status}，但仓内所有实体都继承
     * {@code BaseEntity}，而它已经有一个 {@code status}（业务状态 1 启用/0 停用，TINYINT）⇒ 同名同表
     * 会直接冲突（MyBatis-Plus 会认为是同一个字段）。故本列落库为 {@code notify_status}，
     * 语义与设计完全一致，仅列名不同，已在 {@code docs/ALERTING-DESIGN.md} 登记。</p>
     */
    @TableField("notify_status")
    private String notifyStatus;

    /** 已尝试次数。 */
    private Integer attempt;

    /** 下次重试时刻（退避：30s → 2min → 10min）。 */
    private LocalDateTime nextRetryTs;

    /** 最近一次错误（**原样记录**，不吞；超长截断）。 */
    private String lastError;

    /** 幂等键（{@code instance_id + event + channel + target + 轮次}）→ 唯一索引，防重发。 */
    private String idempotentKey;
}
