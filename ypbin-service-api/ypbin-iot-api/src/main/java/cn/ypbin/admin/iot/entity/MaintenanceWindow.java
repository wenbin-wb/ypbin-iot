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
 * 维护窗口（M-2）：spec §12.5 口径里「统计总时长**排除**可配置维护窗口」的落点。
 *
 * <p>为什么需要它：计划停机（夜间/周末、设备检修、租约交接）不是故障——用墙钟当分母会把计划停机算成断档，
 * 可用率会被系统性低估。断档时间落在窗口内的部分同样从分子里剔除（否则只缩分母、计划停机仍拉低可用率，
 * 与 spec 的意图相反，见 {@code AvailabilityCalculator} 的说明）。</p>
 *
 * <p>{@code deviceId} 为 {@code null} 表示**该租户全部设备**（交接窗口就是这么开的）。
 * {@code endTs} 为 {@code null} 表示窗口仍在进行（查询时按 {@code now} 结算）。</p>
 *
 * @author wenbin
 * @since 2026-09-23
 */
@Getter
@Setter
@TableName("maintenance_window")
public class MaintenanceWindow extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id）；{@code null}=该租户全部设备。 */
    private Long deviceId;

    /** 窗口开始。 */
    private LocalDateTime startTs;

    /** 窗口结束；{@code null}=进行中。 */
    private LocalDateTime endTs;

    /** 来源码（{@link cn.ypbin.admin.iot.availability.MaintenanceSource#getCode()}，非 ordinal）。 */
    private String source;

    /** 说明（人工窗口写计划停机原因；交接窗口由服务端写节点信息）。 */
    private String reason;
}
