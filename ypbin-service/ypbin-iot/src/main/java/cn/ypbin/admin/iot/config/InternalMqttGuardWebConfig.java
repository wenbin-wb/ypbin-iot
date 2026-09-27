/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.config;

import cn.ypbin.admin.common.config.InternalProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 把「原始状态码」的凭证守卫挂到 {@code /internal/mqtt/*}（**仅**该前缀；决策 D2 的破例范围）。
 *
 * <p>注册成 {@link FilterRegistrationBean} 而不是 {@code @Component}：只有注册 bean 才能限定
 * URL 模式与顺序。顺序取 {@code HIGHEST_PRECEDENCE + 10}——要早于业务过滤器链，但把
 * {@code HIGHEST_PRECEDENCE} 本身留给更基础的组件（如编码/转发头过滤器）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@Configuration
@RequiredArgsConstructor
public class InternalMqttGuardWebConfig {

    /** 破例范围的 URL 模式（Servlet 语法：{@code /internal/mqtt/*} 匹配其下全部路径）。 */
    public static final String MQTT_INGRESS_URL_PATTERN = "/internal/mqtt/*";

    private final InternalProperties internalProperties;

    /**
     * 注册 MQTT 入站凭证过滤器。
     *
     * @return 过滤器注册（限定在 {@value #MQTT_INGRESS_URL_PATTERN}）
     */
    @Bean
    public FilterRegistrationBean<InternalMqttTokenFilter> internalMqttTokenFilter() {
        FilterRegistrationBean<InternalMqttTokenFilter> registration =
            new FilterRegistrationBean<>(new InternalMqttTokenFilter(internalProperties));
        registration.addUrlPatterns(MQTT_INGRESS_URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("internalMqttTokenFilter");
        return registration;
    }
}
