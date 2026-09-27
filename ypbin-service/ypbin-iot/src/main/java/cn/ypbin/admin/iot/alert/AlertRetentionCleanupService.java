/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

/**
 * 告警保留清理（用户已批准口径 7：**活动告警永久保留** + **已恢复保留 180 天（可配）**）。
 *
 * <p>与既有时序保留（{@code RetentionCleanupServiceImpl}）同一范式：数据库时钟算截止时刻、
 * 跨租户执行、批量删除、不删活动数据。区别只有一条：**告警是审计证据**（「上周那台设备到底报警了没有」
 * 只能靠它回答），所以保留期比观测数据长得多，且活动行永不清理。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertRetentionCleanupService {

    /**
     * 清理结果。
     *
     * @param skipped 是否因开关/非法配置而跳过
     * @param deleted 删除的告警实例行数
     */
    record CleanupResult(boolean skipped, int deleted) {

        /** 跳过。 */
        public static CleanupResult skippedResult() {
            return new CleanupResult(true, 0);
        }
    }

    /**
     * 执行一轮清理。
     *
     * @return 结果
     */
    CleanupResult cleanupOnce();
}
