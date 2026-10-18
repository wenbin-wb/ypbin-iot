/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease.config;

import feign.Request;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 租约客户端（access → iot 的 /internal/lease/**）的 Feign 配置。
 *
 * <p>三件事与平台约定一致：<b>显式超时</b>（禁止无超时默认客户端）、<b>不重试</b>
 * （租约是有时效的写操作，重试会把「以为失败其实成功」变成归属错乱）、
 * <b>出站携带内部凭证</b>——由 {@code ypbin-starter-cloud-core} 的全局 Feign 拦截器按 {@code ypbin.cloud.feign.internal-token} 统一注入（本客户端不再手写拦截器）。</p>
 *
 * <p>⚠️ 超时值同时被 access 的启动自检使用（续约周期必须 ≥ 一次调用最坏耗时的 2 倍），
 * 改这里必须同步看 {@code AccessStartupValidator}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Configuration
public class LeaseFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 3_000;

    /**
     * 超时配置（connect/read 显式设定；不跟随重定向）。
     *
     * @return Feign 请求参数
     */
    @Bean
    public Request.Options leaseRequestOptions() {
        return new Request.Options(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS, READ_TIMEOUT_MS,
            TimeUnit.MILLISECONDS, true);
    }

    /**
     * 不重试。
     *
     * @return 永不重试的重试器
     */
    @Bean
    public Retryer leaseRetryer() {
        return Retryer.NEVER_RETRY;
    }

}
