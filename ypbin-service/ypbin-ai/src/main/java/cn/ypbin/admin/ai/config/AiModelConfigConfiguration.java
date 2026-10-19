/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模型配置（{@code ypbin.ai.model-config.*}）的装配点。
 *
 * <p><strong>为什么单独立一个配置类，而不是把
 * {@code @EnableConfigurationProperties(AiModelConfigProperties.class)} 挂在
 * {@code AiServiceApplication} 上</strong>：启动类是本仓<b>继承自 admin 的既有文件</b>，
 * 而本仓的启动类与 admin 已有实质分歧（本仓没有 admin 的
 * {@code RemoteIngestClientConfiguration}，跨服务上报改由 starter 的自动配置 +
 * 本仓的 {@code RemoteIngestClientAutoConfiguration} 承担）。
 * 在启动类上再加一行会把这个分歧面继续扩大，且违反「IoT 侧改动优先放新文件」的同步纪律。
 * 用一个只在 ai 模块内的新配置类承载装配，语义等价（都是「让该 properties 类成为 Bean」），
 * 却不新增任何白名单条目。</p>
 *
 * <p>本类会被 {@code @SpringBootApplication(scanBasePackages = "cn.ypbin.admin.ai")} 扫到，
 * 因此 {@link AiModelConfigProperties} 一定会成为容器 Bean，{@code AiKeyCipher} 的构造器注入
 * 不会因「properties 未被启用」而在启动期失败——该结论已由
 * {@code AiModelConfigConfigurationTest} 用真实应用上下文验证。</p>
 *
 * <p><strong>凭据卫生</strong>：本类不读、不打印任何配置值；{@link AiModelConfigProperties}
 * 的字段（含真实凭据 {@code secret-key}）只在该对象内部流转。</p>
 *
 * @author wenbin
 * @since 2026-10-19
 */
@Configuration
@EnableConfigurationProperties(AiModelConfigProperties.class)
public class AiModelConfigConfiguration {
}
