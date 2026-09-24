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
 * 保留清理结果（一轮清理删了多少行）。
 *
 * @param outageEvents      删除的断档事件行数
 * @param maintenanceWindows 删除的维护窗口行数
 * @param skipped           是否因未启用/配置非法而跳过
 * @author wenbin
 * @since 2026-09-24
 */
public record RetentionCleanupResult(int outageEvents, int maintenanceWindows, boolean skipped) {

    /** 空结果（跳过时的返回，避免调用方拿到 null）。 */
    public static RetentionCleanupResult skippedResult() {
        return new RetentionCleanupResult(0, 0, true);
    }

    /** 本轮删除总行数。 */
    public int total() {
        return outageEvents + maintenanceWindows;
    }
}
