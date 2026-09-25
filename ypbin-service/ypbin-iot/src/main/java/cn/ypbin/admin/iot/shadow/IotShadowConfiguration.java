/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 影子上报值写入器的装配（G2）。
 *
 * <p>与 {@code IotValuesConfiguration} 同风格：用 {@code @Bean} 显式装配，而**不给实现类加
 * {@code @Component}**——防的是「实现类既被组件扫描、又在装配层再定义一次」的双装配（见本仓教训三十一）。
 * 本类不是自动配置（没有 {@code @ConditionalOnMissingBean}），因此**不承诺宿主可替换**。</p>
 *
 * <p>为什么不像最新值那样按「有没有 Redis」分两个实现：影子写的是**数据库**（{@code iot_shadow} 是
 * 平台自有表，没有可选存储），实现只有落库一种，没有可选项就没有选择分支。</p>
 *
 * @author wenbin
 * @since 2026-09-25
 */
@Configuration
public class IotShadowConfiguration {

    /**
     * 影子上报值写入器。
     *
     * @param shadowMapper 影子 Mapper
     * @param objectMapper 增量 JSON 序列化器（与影子读路径同源，避免两套转义规则）
     * @param meterRegistry 指标
     * @return 写入器
     */
    @Bean
    public ShadowReportedWriter shadowReportedWriter(IotShadowMapper shadowMapper, ObjectMapper objectMapper,
                                                     MeterRegistry meterRegistry) {
        return new DbShadowReportedWriter(shadowMapper, objectMapper, meterRegistry);
    }
}
