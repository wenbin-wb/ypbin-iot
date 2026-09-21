/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.device.config;

import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 设备规格内部客户端的 Feign 配置。
 *
 * <p>铁律：远程调用必须显式配置连接/读超时并明确重试策略——这里**不自动重试**
 * （{@link Retryer#NEVER_RETRY}）：租约调度线程不能被远端拖住，采集规格拉取失败应让本轮失败、
 * 下一轮重试，而不是在调用点静默放大压力。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Configuration
public class DeviceSpecFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）：一次要返回某租户全部设备与点位，比租约端点给得宽一些。 */
    public static final int READ_TIMEOUT_MS = 5_000;

    /** 内部凭证请求头（与 {@code InternalTokenConstants.TOKEN_HEADER} 必须一致）。 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 内部凭证配置键（与 {@code InternalTokenConstants.TOKEN_PROPERTY} 必须一致）。 */
    public static final String TOKEN_PROPERTY = "ypbin.internal.token";

    /**
     * 超时设置。
     *
     * @return Feign 请求参数
     */
    @Bean
    public Request.Options deviceSpecRequestOptions() {
        return new Request.Options(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS, READ_TIMEOUT_MS,
            TimeUnit.MILLISECONDS, true);
    }

    /**
     * 不重试（由调用方按轮次重试）。
     *
     * @return 重试策略
     */
    @Bean
    public Retryer deviceSpecRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 带上内部凭证头：`/internal/**` 由服务端守卫统一校验，缺凭证一律拒绝。
     *
     * @param environment 环境（读 {@code ypbin.internal.token}）
     * @return 请求拦截器
     */
    @Bean
    public RequestInterceptor deviceSpecInternalTokenInterceptor(Environment environment) {
        return template -> {
            String token = environment.getProperty(TOKEN_PROPERTY);
            if (token != null && !token.isBlank()) {
                template.header(TOKEN_HEADER, token);
            }
        };
    }
}
