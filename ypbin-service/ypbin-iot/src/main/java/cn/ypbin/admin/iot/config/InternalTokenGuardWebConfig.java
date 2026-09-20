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
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把内部凭证守卫挂到 {@code /internal/**}（IoT 服务自己的实例）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Configuration
@RequiredArgsConstructor
public class InternalTokenGuardWebConfig implements WebMvcConfigurer {

    private final InternalProperties internalProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalTokenGuardInterceptor(internalProperties))
            .addPathPatterns("/internal/**");
    }
}
