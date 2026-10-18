/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability.config;

import feign.Request;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 读数上报的 Feign 配置（显式超时 + 不自动重试）。
 *
 * <p>为什么单独一份而不是复用设备规格的配置：两条链路的超时预算不同（读数上报是高频小批量，
 * 设备规格是一次拉全量），且**不重试**是刻意的——access 侧的出口本就有界队列，
 * 重试只会把flush 线程占住并放大远端压力；失败由指标与日志暴露（见
 * {@code HttpAccessReadingSink}）。</p>
 *
 * <p><b>内部凭证不在这里注入</b>：由 {@code ypbin-starter-cloud-core} 的全局 Feign 拦截器按
 * {@code ypbin.cloud.feign.internal-token} 统一携带（starter 4.0.0 起，覆盖所有 Feign 客户端）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Configuration
public class ReadingFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）：一批 ≤500 条观察，正常是毫秒级。 */
    public static final int READ_TIMEOUT_MS = 5_000;

    /**
     * 超时显式化（禁止无超时的默认客户端）。
     *
     * @return Feign 请求选项
     */
    @Bean
    public Request.Options readingRequestOptions() {
        return new Request.Options(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS, READ_TIMEOUT_MS,
            TimeUnit.MILLISECONDS, true);
    }

    /**
     * 不自动重试（重试策略由 access 侧出口的有界队列与丢弃计数承担）。
     *
     * @return 重试器
     */
    @Bean
    public Retryer readingRetryer() {
        return Retryer.NEVER_RETRY;
    }

}
