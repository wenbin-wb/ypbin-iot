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

import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp;

/**
 * IoT 运行总览服务。
 *
 * <p>只读聚合：一次调用给出首页所需全部计数、趋势与分布；所有查询都由租户插件按当前租户隔离。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
public interface IotDashboardService {

    /**
     * 获取当前租户的运行总览。
     *
     * <p>{@code onlineTrend} 当前恒为空数组：仓内没有可信的「按日在离线/在线快照」来源
     * （{@code iot_device.online_status} 只是当下值，{@code outage_event} 只记录断档事件，
     * 用它反推逐日在线数会把停用/从未上报设备混进来，属于编造）。没有可信口径就不返回假趋势，
     * 前端按空态展示。后续若新增落库的在线快照再在此实现。</p>
     *
     * @return 运行总览
     */
    DashboardOverviewResp overview();
}
