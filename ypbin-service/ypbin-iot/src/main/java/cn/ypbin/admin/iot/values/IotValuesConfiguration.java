/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 最新值写入器的装配（设计 §5.3 / D0.7）。
 *
 * <p>装配规则：有 {@link StringRedisTemplate} ⇒ {@link RedisLatestValueWriter}；
 * 没有 ⇒ {@link LoggingLatestValueWriter}（WARN 暴露，不静默）。</p>
 *
 * <p>⚠️ 这里用 `@Bean` + `ObjectProvider` 而不是把实现类标 `@Component`：**防的是「实现类被双装配」**
 * （既当组件扫进来、又在装配层再定义一次）。注意：**本类不是自动配置**（没有
 * `@ConditionalOnMissingBean`），因此**不承诺宿主可替换**——宿主自行定义 `LatestValueWriter`
 * 会与本 bean 冲突（`NoUniqueBeanDefinitionException`）；真要做成可替换，需要升级为
 * `@AutoConfiguration` + `@ConditionalOnMissingBean`（见本仓教训三十一的完整条件）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Configuration
public class IotValuesConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotValuesConfiguration.class);

    /**
     * 最新值写入器（按是否存在 Redis 模板选择实现）。
     *
     * @param redisTemplateProvider Redis 模板（可能不存在）
     * @param meterRegistry         指标
     * @return 写入器
     */
    @Bean
    public LatestValueWriter latestValueWriter(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                               MeterRegistry meterRegistry) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("[iot] 未发现 StringRedisTemplate：最新值将不落库（可用率/断档不受影响）");
            return new LoggingLatestValueWriter();
        }
        return new RedisLatestValueWriter(redisTemplate, meterRegistry);
    }
}
