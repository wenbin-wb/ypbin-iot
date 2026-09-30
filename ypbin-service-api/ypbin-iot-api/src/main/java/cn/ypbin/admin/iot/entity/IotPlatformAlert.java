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
 * 平台自告警实例（看板 #10，设计 `docs/PLATFORM-ALERTING-DESIGN.md` §3.1）。
 *
 * <p><b>为什么与设备告警 {@code iot_alert_instance} 分成两张表</b>（设计 §1.3）：
 * 设备告警以 `ruleId` + `deviceId` + `propertyId` 为键，而平台健康规则
 * **没有设备、也没有点位**（判定对象是平台自身）。硬塞会污染设备告警的语义与索引：
 * `property_id` 是点位告警语义的组成部分，给平台规则填无意义值等于让"按设备/点位查告警"
 * 这条最常用的路径上混入永远不该出现的行。</p>
 *
 * <p><b>去重照 {@code IotAlertInstance} 的成熟形态</b>（该形态在本仓已由真实库实测并从两个方向确认）：
 * MySQL 唯一索引**对含 NULL 的行不做约束** ⇒ 若把 `resolved_ts` 放进唯一索引，
 * "活动中的告警"（`resolved_ts IS NULL`）会**整批逃出去重**，且**不报错**——只表现为重复告警刷屏。
 * 正确做法是 {@link #activeDedupKey}：活动期间 = `dedupKey`，恢复时置 `NULL`，
 * 与 `tenant_id` 组成 {@code uk_platform_alert_active(tenant_id, active_dedup_key)}。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
@TableName("iot_platform_alert")
public class IotPlatformAlert extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 规则码（`PlatformHealthRule.code`）。 */
    private String ruleCode;

    /** 去重键（规则码 + 维度），不随状态变化。 */
    private String dedupKey;

    /** 只在活动期间非 NULL（= `dedupKey`）；恢复时置 NULL ⇒ 与 tenant_id 唯一 ⇒ 同键至多一条活动告警。 */
    private String activeDedupKey;

    /** 严重度码（`PlatformSeverity.code`）。 */
    private String severity;

    /** 状态码（`PlatformAlertState.code`）：PENDING/FIRING/RESOLVED。 */
    private String state;

    /** 面向运维的一句话（**含实际值**，如"评估器已 60000ms 未成功（阈值 45000ms）"）。 */
    private String summary;

    /**
     * 判定时的指标快照（JSON）。
     *
     * <p><b>为什么必须留这个</b>：事后排障时唯一能回答"当时到底是多少"的依据。
     * 只存 summary 的话，等值班来看时指标早已变化，无法判断当时是否真的异常。</p>
     */
    private String metricSnapshot;

    /** 首次观测到异常的时刻。 */
    private LocalDateTime startTs;

    /** 确认触发的时刻（连续 N 轮后）。 */
    private LocalDateTime firingTs;

    /** 恢复时刻（NULL = 仍活动）。 */
    private LocalDateTime resolvedTs;

    /** 最近一轮判定的结果（便于看"还在恶化还是在好转"）。 */
    private String lastVerdict;

    /** 累计判定轮次（活动期间每轮 +1）。 */
    private Integer observedRounds;
}
