/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 逐台设备可用率视图（M-2 口径，见 docs/IOT-PLATFORM-DESIGN.md §5.4）。
 *
 * <p>可用率 = 1 - Σ(断档时长 ∩ 统计窗口) / 窗口时长；断档 = 连续 &gt; K × 采集周期 无有效数据
 * （quality=GOOD）；进行中的断档按查询时刻（数据库时钟）算到窗口末。达标判定是**双条件**：
 * 可用率 ≥ 目标**且**最长单次断档 ≤ max(10 分钟, 10 × 采集周期)。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
public class AvailabilityResp {

    /** 设备 ID。 */
    private Long deviceId;

    /** 统计窗口起点。 */
    private LocalDateTime from;

    /** 统计窗口终点。 */
    private LocalDateTime to;

    /** 窗口时长（秒）。 */
    private long windowSeconds;

    /** 窗口内断档总时长（秒，已按窗口裁剪）。 */
    private long outageSeconds;

    /** 窗口内最长单次断档（秒，已按窗口裁剪）。 */
    private long longestOutageSeconds;

    /** 窗口内断档次数（含进行中的一次）。 */
    private int outageCount;

    /** 可用率（6 位小数）。 */
    private BigDecimal availability;

    /** 是否达标（双条件）。 */
    private Boolean meetsTarget;

    /** 目标可用率（口径常量，随响应返回便于客户端展示与解释）。 */
    private BigDecimal targetAvailability;

    /** 最长单次断档上限（秒）。 */
    private long maxAllowedOutageSeconds;

    /**
     * 维护窗口（永不为 null）。
     *
     * @return 维护窗口列表
     */
    public List<MaintenanceWindowDto> getMaintenanceWindows() {
        return maintenanceWindows == null ? List.of() : maintenanceWindows;
    }

    /**
     * 断档明细（**最新优先**：按开始时间倒序），最多返回 {@code AvailabilityRules.MAX_OUTAGE_ROWS} 条；
     * 超出时置 {@link #truncated}——注意**汇总（可用率/次数/总时长）不受截断影响**（来自精确聚合）。
     */
    private List<OutageEventResp> outages = new ArrayList<>();

    /** 统计**总时长**（秒）= 窗口时长 − 维护窗口时长：可用率的分母（spec §12.5）。 */
    private Long effectiveWindowSeconds;

    /** 窗口内的维护时长（秒）：分母里被排除的计划停机。 */
    private Long maintenanceSeconds;

    /** 被排除的断档（秒）：断档落在维护窗口内的部分（只缩分母会让计划停机仍拉低可用率，故一并剔除）。 */
    private Long outageInMaintenanceSeconds;

    /** 与本设备重叠的维护窗口（最多 {@code AvailabilityRules.MAX_MAINTENANCE_ROWS} 条，用于解释口径）。 */
    private List<MaintenanceWindowDto> maintenanceWindows = new ArrayList<>();

    /** 断档明细是否被截断（窗口内事件数超过返回上限）。 */
    private Boolean truncated;

    /**
     * 断档明细（防御 null）。
     *
     * @return 明细，非 null
     */
    public List<OutageEventResp> getOutages() {
        return outages == null ? List.of() : outages;
    }
}
