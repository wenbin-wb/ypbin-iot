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

import cn.ypbin.admin.iot.lease.config.LeaseFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 接入节点 → iot 的**指标上报**客户端（把 access 的关键指标推给平台侧自告警）。
 *
 * <p>复用与租约客户端同源的出站配置（内部凭证、显式超时、不重试）：
 * ① 内部凭证由 starter 的全局 {@code FeignHeaderInterceptor} 按
 * {@code ypbin.cloud.feign.internal-token} 统一携带；② 超时由配置中心的
 * {@code spring.cloud.openfeign.client.config.accessMetricsClient.*} 提供（与租约客户端同预算
 * 1000/3000，见 {@code deploy/nacos/ypbin-iot.yaml}）；③ 不重试由 OpenFeign 默认的
 * {@code Retryer.NEVER_RETRY} 保证。
 * <b>本客户端与租约客户端共用 {@code LeaseFeignConfiguration} 仅作为「同一超时口径」的声明</b>——
 * 该配置类自 issue #298 起**不再声明任何 Bean**（摘掉 {@code @Configuration} 与
 * {@code Request.Options}/{@code Retryer}，否则会污染主上下文成为全局 Feign 默认值）。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
@FeignClient(name = "ypbin-iot", contextId = "accessMetricsClient", path = "/internal/metrics",
    configuration = LeaseFeignConfiguration.class)
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
