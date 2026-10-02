/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import cn.ypbin.starter.sign.core.InMemoryNonceStore;
import cn.ypbin.starter.sign.core.NonceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 开放 API 签名 nonce 防重放存储装配。
 *
 * <p>有 {@link StringRedisTemplate} ⇒ {@link RedisNonceStore}（多实例安全）；
 * 没有 ⇒ {@link InMemoryNonceStore}（<b>仅单实例正确</b>，打 WARN 暴露，不静默）。</p>
 *
 * <p>刻意不用 starter 的 `sign` 模块自动配置：那里装配的 NonceStore 服务于
 * `ypbin.sign` 的 servlet 链路，而本服务用的是网关透传参数的内部校验链路，
 * 两者生命周期与开关不同；此处按 iot 自身需要单独装配，语义更清晰。</p>
 *
 * @author wenbin
 * @since 2026-10-05
 */
@Configuration
public class IotOpenApiSignConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotOpenApiSignConfiguration.class);

    /**
     * nonce 防重放存储。
     *
     * @param redisTemplateProvider Redis 模板（可能不存在）
     * @return 存储实现
     */
    @Bean
    public NonceStore openApiNonceStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("[iot] 未发现 StringRedisTemplate：开放 API 防重放退化为**内存**实现，"
                + "多实例部署下跨实例重放无法拦截（单实例可用）");
            return new InMemoryNonceStore();
        }
        return new RedisNonceStore(redisTemplate);
    }
}
