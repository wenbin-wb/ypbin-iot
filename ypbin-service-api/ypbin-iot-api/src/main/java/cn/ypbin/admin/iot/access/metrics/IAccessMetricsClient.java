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

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 接入节点 → iot 的**指标上报**客户端（把 access 的关键指标推给平台侧自告警）。
 *
 * <p>复用与租约客户端同源的出站配置（内部凭证、显式超时、不重试）——它们在同一个
 * {@code LeaseFeignConfiguration} 作用域内注册，`Request.Options`/`Retryer`/
 * `RequestInterceptor` 三类 bean 各唯一，因此对本客户端同样生效（由
 * {@code AccessMetricsReporterTest} 断言「同一配置来源」的契约，避免换包时静默失效）。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
@FeignClient(name = "ypbin-iot", contextId = "accessMetricsClient", path = "/internal/metrics")
public interface IAccessMetricsClient {

    /**
     * 上报一次 access 指标快照。
     *
     * @param snapshot 指标快照
     * @return 空响应（失败由 Feign 抛异常，调用方计数）
     */
    @PostMapping("/access-report")
    Void report(@RequestBody AccessMetricsSnapshotResp snapshot);
}
