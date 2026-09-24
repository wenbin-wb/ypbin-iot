/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 历史时序查询请求（§5.2.1 查询路径）。
 *
 * <p>{@code from}/{@code to} 用 epoch 毫秒（与上报契约同一口径，跨服务不用字符串时间）；
 * {@code limit} 有上限（默认 1000、最大 5000），防止把整段时间序列拉进内存。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class TimeSeriesQueryReq {

    /** 默认返回条数。 */
    public static final int DEFAULT_LIMIT = 1_000;

    /** 单次查询最大条数。 */
    public static final int MAX_LIMIT = 5_000;

    /** 点位标识（必填——不按点位查会把设备的全部点位混在一起）。 */
    @NotBlank(message = "点位不能为空")
    private String propertyId;

    /** 起始时刻（epoch 毫秒，含）。 */
    private Long from;

    /** 结束时刻（epoch 毫秒，含）。 */
    private Long to;

    /** 返回条数上限（默认 {@value #DEFAULT_LIMIT}，最大 {@value #MAX_LIMIT}）。 */
    @Min(value = 1, message = "limit 必须为正数")
    @Max(value = MAX_LIMIT, message = "limit 超过上限")
    private Integer limit = DEFAULT_LIMIT;

    public String getPropertyId() {
        return propertyId;
    }

    public void setPropertyId(String propertyId) {
        this.propertyId = propertyId;
    }

    public Long getFrom() {
        return from;
    }

    public void setFrom(Long from) {
        this.from = from;
    }

    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
