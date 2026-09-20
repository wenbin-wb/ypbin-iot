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
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;

/**
 * 租约客户端（access → iot 的 /internal/lease/**）的 Feign 配置。
 *
 * <p>三件事与平台约定一致：<b>显式超时</b>（禁止无超时默认客户端）、<b>不重试</b>
 * （租约是有时效的写操作，重试会把「以为失败其实成功」变成归属错乱）、
 * <b>出站携带内部凭证</b>（服务端 /internal/** 的守卫 fail-closed）。</p>
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

    /** 内部凭证请求头（与 {@code InternalTokenConstants.TOKEN_HEADER} 必须一致）。 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 内部凭证配置键（与 {@code InternalTokenConstants.TOKEN_PROPERTY} 必须一致）。 */
    public static final String TOKEN_PROPERTY = "ypbin.internal.token";

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

    /**
     * 出站携带内部凭证。
     *
     * <p>凭证未配置时不加头（服务端会 fail-closed 拒绝）——**不静默放行**，
     * 让 access 在启动握手时就失败，而不是带着无权调用跑起来。</p>
     *
     * @param environment 环境（取 {@code ypbin.internal.token}）
     * @return 出站拦截器
     */
    @Bean
    public RequestInterceptor leaseInternalTokenInterceptor(Environment environment) {
        return template -> {
            String token = environment.getProperty(TOKEN_PROPERTY, "");
            if (token == null || token.isBlank()) {
                return;
            }
            template.header(TOKEN_HEADER, token);
        };
    }
}
