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

import cn.ypbin.admin.common.log.IdentityHeaderLogUserProvider;
import cn.ypbin.starter.security.log.SessionLogClientProvider;
import cn.ypbin.admin.common.log.SystemRemoteLogIngestClient;
import cn.ypbin.admin.common.tracking.SystemRemoteTrackIngestClient;
import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.starter.log.core.LogClientProvider;
import cn.ypbin.starter.log.core.LogUserProvider;
import cn.ypbin.starter.log.spi.RemoteLogIngestClient;
import cn.ypbin.starter.tracking.spi.RemoteTrackIngestClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 跨服务上报 SPI 的装配：把本仓的日志/埋点上报实现注册成 Bean，供 starter 的
 * starter 的日志/埋点远程上报自动配置消费。
 *
 * <p><b>starter 4.1.0 起本仓不再自带自动配置与落库实现（starter #100）</b>：原先本仓有 4 个类
 * （日志上报自动配置 + 上报用 LogDao、埋点上报自动配置 + 上报用 TrackEventSink），
 * 依赖 `@AutoConfiguration(beforeName=...)` 抢在 starter 默认实现之前注册。
 * 基座上收这两层后，正确形态是：</p>
 * <ol>
 *   <li>本仓只提供 2 个 <b>SPI 实现</b>（{@link RemoteLogIngestClient} / {@link RemoteTrackIngestClient}，
 *       即「怎么调用 system」）；</li>
 *   <li>本类只负责把这 2 个实现注册成 Bean；</li>
 *   <li>「是否要用上报实现替换只打印的默认实现」「装配顺序」「与 system 本地实现的互斥」全部由 starter 的
 *       starter 的日志/埋点远程上报自动配置决定——它们以
 *       {@code @ConditionalOnBean(...IngestClient)} 为条件，且各自的 {@code @Bean} 带
 *       {@code @ConditionalOnMissingBean}。</li>
 * </ol>
 *
 * <p><b>为什么保留本类而不是用 {@code @Component}</b>：{@code ypbin-common} 不是每个服务的扫描根
 * （如 gateway 不扫本包），用自动配置才能让「有 Feign 能力且引入了日志/埋点模块的服务」统一生效；
 * 同时 {@code @ConditionalOnMissingBean} 允许宿主（含 test）覆盖实现。</p>
 *
 * <p><b>依赖可选</b>：只在类路径同时存在 SPI 与 {@link ISystemClient} 时生效——纯 WebFlux 网关等
 * 不具备这两者的应用不受影响。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@AutoConfiguration
public class RemoteIngestClientAutoConfiguration {

    /**
     * 日志上报实现：把 {@code LogRecord} 上报给 system（跨服务，不直连库）。
     *
     * @param systemClient system 服务 Feign 客户端
     * @return 日志上报 SPI 实现
     */
    @Bean
    @ConditionalOnClass({RemoteLogIngestClient.class, ISystemClient.class})
    @ConditionalOnMissingBean(RemoteLogIngestClient.class)
    public RemoteLogIngestClient remoteLogIngestClient(ISystemClient systemClient) {
        return new SystemRemoteLogIngestClient(systemClient);
    }

    /**
     * 操作人数据源（网关身份头实现）：从 {@code IdentityContext} 取当前用户。
     *
     * <p>starter 的 {@code LogAutoConfiguration.logUserProvider()} 默认实现恒返回空 ⇒ {@code sys_log}
     * 的 {@code create_user} 恒为 null。本实现替代它（含与 system 侧 {@code DbLogProviders} 的互斥，
     * 由两边的 {@code @ConditionalOnMissingBean} 保证同容器只有一个）。</p>
     *
     * @return 操作人数据源
     */
    @Bean
    @ConditionalOnMissingBean(LogUserProvider.class)
    public LogUserProvider identityHeaderLogUserProvider() {
        return new IdentityHeaderLogUserProvider();
    }

    /**
     * 客户端信息数据源：<b>直接用 starter 4.1.0 的实现</b>（`cn.ypbin.starter.security.log`）。
     *
     * <p>本仓此前自建过同名实现，starter 4.1.0（issue #100）已把「身份头模式按 loginId 读账号会话、
     * 单体模式读当前 token 会话」两种分支做进去，且单体模式只读 <b>1 次</b>会话（本地副本是 2 次）。
     * 保留本地副本会让「栈帧/IDE 跳转/同包测试解析」指向错误实现，故删除并改用官方版
     * （架构门禁 {@code DuplicateClassNameGateTest} 会拦住这类遗留副本）。</p>
     *
     * <p>仍保留本 Bean：宿主必须<b>显式</b>注册 SPI 适配（starter 的默认实现返回空，见
     * {@code LogAutoConfiguration}），这里把它接到 starter 的会话实现上。</p>
     *
     * @return 客户端信息数据源
     */
    @Bean
    @ConditionalOnMissingBean(LogClientProvider.class)
    public LogClientProvider sessionLogClientProvider() {
        return new SessionLogClientProvider();
    }

    /**
     * 埋点上报实现：把 {@code TrackEvent} 上报给 system。
     *
     * @param systemClient system 服务 Feign 客户端
     * @return 埋点上报 SPI 实现
     */
    @Bean
    @ConditionalOnClass({RemoteTrackIngestClient.class, ISystemClient.class})
    @ConditionalOnMissingBean(RemoteTrackIngestClient.class)
    public RemoteTrackIngestClient remoteTrackIngestClient(ISystemClient systemClient) {
        return new SystemRemoteTrackIngestClient(systemClient);
    }
}
