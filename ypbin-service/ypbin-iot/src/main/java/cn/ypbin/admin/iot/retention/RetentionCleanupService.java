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
 * 数据保留清理（D0.8）。
 *
 * <p>语义：按**数据库时钟**计算截止时刻，删除早于截止时刻的断档事件与维护窗口。
 * 「过期」的判据用各自的时间列（断档用 `start_ts`，维护窗口用 `start_ts`）——窗口跨过期边界的情况
 * 不特殊处理：跨界的行会等下一次清理，代价是少删一点（**保守方向**，宁可多留不可误删）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public interface RetentionCleanupService {

    /**
     * 执行一轮清理（可被定时任务与运维手工调用）。
     *
     * @return 清理结果（跳过时 {@code skipped=true}）
     */
    RetentionCleanupResult cleanupOnce();
}
