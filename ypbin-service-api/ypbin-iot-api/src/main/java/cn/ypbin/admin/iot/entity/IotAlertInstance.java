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
 * 告警实例（设计 §2.1 表 C）—— 状态机的载体。
 *
 * <p><b>形态直接照 {@code outage_event}</b>（保持仓内一致）：{@code resolved_ts IS NULL} = 仍在活动。</p>
 *
 * <p><b>去重的库级实现（设计 §2.1 里最容易写反的一处）</b>：MySQL 唯一索引对**含 NULL 的行不做约束**
 * ⇒ 把 {@code resolved_ts} 放进唯一索引恰好把「活动告警」（{@code resolved_ts IS NULL}）这一批放走，
 * 去重完全失效（且**不会报错**，只表现为重复告警刷屏）。正确做法是本类的
 * {@link #activeDedupKey}：活动期间 = {@code dedup_key}，恢复时置 {@code NULL}，与 {@code tenant_id}
 * 组成 {@code uk_alert_active(tenant_id, active_dedup_key)}。设计 §2.1 已在本机用 {@code mysql:8.4}
 * 实测两个方向确认过；本仓另有 {@code AlertDedupIndexGateTest}（SQL 文本门禁）把该形状钉住。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
@TableName("iot_alert_instance")
public class IotAlertInstance extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 触发它的规则 ID。
     *
     * <p>断档映射产生的实例用**保留值 0**（见 {@code AlertRules#RULE_ID_OUTAGE}）：设计表 C 要求
     * {@code rule_id} 非空，而断档事件没有规则owner；用保留值而不是 NULL 可以让唯一键与索引保持简单，
     * 页面上把 0 展示成「设备离线（平台断档链路）」。</p>
     */
    private Long ruleId;

    /** 设备 ID。 */
    private Long deviceId;

    /** 点位标识（设备级/产品级/断档类规则触发的实例此处为空）。 */
    private String propertyId;

    /** 去重键：{@code rule_id + device_id + property_id} 规范化后的字符串（**历史留痕用，不进唯一索引**）。 */
    private String dedupKey;

    /** **只在活动期间非 NULL**（= {@code dedup_key} 的值）；恢复时置 NULL。与 {@code tenant_id} 组成唯一键。 */
    private String activeDedupKey;

    /** **冗余存下触发时的级别**（规则后来改了级别，历史实例的级别不能被改写）。 */
    private String severity;

    /** 状态码（{@link cn.ypbin.admin.iot.enums.AlertState#getCode()}）。 */
    private String state;

    /**
     * 连续越界计数（抖动抑制用）。
     *
     * <p>⚠️ **与设计的偏差（增列）**：设计 §2.1 表 C 未列出本列，但设计 §2.3 同时要求
     * ① {@code PENDING} **落库**、② 「不落库的纯内存方案会在评估器重启后丢失计数，导致每次重启都要
     * 重新凑 N 次」。两条要求同时成立就必须把计数持久化，故增列并在 {@code docs/ALERTING-DESIGN.md}
     * 的「与设计的偏差及理由」中登记。</p>
     */
    private Integer consecutiveCount;

    /** 触发时读到的**原始值**（字符串，原样存；用于「当时到底是多少」）。 */
    private String triggerValue;

    /** **触发时的阈值快照**（规则改了阈值后，仍能解释当时为何报警）。 */
    private String thresholdSnapshot;

    /** 首次越界时刻（= {@code PENDING} 开始时刻）。 */
    private LocalDateTime startTs;

    /** 满足持续条件、正式 {@code FIRING} 的时刻。 */
    private LocalDateTime firingTs;

    /** 恢复时刻（{@code null} = 仍活动）。 */
    private LocalDateTime resolvedTs;

    /** 确认时刻。 */
    private LocalDateTime ackedTs;

    /** 确认人。 */
    private Long ackedBy;

    /** 最近一次通知时刻（**重复通知抑制的唯一依据**）。 */
    private LocalDateTime lastNotifiedTs;

    /** 已通知次数（可观测 + 排查「为什么没再通知」）。 */
    private Integer notifyCount;

    /** 结束原因码（{@link cn.ypbin.admin.iot.enums.AlertReason#getCode()}；活动期为 {@code null}）。 */
    private String reason;

    /**
     * 实例级静默截止时刻（{@code null} = 未静默）。
     *
     * <p>⚠️ **与设计的偏差（增列）**：设计只给了「规则自带静默窗口 + 复用 maintenance_window」两级静默
     * （§2.3/§2.4），而用户口径要求「一键静默 1 小时」这种**针对单条告警**的动作。若用规则级静默实现，
     * 会把同一规则下**其它设备**的告警一起静默（误伤面大得多）；用维护窗口实现则要借用
     * {@code iot:maintenance:create} 权限并顺带改动可用率口径。故增列实例级静默截止时刻。
     * <b>它仍然只是「通知侧判定」，不是状态</b>（设计 §2.3：静默做成状态会让「静默中的告警」在页面上
     * 显得像不存在），判定与状态机完全不受它影响。</p>
     */
    private LocalDateTime silenceUntil;
}
