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

/**
 * 可用率口径的**固定部分**（继承 spec §12.5 / 设计 §5.4）。
 *
 * <ul>
 *   <li>有效数据 = {@code quality=GOOD}；</li>
 *   <li>断档 = 连续 &gt; K × 采集周期 无有效数据（K 可配，见 {@link AvailabilityProperties}）；</li>
 *   <li>可用率（逐台）= 1 - Σ(断档时长 ∩ 统计窗口) / 窗口时长（进行中的断档按查询时刻结算）；</li>
 *   <li>达标是**双条件**：可用率 ≥ {@link #TARGET_AVAILABILITY} 且最长单次断档 ≤
 *       {@code max(10 分钟, 10 × 采集周期)}。</li>
 * </ul>
 *
 * <p>这些是**口径**而非部署参数，故写死为常量并随响应返回（客户端不必重复实现一遍口径，
 * 避免两处口径漂移）。按设备覆盖阈值属后续增量，见 ROADMAP 的四点十二未闭环表。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public final class AvailabilityRules {

    /** 有效数据的质量码（与协议栈 {@code Quality.name()} 对齐；iot 服务不依赖协议栈，故用常量）。 */
    public static final String QUALITY_GOOD = "GOOD";

    /** 目标可用率。 */
    public static final BigDecimal TARGET_AVAILABILITY = new BigDecimal("0.995");

    /** 最长单次断档的下限（秒）：max(10 分钟, 10 × 采集周期)。 */
    public static final long MAX_OUTAGE_FLOOR_SECONDS = 600L;

    /** 最长单次断档的采集周期倍数。 */
    public static final long MAX_OUTAGE_INTERVAL_MULTIPLIER = 10L;

    /** 可用率小数位。 */
    public static final int AVAILABILITY_SCALE = 6;

    /** 断档明细一次返回的最大条数（超出则截断并置 {@code truncated}，避免一个长窗口把响应撑爆）。 */
    public static final int MAX_OUTAGE_ROWS = 200;

    private AvailabilityRules() {
    }

    /**
     * 最长单次断档上限（秒）：{@code max(10 分钟, 10 × 采集周期)}。
     *
     * @param intervalMs 生效采集周期（毫秒，必须为正）
     * @return 上限秒数
     */
    public static long maxAllowedOutageSeconds(long intervalMs) {
        long intervalSeconds = Math.max(1L, Math.round(intervalMs / 1000.0d));
        return Math.max(MAX_OUTAGE_FLOOR_SECONDS, MAX_OUTAGE_INTERVAL_MULTIPLIER * intervalSeconds);
    }
}
