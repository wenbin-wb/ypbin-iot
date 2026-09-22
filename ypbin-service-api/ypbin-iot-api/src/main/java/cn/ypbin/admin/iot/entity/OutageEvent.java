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
 * 断档事件（M-2）：可用率的**唯一**数据来源。
 *
 * <p>{@code end_ts} 为 {@code null} 表示断档仍在进行（扫描发现后一直开着，直到下一个有效数据到达）。
 * 可用率不落库（避免派生数据与事实漂移），由 {@code AvailabilityCalculator} 在查询时按窗口现算。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
@TableName("outage_event")
public class OutageEvent extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 断档开始（最后一次有效数据时刻，或首次观测时刻）。 */
    private LocalDateTime startTs;

    /** 断档结束（恢复有效数据的时刻）；{@code null}=进行中。 */
    private LocalDateTime endTs;

    /** 断档时长（秒）；进行中为 {@code null}（按查询时刻现算）。 */
    private Long durationSec;

    /** 原因码（{@link cn.ypbin.admin.iot.availability.OutageReason#getCode()}，非 ordinal）。 */
    private String reason;
}
