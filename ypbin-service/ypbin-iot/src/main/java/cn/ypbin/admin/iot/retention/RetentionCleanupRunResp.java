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
 * 手工触发保留清理的响应（#227）。
 *
 * <p>字段读法：<b>{@code executed}</b> 才是「这轮到底跑没跑」的唯一权威；{@code code=200} 只代表
 * 请求被正确处理，**不代表执行了清理**。未执行时 {@code skipReason} 给出有界原因码
 * （见 {@link RetentionSkipReason}）：{@code disabled}（开关关闭）、{@code invalid-config}
 * （保留天数非法）、{@code already-running}（上一轮未结束，防重入跳过）。</p>
 *
 * @param executed          是否真正执行了一轮清理
 * @param skipReason        未执行原因码；已执行时为 {@code none}
 * @param outageEvents      本次删除的断档事件行数
 * @param maintenanceWindows 本次删除的维护窗口行数
 * @param totalDeleted      本次删除总行数
 * @param durationMs        本次耗时（毫秒）
 * @author wenbin
 * @since 2026-10-13
 */
public record RetentionCleanupRunResp(boolean executed, String skipReason,
                                      int outageEvents, int maintenanceWindows,
                                      int totalDeleted, long durationMs) {

    /**
     * 由服务层结果构造响应。
     *
     * @param result 清理结果
     * @return 响应
     */
    public static RetentionCleanupRunResp from(RetentionCleanupResult result) {
        return new RetentionCleanupRunResp(
            !result.skipped(),
            result.skipReason().getCode(),
            result.outageEvents(),
            result.maintenanceWindows(),
            result.total(),
            result.durationMs());
    }
}
