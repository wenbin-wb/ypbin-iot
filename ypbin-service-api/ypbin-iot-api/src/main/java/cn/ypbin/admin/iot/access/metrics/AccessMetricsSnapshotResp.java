/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.access.metrics;

/**
 * 接入节点（access）关键指标的**只读快照**，由 access 侧主动推送给 iot。
 *
 * <p>为什么要推送而不是让 iot 去拉：access 与 iot 是两个服务，access 上没有
 * 内部凭证守卫（其 `/internal/**` 面不存在），让 iot 去拉要么新增无保护端点、要么给 access
 * 补一整套守卫；而 access 本来就已有带内部凭证的出站链路（租约客户端）⇒ 由它**推**更简单，
 * 且语义更诚实：access 挂了 ⇒ iot 收不到 ⇒ 判定为「不可判定」而不是误报。</p>
 *
 * <p>各字段为 {@code null} 表示「这个指标没取到」——{@code 0} 是「观测到了且为零」。</p>
 *
 * @param reconcileDeferredCount 因时间预算用尽被推迟的租户次数（R8-2 方案 A）
 * @param tickDurationMaxMs      最近一轮 tick 的**最大**耗时（毫秒）
 * @param reconcileDurationMaxMs 对账阶段的**最大**耗时（毫秒）
 * @param specLoadDurationMaxMs  单租户规格取数的**最大**耗时（毫秒）
 * @param selfFencedCount        本地自我停采次数累计
 * @param nodeFencedCount        节点级失效停采次数累计
 * @param renewFailureCount      续约失败次数累计
 * @param egressPendingCount     当前待上报读数条数（gauge）
 * @param reportedAt             上报时刻（iot 侧据此判断「上报是否中断」）
 * @author wenbin
 * @since 2026-10-10
 */
public record AccessMetricsSnapshotResp(
    Long reconcileDeferredCount,
    Long tickDurationMaxMs,
    Long reconcileDurationMaxMs,
    Long specLoadDurationMaxMs,
    Long selfFencedCount,
    Long nodeFencedCount,
    Long renewFailureCount,
    Long egressPendingCount,
    java.time.Instant reportedAt) {
}
