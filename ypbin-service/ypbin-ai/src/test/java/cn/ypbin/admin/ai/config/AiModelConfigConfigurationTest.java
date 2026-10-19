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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.ai.core.AiKeyCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link AiModelConfigConfiguration} 的<strong>装配</strong>测试（对应 starter #103 的本仓落地方式）。
 *
 * <p><b>为什么必须真的起上下文，而不是只读源码</b>：{@code @ConfigurationProperties} 类只有在被
 * <em>启用</em>（组件扫描命中 {@code @Configuration} + {@code @EnableConfigurationProperties}，
 * 或启动类上的同名注解）之后才会成为 Bean。只加 {@code @ConfigurationProperties} 注解而不启用时，
 * {@code AiKeyCipher} 的构造器注入会在启动期抛 {@code NoSuchBeanDefinitionException}——
 * 这类「注解写了、装配没生效」的缺陷读代码看不出来，只能实测。</p>
 *
 * <p><b>本测试锁定三件事</b>：① {@link AiModelConfigProperties} 确实被绑定为 Bean 且拿到配置值；
 * ② {@link AiKeyCipher} 能被真实注入并可用（加解密往返）；③ 密钥缺失时应用<strong>启动失败</strong>
 * （fail-closed 语义不变，不允许出现「没配也能跑」的默认密钥）。</p>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class AiModelConfigConfigurationTest {

    /** 恰好 16 字节的合法密钥（AES-128）。仅测试用，非真实凭据。 */
    private static final String TEST_KEY = "0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(AiModelConfigConfiguration.class, CipherConfiguration.class);

    @Test
    @DisplayName("装配：配置类启用后 properties 成为 Bean，且绑定到 ypbin.ai.model-config.secret-key")
    void shouldBindPropertiesBeanWhenConfigurationIsScanned() {
        runner.withPropertyValues(AiModelConfigProperties.PREFIX + ".secret-key=" + TEST_KEY)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AiModelConfigProperties.class);
                assertThat(context.getBean(AiModelConfigProperties.class).getSecretKey())
                    .as("键名必须由 PREFIX 常量拼出，否则配置改了也不会被读到")
                    .isEqualTo(TEST_KEY);
                assertThat(context).hasSingleBean(AiKeyCipher.class);
            });
    }

    @Test
    @DisplayName("装配：AiKeyCipher 拿到可用密钥，加解密往返一致（证明注入的不是空对象）")
    void shouldInjectUsableCipher() {
        runner.withPropertyValues(AiModelConfigProperties.PREFIX + ".secret-key=" + TEST_KEY)
            .run(context -> {
                AiKeyCipher cipher = context.getBean(AiKeyCipher.class);
                String encrypted = cipher.encrypt("api-key-plain");
                assertThat(encrypted).isNotEqualTo("api-key-plain");
                assertThat(cipher.decrypt(encrypted)).isEqualTo("api-key-plain");
            });
    }

    @Test
    @DisplayName("fail-closed：未配置 secret-key 时上下文启动失败，且错误信息不带任何密钥值")
    void shouldFailContextWhenSecretKeyMissing() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .as("缺失密钥必须在启动期拒绝，而不是退化成某个内置默认密钥")
                .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining(AiModelConfigProperties.PREFIX + ".secret-key");
        });
    }

    @Test
    @DisplayName("同步期守护：同一 properties 类被两处 @EnableConfigurationProperties 启用时仍是单例")
    void shouldTolerateDuplicateEnablementAcrossMerge() {
        // 本仓用新配置类作为装配点（不改启动类）；admin 则把同名注解挂在 AiServiceApplication 上。
        // 下次 `git merge upstream/main` 采纳上游启动类后，两处会**同时**启用同一个 properties 类。
        // Spring 对同一类型的重复启用是幂等的（注册同一个 Bean 定义），本用例把这一点钉死：
        // 若将来变成 "duplicate bean" 启动失败，同步现场就会立刻发现，而不是等部署才炸。
        runner.withUserConfiguration(UpstreamStyleEnablement.class)
            .withPropertyValues(AiModelConfigProperties.PREFIX + ".secret-key=" + TEST_KEY)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AiModelConfigProperties.class);
                assertThat(context.getBean(AiModelConfigProperties.class).getSecretKey()).isEqualTo(TEST_KEY);
            });
    }

    /**
     * 上游形态的装配点：admin 把 {@code @EnableConfigurationProperties(AiModelConfigProperties.class)}
     * 挂在启动类上（本仓改用 {@link AiModelConfigConfiguration}）。本类仅用于模拟「合并后两处都在」。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiModelConfigProperties.class)
    static class UpstreamStyleEnablement {
    }

    /**
     * 被测装配的最小上下文：只注册 {@link AiKeyCipher}。
     *
     * <p>不用 {@code @ComponentScan} 扫整个 {@code cn.ypbin.admin.ai} 包——那会把 Mapper、Feign 客户端
     * 一并拖进来，测试就变成「测基础设施」而不是「测本配置类是否启用」。</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class CipherConfiguration {

        @Bean
        AiKeyCipher aiKeyCipher(AiModelConfigProperties properties) {
            return new AiKeyCipher(properties);
        }
    }
}
