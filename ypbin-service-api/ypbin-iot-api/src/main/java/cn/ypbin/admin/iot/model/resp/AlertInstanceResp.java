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
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警实例视图（全局列表、设备页签、展开行共用同一份）。
 *
 * <p>展开行要看的四件事必须在**同一份响应**里给全，否则前端展开时还得再发 3 个请求：
 * 触发值 / 阈值快照 / 时间线（start→firing→acked→resolved）/ 投递记录。
 * 曲线缩略**不在这里**——它复用既有的历史曲线端点（{@code /iot/devices/{id}/series}），
 * 不另造一套查询（与「历史曲线」页签复用同一抽屉同一取向）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertInstanceResp {

    /** 实例 ID。 */
    private Long id;

    /** 规则 ID（断档类为 0）。 */
    private Long ruleId;

    /** 规则名（断档类显示为平台断档链路；可空）。 */
    private String ruleName;

    /** 是否断档类（{@code ruleId == 0}；页面据此显示「设备离线」而不是「规则 0」）。 */
    private Boolean outage;

    /** 设备 ID。 */
    private Long deviceId;

    /** 设备名。 */
    private String deviceName;

    /** 设备编码。 */
    private String deviceCode;

    /** 产品 ID。 */
    private Long productId;

    /** 点位标识（设备级/断档类为空）。 */
    private String propertyId;

    /** 级别码。 */
    private String severity;

    /** 状态码。 */
    private String state;

    /** 触发时读到的原始值（**原样**，不美化、不四舍五入）。 */
    private String triggerValue;

    /** 触发时的阈值快照。 */
    private String thresholdSnapshot;

    /** 首次越界时刻（pending 开始）。 */
    private LocalDateTime startTs;

    /** 正式触发时刻。 */
    private LocalDateTime firingTs;

    /** 恢复时刻。 */
    private LocalDateTime resolvedTs;

    /** 确认时刻。 */
    private LocalDateTime ackedTs;

    /** 确认人。 */
    private Long ackedBy;

    /** 最近一次通知时刻。 */
    private LocalDateTime lastNotifiedTs;

    /** 已生成通知次数（投递结果见 {@link #notifications}）。 */
    private Integer notifyCount;

    /** 结束原因码（可空）。 */
    private String reason;

    /** 实例级静默截止时刻（可空 = 未静默）。 */
    private LocalDateTime silenceUntil;

    /** 已持续时长（秒；活动告警算到「现在」，已恢复算到恢复时刻）。 */
    private Long durationSeconds;

    /** 通知投递记录（展开行展示「站内信/邮件记录」）。 */
    private List<AlertNotificationResp> notifications;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
