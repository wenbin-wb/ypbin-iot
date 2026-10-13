/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

/**
 * 保留清理结果（一轮清理删了多少行 / 是否执行 / 耗时）。
 *
 * <p>#227 起新增 {@code skipReason} 与 {@code durationMs}：手工触发端点要**如实**告诉运维
 * 「这轮到底跑没跑、为什么没跑、跑了多久」，只回删除行数不足以区分
 * 「开关关了」「配置非法」「上一轮还在跑」三种情况（见 {@link RetentionSkipReason}）。</p>
 *
 * @param outageEvents       删除的断档事件行数
 * @param maintenanceWindows 删除的维护窗口行数
 * @param skipReason         未执行原因；已执行时为 {@link RetentionSkipReason#NONE}
 * @param durationMs         本轮耗时（毫秒；跳过时也如实记录判定耗时）
 * @author wenbin
 * @since 2026-09-24
 */
public record RetentionCleanupResult(int outageEvents, int maintenanceWindows,
                                     RetentionSkipReason skipReason, long durationMs) {

    /**
     * 规范化：{@code skipReason} 为空按「已执行」处理；耗时不允许为负。
     */
    public RetentionCleanupResult {
        if (skipReason == null) {
            skipReason = RetentionSkipReason.NONE;
        }
        if (durationMs < 0) {
            durationMs = 0L;
        }
    }

    /**
     * 已执行的一轮结果。
     *
     * @param outageEvents       删除的断档事件行数
     * @param maintenanceWindows 删除的维护窗口行数
     * @param durationMs         耗时（毫秒）
     * @return 结果
     */
    public static RetentionCleanupResult executed(int outageEvents, int maintenanceWindows, long durationMs) {
        return new RetentionCleanupResult(outageEvents, maintenanceWindows, RetentionSkipReason.NONE, durationMs);
    }

    /**
     * 未执行的一轮结果（必须给出非 {@code NONE} 的原因，避免调用方误读成「跑了但没删到」）。
     *
     * @param reason     未执行原因
     * @param durationMs 判定耗时（毫秒）
     * @return 结果
     */
    public static RetentionCleanupResult skipped(RetentionSkipReason reason, long durationMs) {
        if (reason == null || reason == RetentionSkipReason.NONE) {
            throw new IllegalArgumentException("skipped 结果必须给出非 NONE 的 skipReason");
        }
        return new RetentionCleanupResult(0, 0, reason, durationMs);
    }

    /**
     * 本轮是否未执行（被开关/配置/防重入拦下）。
     *
     * @return 未执行返回 {@code true}
     */
    public boolean skipped() {
        return skipReason != RetentionSkipReason.NONE;
    }

    /**
     * 本轮删除总行数。
     *
     * @return 删除总行数
     */
    public int total() {
        return outageEvents + maintenanceWindows;
    }
}
