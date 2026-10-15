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
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IoT SSE 的**装配契约**用例：{@code ypbin.sse.enabled} 缺席/为 false 时启动必须**失败**（fail-fast）。
 *
 * <p><b>为什么这是 IoT 的契约而不是 starter 的</b>：starter 提供的是「SSE 能力及其开关」
 * （默认关闭，由 starter 仓自身用例覆盖）；「IoT 必须显式开启它、且不允许静默降级」是本仓的部署约定 ——
 * 漏配 {@code deploy/nacos/ypbin-iot.yaml} 的 {@code ypbin.sse.enabled=true} 时，
 * 宁可应用起不来，也不要「服务起来了但实时流静默不可用」（后者的表现是前端永远收不到推送，
 * 而日志里没有任何报错，属最难排查的一类失效）。</p>
 *
 * <p><b>原文为独立验收探针（verify-backend / task-20），经 Lead 决策转为正式用例</b>并保留全部断言力：
 * 正向（enabled=true 装配成功）、反向（缺席与显式 false 都失败）、以及「构造器无 ObjectProvider 兜底」
 * 的结构断言。其中反向用例是**防假绿**的：只断言「失败」无法区分「条件未满足」与「装配逻辑本身坏掉」。</p>
 *
 * <p>用 {@link WebApplicationContextRunner}（而非普通 {@code ApplicationContextRunner}）：
 * starter 的 {@code SseAutoConfiguration} 带
 * {@code @ConditionalOnWebApplication(type = SERVLET)}，非 Web 上下文里它整类退让，
 * 会让「enabled=true 应成功」那条用例假失败。</p>
 *
 * @author verify-backend（原验收探针）→ wenbin（转正）
 * @since 2026-10-15
 */
class IotSseFailFastTest {

    /** 与生产同构：IotRealtimeService 由容器装配，构造器硬依赖 SseEmitterManager。 */
    @Configuration(proxyBeanMethods = false)
    static class ProductionLikeWiring {

        @Bean
        TenantProvider probeTenantProvider() {
            return () -> Optional.empty();
        }

        @Bean
        IotRealtimeService probeRealtimeService(TenantProvider provider, SseEmitterManager manager) {
            return new IotRealtimeService(provider, manager);
        }
    }

    @Test
    @DisplayName("★ fail-fast：缺 ypbin.sse.enabled（默认 false）⇒ 生产形态装配失败，报缺 SseEmitterManager")
    void missingEnabledFailsFast() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SseAutoConfiguration.class))
            .withUserConfiguration(ProductionLikeWiring.class)
            .run(context -> {
                assertThat(context).as("必须启动失败：宁可起不来，也不要'服务起来了但实时流静默不可用'")
                    .hasFailed();
                assertThat(String.valueOf(context.getStartupFailure()))
                    .contains("SseEmitterManager");
            });
    }

    @Test
    @DisplayName("★ 反向（防假绿）：显式 enabled=false 同样失败；enabled=true 则装配成功")
    void disabledFailsEnabledSucceeds() {
        // 显式 false 也必须失败（不是"没配才失败"）
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SseAutoConfiguration.class))
            .withUserConfiguration(ProductionLikeWiring.class)
            .withPropertyValues("ypbin.sse.enabled=false")
            .run(context -> assertThat(context).hasFailed());

        // enabled=true ⇒ 正常装配（证明上面的失败是"条件未满足"，不是装配逻辑本身坏掉）
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SseAutoConfiguration.class))
            .withUserConfiguration(ProductionLikeWiring.class)
            .withPropertyValues("ypbin.sse.enabled=true", "ypbin.sse.register-endpoint=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SseEmitterManager.class);
                assertThat(context).hasSingleBean(IotRealtimeService.class);
            });
    }

    @Test
    @DisplayName("★ 源码结构性事实：IotRealtimeService 是 @Service 且构造器硬依赖 manager（无 ObjectProvider 兜底）")
    void productionWiringIsHardDependency() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/cn/ypbin/admin/iot/realtime/IotRealtimeService.java"));
        assertThat(source).as("必须是容器组件，否则上面的装配用例与生产无关").contains("@Service");
        assertThat(source).contains("SseEmitterManager sseEmitterManager");
        assertThat(source).as("不得用 ObjectProvider 惰性兜底（那会把 fail-fast 变成静默禁用）")
            .doesNotContain("ObjectProvider");
    }
}
