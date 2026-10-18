/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.messaging.autoconfigure.SseAutoConfiguration;
import cn.ypbin.starter.messaging.sse.SseEmitterManager;
import cn.ypbin.starter.messaging.sse.JacksonSseFanoutCodec;
import cn.ypbin.starter.messaging.sse.SseFanoutCodec;
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 装配用例（task-18 能力上收后新增）。
 *
 * <p><b>为什么必须有</b>：改造后 IoT 的 {@link IotRealtimeService} 直接依赖 starter 的
 * {@link SseEmitterManager}。而 starter 的自动配置以 {@code ypbin.sse.enabled=true} 为条件
 * （**默认 false**）且要求 Servlet Web 环境 ⇒ 若 nacos 漏配该项，应用启动时会以「缺 Bean」失败。
 * 这类问题单测（直接 new）永远发现不了，只有真的跑装配才看得见——本用例就是那道闸门。</p>
 *
 * <p>用 {@link WebApplicationContextRunner}（而非普通 {@code ApplicationContextRunner}）：
 * starter 的 {@code SseAutoConfiguration} 带
 * {@code @ConditionalOnWebApplication(type = SERVLET)}，非 Web 上下文里它整类退让、什么都不装配
 * ——用错 runner 会得到「永远装配不出 manager」的假失败。这里只加载 SSE 自动配置，不起数据源/Redis，
 * 快且稳定。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotSseWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SseAutoConfiguration.class))
        // starter 的 JacksonSseFanoutCodec 只有在容器里**真有** ObjectMapper Bean 时才装配
        // （SseAutoConfiguration#sseFanoutCodec 用 ObjectProvider 惰性取，缺席即退回 TextSseFanoutCodec）。
        // 生产里该 Bean 由 Boot 的 Jackson 自动配置提供；本用例只加载 SSE 自动配置，故显式补一个，
        // 否则测到的是「Text 兜底」而不是「生产装配」。
        .withBean(tools.jackson.databind.ObjectMapper.class,
            () -> tools.jackson.databind.json.JsonMapper.builder().build())
        .withUserConfiguration(CodecConfiguration.class);

    @Test
    @DisplayName("★ ypbin.sse.enabled=true ⇒ 装配出 SseEmitterManager 且 IoT 服务可注入（启动期不报缺 Bean）")
    void enabledPropertyAssemblesEmitterManager() {
        runner.withPropertyValues("ypbin.sse.enabled=true", "ypbin.sse.register-endpoint=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SseEmitterManager.class);
                assertThat(context).hasSingleBean(IotRealtimeService.class);
            });
    }

    @Test
    @DisplayName("★ 缺省（enabled 未配 = false）⇒ 不装配 manager —— 这正是必须在 nacos 显式开启的原因")
    void disabledByDefaultDoesNotAssembleManager() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SseEmitterManager.class);
        });
    }

    @Test
    @DisplayName("★ 反证：manager 缺席时按构造注入的真实 IotRealtimeService **启动即失败**（fail-fast，不静默禁用实时流）")
    void missingManagerMakesProductionWiringFailFast() {
        // 生产里 IotRealtimeService 是 @Service + 构造注入 manager：manager 缺席 ⇒ 启动失败。
        // 这是**刻意**的（宁可启动报错，也不要「服务起来了但实时流静默不可用」）。
        // 本用例用 @Bean 直接构造来断言这个依赖关系是硬依赖。
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SseAutoConfiguration.class))
            .withUserConfiguration(HardDependencyConfiguration.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("SseEmitterManager");
            });
    }

    /** 硬依赖装配（等价于生产里 @Service 的构造注入，不带 ObjectProvider 兜底）。 */
    @Configuration(proxyBeanMethods = false)
    static class HardDependencyConfiguration {

        @Bean
        TenantProvider hardTenantProvider() {
            return () -> Optional.empty();
        }

        @Bean
        IotRealtimeService hardIotRealtimeService(TenantProvider tenantProvider,
                                                  SseEmitterManager manager) {
            return new IotRealtimeService(tenantProvider, manager);
        }
    }

    @Test
    @DisplayName("★ starter 4.0.0 的 JacksonSseFanoutCodec 承载结构化事件载荷（IoT 不再自建 codec）")
    void starterJacksonCodecCarriesStructuredPayload() {
        runner.withPropertyValues("ypbin.sse.enabled=true", "ypbin.sse.register-endpoint=false")
            .run(context -> {
                assertThat(context).hasSingleBean(SseFanoutCodec.class);
                SseFanoutCodec codec = context.getBean(SseFanoutCodec.class);
                assertThat(codec)
                    .as("IoT 已删除自建 IotSseFanoutCodec（issue #289 第 5 项）⇒ 必须由 starter 的 "
                        + "JacksonSseFanoutCodec 承担结构化载荷；退化成 TextSseFanoutCodec 会让 "
                        + "record 事件只投本实例（跨实例扇出静默失效）")
                    .isInstanceOf(JacksonSseFanoutCodec.class);
                assertThat(codec.id())
                    .as("协议 id 是对外契约：多个实例混跑时必须一致（滚动升级窗口会双向丢帧）")
                    .isEqualTo(JacksonSseFanoutCodec.ID);
                // 形状冒烟：编码必须产出帧（具体解码形态由 starter 的单测覆盖；
                // starter 的 j 分支解码返回 JSON 原文，不再还原为树——这是 starter 的既有契约，不是本仓要改的）
                assertThat(codec.encode(java.util.Map.of("kind", "reading", "value", 42)))
                    .as("结构化载荷必须能被本 codec 编码（非 empty）")
                    .isPresent();
            });
    }

    /**
     * 装配被测的两类（生产里它们是 {@code @Service}/{@code @Component}，这里以 {@code @Bean} 等价装配）。
     *
     * <p>两个 Bean 都带 {@code @ConditionalOnMissingBean} 语义的等价物——用 {@code ObjectProvider}
     * 惰性取 manager：**manager 缺席时本配置也不该让上下文启动失败**，
     * 否则「默认关闭」那条用例就测不出「不装配」这一事实。</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class CodecConfiguration {

        @Bean
        TenantProvider iotTenantProvider() {
            return () -> Optional.empty();
        }

        @Bean
        IotRealtimeService iotRealtimeService(TenantProvider tenantProvider,
                                              ObjectProvider<SseEmitterManager> manager) {
            return new IotRealtimeService(tenantProvider, manager.getIfAvailable());
        }
    }
}
