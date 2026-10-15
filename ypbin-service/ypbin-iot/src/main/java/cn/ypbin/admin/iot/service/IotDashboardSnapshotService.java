/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import java.util.List;

/**
 * IoT 仪表盘每日在线快照服务。
 *
 * <p>只为在线趋势提供**真实**历史数据：每次总览请求采集当天快照，趋势只读已落库的日期，
 * 没有快照就不返回该日期（不插值、不补造）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public interface IotDashboardSnapshotService {

    /**
     * 采集当前租户今天的设备在线快照。
     *
     * <p>按 {@code (当前租户, 今天)} 先查后写：已有则更新计数；没有则插入。并发首写由唯一键
     * {@code uk_iot_dashboard_daily_snapshot(tenant_id, snapshot_date)} 兜底，冲突时回退为更新。</p>
     */
    void captureCurrentTenant();

    /**
     * 采集**全平台所有租户**今天的设备在线快照（每日定时任务用）。
     *
     * <p>跨租户批处理：{@link cn.ypbin.starter.tenant.core.TenantContext#executeIgnore} 内一次
     * {@code GROUP BY tenant_id} 聚合出每租户的 total/online/offline/disabled，再通过
     * {@code IotDashboardDailySnapshotMapper#upsertBatch} 一条语句批量 upsert。没有任何租户设备时
     * 短路返回（不空打 upsert）。本方法与 {@link #captureCurrentTenant()} 并存且互不影响：后者仍是
     * 单请求租户路径的实时采集。</p>
     */
    void captureAllTenants();

    /**
     * 读取最近 {@code days} 天（含今天）的快照，按 {@code snapshot_date} 升序。
     *
     * @param days 天数；{@code <= 0} 直接返回空 List
     * @return 快照列表；历史不足 {@code days} 天时只返回已有天数，不补造
     */
    List<IotDashboardDailySnapshot> loadRecent(int days);
}
