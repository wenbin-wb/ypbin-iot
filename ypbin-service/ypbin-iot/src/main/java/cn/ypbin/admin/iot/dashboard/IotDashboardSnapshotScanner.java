/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.dashboard;

import cn.ypbin.admin.iot.service.IotDashboardSnapshotService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 仪表盘每日在线快照定时任务（默认每天 00:05）。
 *
 * <p>与仓内其它扫描器一致，本类只是 {@code @Scheduled} 薄壳：真正的跨租户聚合与批量写入在
 * {@link IotDashboardSnapshotService#captureAllTenants()} 中，便于纯单测不起 HTTP/调度。</p>
 *
 * <p><b>异常策略</b>：任务失败只记 ERROR（带完整堆栈）并让调度线程继续，不向容器抛异常——
 * 某天采集失败不应影响应用/后续 cron；下一次触发自然重试。留痕不可省：没日志的失败会变成
 * 「趋势就是空」这种无法解释的现象。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Component
@RequiredArgsConstructor
public class IotDashboardSnapshotScanner {

    private static final Logger log = LoggerFactory.getLogger(IotDashboardSnapshotScanner.class);

    private final IotDashboardSnapshotService iotDashboardSnapshotService;

    /**
     * 每日采集全平台租户当天快照；cron 可由 {@code ypbin.dashboard.snapshot-cron} 覆盖。
     */
    @Scheduled(cron = "${ypbin.dashboard.snapshot-cron:0 5 0 * * ?}")
    public void scan() {
        try {
            iotDashboardSnapshotService.captureAllTenants();
        } catch (RuntimeException ex) {
            // 失败不重抛（不影响应用），但必须 ERROR 留痕：下一天 cron 自然重试
            log.error("仪表盘每日快照扫描失败", ex);
        }
    }
}
