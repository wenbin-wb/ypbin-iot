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
 * 告警规则主表（设计 §2.1 表 A）。
 *
 * <p><b>一条规则 = 一个作用域 + 一组点位条件</b>：点位条件在
 * {@link IotAlertRulePoint}（独立行表，为将来的复合条件留位置，本期只启用单点位条件）。
 * <b>零条件行是一条合法规则</b>，其语义是「设备离线/数据中断告警」——它不需要阈值，
 * 判定由平台既有的断档链路（{@code outage_event} + {@code OutageScanner}）承担，
 * 本规则只决定级别、通知对象与通知节奏（设计 §2.6 对「设备离线类告警」的取舍：**不新造判定**）。</p>
 *
 * <p><b>停用不删除</b>：{@code enabled=0} 保留规则与历史实例（设计 §2.1）。停用时其活动实例会被
 * 收口为 {@code RESOLVED/RULE_DISABLED}，否则会留下永不消解的幽灵告警。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
@TableName("iot_alert_rule")
public class IotAlertRule extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 规则名（展示用；同名不禁止，靠 id 区分）。 */
    private String ruleName;

    /** 作用域码（{@link cn.ypbin.admin.iot.enums.AlertScopeType#getCode()}，非 ordinal）。 */
    private String scopeType;

    /** 作用域产品 ID（{@code PRODUCT}/{@code POINT} 时必填；其余必须为空）。 */
    private Long scopeProductId;

    /** 作用域设备 ID（{@code DEVICE}/{@code POINT} 时必填；其余必须为空）。 */
    private Long scopeDeviceId;

    /** 级别码（{@link cn.ypbin.admin.iot.enums.AlertSeverity#getCode()}）。 */
    private String severity;

    /** 启用开关（**停用不删除**：保留历史与解释能力）。 */
    private Boolean enabled;

    /** 抖动抑制模式码（{@link cn.ypbin.admin.iot.enums.AlertTriggerMode#getCode()}）。 */
    private String triggerMode;

    /**
     * 抖动抑制参数：{@code CONSECUTIVE_COUNT} 的 N（默认 3）、{@code DURATION} 的秒数；
     * {@code IMMEDIATE} 时忽略（存 0）。
     */
    private Integer triggerThreshold;

    /**
     * {@code pending} 态的**最大挂起时长**（秒）：超过则放弃本次候选。
     *
     * <p>防的是「一年前越界一次 + 今天越界一次」被拼成「连续 2 次」——没有它，抖动抑制会在稀疏数据上
     * 退化成「迟早会触发」。</p>
     */
    private Integer pendingTtlSec;

    /** 重复通知抑制：活动期内未恢复时的重发间隔（秒，默认 1800）。 */
    private Integer repeatIntervalSec;

    /** 规则自带的静默窗口起（与 {@code maintenance_window} 取并集；可空=不静默）。 */
    private LocalDateTime silenceStart;

    /** 规则自带的静默窗口止（可空；只有起没有止时视为「只静默起点之后」，见服务层说明）。 */
    private LocalDateTime silenceEnd;

    /** 通知渠道码集合（逗号分隔：{@code INBOX} / {@code EMAIL}）。 */
    private String notifyChannels;

    /** 收件人（用户 id 列表 / 邮箱列表，逗号分隔）；{@code null} = 规则创建者。 */
    private String notifyTargets;

    /** 说明（人会读的那一句）。 */
    private String description;
}
