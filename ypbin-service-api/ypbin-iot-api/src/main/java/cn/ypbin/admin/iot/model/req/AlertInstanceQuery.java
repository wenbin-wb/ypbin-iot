/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警实例分页查询条件（全局列表与设备详情「告警」页签共用）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertInstanceQuery {

    /** 页码（1 起）。 */
    private Integer page = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;

    /** 状态码筛选（可空；可多值逗号分隔，如 {@code FIRING,ACKED}）。 */
    private String state;

    /** 是否只看活动（{@code true} = FIRING/ACKED/PENDING；与 {@link #state} 同时给出时以 state 为准）。 */
    private Boolean activeOnly;

    /** 级别码筛选（可空）。 */
    private String severity;

    /** 设备 ID 筛选（可空；设备页签固定带）。 */
    private Long deviceId;

    /** 产品 ID 筛选（可空）。 */
    private Long productId;

    /** 点位标识筛选（可空）。 */
    private String propertyId;

    /** 规则 ID 筛选（可空）。 */
    private Long ruleId;

    /** 首次越界时刻起（含，可空）。 */
    private LocalDateTime from;

    /** 首次越界时刻止（含，可空）。 */
    private LocalDateTime to;
}
