/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * EMQX 相关配置的绑定入口。
 *
 * <p><b>只做参数绑定与端口装配</b>：{@code enabled=true} 时装配真实的 REST 客户端
 * （{@link EmqxRestAdminClient}），{@code enabled=false} 时装配 {@link DisabledEmqxAdminClient}
 * ——后者每次调用都显式报错，**不是**「假装成功的空实现」（见该类注释）。
 * 无论哪种，平台侧凭据的签发/校验（哈希自持）都照常可用。</p>
 *
 * <p><b>为什么用 {@code @Bean} 方法而不是给实现类标 {@code @Component}</b>：这样「哪个实现被装配」
 * 只有一处真源（本方法），将来宿主想替换实现时不会遇到 {@code NoUniqueBeanDefinitionException}
 * ——「可替换」必须是真的缝（架构门禁 SRC-07 / 教训三十一）。</p>
 *
 * <p><b>fail-fast</b>：{@code enabled=true} 但缺 base-url/API Key/Secret 时，客户端构造器直接抛异常
 * ⇒ 应用**启动即失败**，而不是等到第一次签发凭据才暴露（那时代码已经跑到一半）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Configuration
@EnableConfigurationProperties(EmqxProperties.class)
public class IotEmqxConfiguration {

    /**
     * 装配 EMQX 管理面客户端。
     *
     * @param properties   EMQX 配置
     * @param objectMapper JSON 序列化器（Spring 装配的 Jackson 3）
     * @return 管理面客户端（enabled=false 时是不可用实现）
     */
    @Bean
    public EmqxAdminClient emqxAdminClient(EmqxProperties properties, ObjectMapper objectMapper) {
        if (!properties.isEnabled()) {
            return new DisabledEmqxAdminClient();
        }
        return new EmqxRestAdminClient(properties, objectMapper);
    }
}
