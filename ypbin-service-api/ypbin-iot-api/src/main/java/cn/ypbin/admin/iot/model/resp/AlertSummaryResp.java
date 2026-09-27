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
 * 告警概览摘要（设备详情「概览」页签的一行摘要 + 全局列表页顶部计数）。
 *
 * <p>计数用**一次聚合查询**给出（不是让前端拿列表长度当计数：列表是分页的，长度等于每页条数）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertSummaryResp {

    /** 活动告警总数（PENDING + FIRING + ACKED）。 */
    private Long activeCount;

    /** 待满足条件的候选数（PENDING；正常应接近 0，因为它只活几个周期）。 */
    private Long pendingCount;

    /** 已触发未确认（FIRING）。 */
    private Long firingCount;

    /** 已确认未恢复（ACKED）。 */
    private Long ackedCount;

    /** 严重级别且活动。 */
    private Long criticalCount;

    /** 警告级别且活动。 */
    private Long warningCount;

    /** 提示级别且活动。 */
    private Long infoCount;

    /** 最近 24 小时恢复数（判断「是不是刚消停」）。 */
    private Long resolvedLast24h;
}
