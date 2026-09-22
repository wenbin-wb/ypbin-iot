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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可用率计算（纯逻辑，无 IO）：把断档窗口按统计窗口裁剪后聚合。
 *
 * <p>口径见 {@link AvailabilityRules}。三处刻意写死的边界：</p>
 * <ol>
 *   <li><b>窗口时长为 0</b>（{@code from >= to}，通常是把区间给反了）：不判不达标、可用率记 100%——
 *       否则「参数写反」会伪装成「设备全窗口断档」；</li>
 *   <li><b>断档与窗口求交</b>：只统计落在窗口内的那一段，跨窗口的长断档不会被整段计入；</li>
 *   <li><b>防御性封顶</b>：明细重叠或脏数据导致断档合计超过窗口时，按窗口上限截断，保证可用率 ≥ 0
 *       （宁可高估可用率也不给出负数这种无意义的值，且该情形由 {@code AvailabilityServiceImpl} 记录告警）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public final class AvailabilityCalculator {

    private AvailabilityCalculator() {
    }

    /**
     * 断档窗口：{@code end} 为 {@code null} 表示进行中（按 {@code now} 结算）。
     *
     * @param start 断档开始
     * @param end   断档结束（可空=进行中）
     */
    public record OutageWindow(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 可用率汇总。
     *
     * @param windowSeconds          窗口时长（秒）
     * @param outageSeconds          窗口内断档合计（秒，已裁剪）
     * @param longestOutageSeconds   窗口内最长单次断档（秒，已裁剪）
     * @param outageCount            窗口内断档次数
     * @param availability           可用率
     * @param meetsTarget            是否达标（双条件）
     * @param maxAllowedOutageSeconds 最长单次断档上限（秒）
     * @param truncated              明细是否被截断（可能低估断档 ⇒ 可用率偏高）
     */
    public record Summary(long windowSeconds, long outageSeconds, long longestOutageSeconds, int outageCount,
                          BigDecimal availability, boolean meetsTarget, long maxAllowedOutageSeconds,
                          boolean truncated) {
    }

    /**
     * 计算可用率汇总。
     *
     * @param from       统计窗口起点
     * @param to         统计窗口终点
     * @param now        当前时刻（进行中的断档结算到它；通常取数据库时钟）
     * @param windows    断档窗口清单（可空=无断档）
     * @param intervalMs 生效采集周期（毫秒）
     * @param truncated  明细是否被截断
     * @return 汇总
     */
    public static Summary summarize(LocalDateTime from, LocalDateTime to, LocalDateTime now,
                                    List<OutageWindow> windows, long intervalMs, boolean truncated) {
        long maxAllowed = AvailabilityRules.maxAllowedOutageSeconds(intervalMs);
        long windowSeconds = Math.max(0L, Duration.between(from, to).getSeconds());
        if (windowSeconds == 0L) {
            return new Summary(0L, 0L, 0L, 0, BigDecimal.ONE.setScale(AvailabilityRules.AVAILABILITY_SCALE),
                true, maxAllowed, truncated);
        }
        long total = 0L;
        long longest = 0L;
        int count = 0;
        if (windows != null) {
            for (OutageWindow window : windows) {
                long overlap = overlapSeconds(window, from, to, now);
                if (overlap <= 0L) {
                    continue;
                }
                total += overlap;
                count++;
                longest = Math.max(longest, overlap);
            }
        }
        if (total > windowSeconds) {
            total = windowSeconds;
        }
        BigDecimal availability = BigDecimal.ONE.subtract(BigDecimal.valueOf(total)
            .divide(BigDecimal.valueOf(windowSeconds), AvailabilityRules.AVAILABILITY_SCALE,
                RoundingMode.HALF_UP));
        if (availability.signum() < 0) {
            availability = BigDecimal.ZERO.setScale(AvailabilityRules.AVAILABILITY_SCALE);
        }
        boolean meets = availability.compareTo(AvailabilityRules.TARGET_AVAILABILITY) >= 0
            && longest <= maxAllowed;
        return new Summary(windowSeconds, total, longest, count, availability, meets, maxAllowed, truncated);
    }

    /**
     * 单个断档窗口与统计窗口的交集秒数。
     *
     * @param window 断档窗口
     * @param from   统计窗口起点
     * @param to     统计窗口终点
     * @param now    当前时刻（进行中的断档用它当结束）
     * @return 交集秒数（≤0 表示无交集）
     */
    private static long overlapSeconds(OutageWindow window, LocalDateTime from, LocalDateTime to,
                                       LocalDateTime now) {
        if (window == null || window.start() == null || now == null) {
            return 0L;
        }
        LocalDateTime end = window.end() != null ? window.end() : now;
        LocalDateTime clampStart = window.start().isBefore(from) ? from : window.start();
        LocalDateTime clampEnd = end.isAfter(to) ? to : end;
        return Duration.between(clampStart, clampEnd).getSeconds();
    }
}
