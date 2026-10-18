/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.access.metrics.AccessMetricsSnapshotResp;
import cn.ypbin.admin.iot.platform.AccessMetricsReportStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import cn.ypbin.starter.core.model.R;

/**
 * 接入 access 指标上报的**内部端点**（平台自告警的数据入口）。
 *
 * <p>与 `/internal/lease/**` 同一信任域：由
 * starter 官方内部凭证守卫（`ypbin.security.internal-token.guard-enabled`）挂在
 * `/internal/**` 上的凭证守卫保护（凭证未配置一律拒绝、失败转 HTTP 200 + R.code=401）。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
@RestController
@RequestMapping("/internal/metrics")
@RequiredArgsConstructor
public class InternalAccessMetricsController {

    private final AccessMetricsReportStore reportStore;

    /**
     * 接收一次 access 指标快照。
     *
     * @param snapshot 快照
     * @return 空响应
     */
    @PostMapping("/access-report")
    public R<Void> report(@RequestBody AccessMetricsSnapshotResp snapshot) {
        reportStore.store(snapshot);
        return R.ok();
    }
}
