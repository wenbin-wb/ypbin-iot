/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.starter.log.spi.RemoteLogIngestClient;
import cn.ypbin.starter.tracking.spi.RemoteTrackIngestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link RemoteIngestClientAutoConfiguration} 的<b>可选依赖</b>装配测试。
 *
 * <p><b>要防的是真实事故</b>（2026-10-20 dev）：宿主 {@code ypbin-access} 没有依赖
 * {@code ypbin-starter-tracking}，而本自动配置里 {@code remoteTrackIngestClient} 方法的返回类型
 * 是 {@link RemoteTrackIngestClient} ⇒ 启动期内省该类时 {@code NoClassDefFoundError}，
 * <b>应用起不来</b>。原来只在方法上写了 {@code @ConditionalOnClass}——不足以保证安全：
 * Spring 处理自动配置类时仍会内省其 {@code @Bean} 方法签名。</p>
 *
 * <p>本测试用 {@link FilteredClassLoader} 把 {@code RemoteTrackIngestClient} 从类路径「遮掉」，
 * 真实复现「宿主没引 tracking」的场景，断言：① 上下文能正常启动（不抛 NoClassDefFoundError）；
 * ② 日志上报 Bean 仍然装配（缺 tracking 不该连日志上报一起丢掉）。</p>
 *
 * @author wenbin
 * @since 2026-10-20
 */
class RemoteIngestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RemoteIngestClientAutoConfiguration.class))
        .withUserConfiguration(SystemClientStubConfiguration.class);

    @Test
    @DisplayName("★ 类路径缺 starter-tracking：上下文仍能启动，且日志上报 Bean 照常装配")
    void shouldStartWithoutTrackingOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(RemoteTrackIngestClient.class))
            .run(context -> {
                assertThat(context).as("缺 tracking 时不得因内省 @Bean 方法签名而启动失败")
                    .hasNotFailed();
                assertThat(context).hasSingleBean(RemoteLogIngestClient.class);
                assertThat(context).doesNotHaveBean(RemoteTrackIngestClient.class);
            });
    }

    @Test
    @DisplayName("类路径齐备：两个上报 Bean 都装配")
    void shouldRegisterBothWhenAllSpiPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RemoteLogIngestClient.class);
            assertThat(context).hasSingleBean(RemoteTrackIngestClient.class);
        });
    }

    /** 提供 {@link ISystemClient} 的桩（本测试只关心装配条件，不关心实现行为）。 */
    @Configuration(proxyBeanMethods = false)
    static class SystemClientStubConfiguration {

        @Bean
        ISystemClient iSystemClient() {
            return org.mockito.Mockito.mock(ISystemClient.class);
        }
    }
}
