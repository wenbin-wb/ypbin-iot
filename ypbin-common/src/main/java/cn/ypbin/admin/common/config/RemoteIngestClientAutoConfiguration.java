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
import org.springframework.context.annotation.Configuration;

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
     * 日志侧装配（<b>独立嵌套类</b>，类级 {@code @ConditionalOnClass}）。
     *
     * <p><b>为什么 log 的 {@code @Bean} 也必须收进带类级条件的嵌套类</b>（2026-10-20 第二轮
     * dev 部署实测）：上一轮只把 track 收进嵌套类，log 的
     * {@code remoteLogIngestClient(ISystemClient)} 仍留在父类 ⇒ 宿主 {@code ypbin-access}
     * 没有依赖 {@code ypbin-system-api}（因此没有 {@code ISystemClient}）时，Spring 在<b>条件求值
     * 阶段内省父类全部 {@code @Bean} 方法签名</b>就撞 {@code NoClassDefFoundError}：
     * {@code Error processing condition on RemoteIngestClientAutoConfiguration.sessionLogClientProvider
     * → NoClassDefFoundError: cn/ypbin/admin/system/api/feign/ISystemClient}。方法级
     * {@code @ConditionalOnClass} 不能阻止这次内省，类级条件才可以（先求值、整类跳过）。</p>
     *
     * <p>教训：**只要 {@code @Bean} 方法签名里出现可选依赖的类型，就必须和它的类级条件待在同一个
     * 嵌套配置类里**；把不同可选依赖的 {@code @Bean} 混在同一个类里，等于把它们的条件绑在一起。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RemoteLogIngestClient.class, ISystemClient.class})
    static class LogIngestConfiguration {

        /**
         * 日志上报实现：把 {@code LogRecord} 上报给 system（跨服务，不直连库）。
         *
         * @param systemClient system 服务 Feign 客户端
         * @return 日志上报 SPI 实现
         */
        @Bean
        @ConditionalOnMissingBean(RemoteLogIngestClient.class)
        RemoteLogIngestClient remoteLogIngestClient(ISystemClient systemClient) {
            return new SystemRemoteLogIngestClient(systemClient);
        }
    }

    /**
     * 日志「数据源」侧装配（操作人 / 客户端信息）：只依赖 starter-log 与 starter-security 的
     * {@code LogUserProvider} / {@code LogClientProvider}，<b>不依赖 {@code ISystemClient}</b>。
     *
     * <p>与上报装配分开成类，是为了让缺口 {@code ISystemClient} 的宿主编译/启动时仍能拿到这两个
     * provider（上报装配被整类跳过），而不是「一个可选依赖缺失就整块日志能力消失」。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({LogUserProvider.class, LogClientProvider.class, SessionLogClientProvider.class})
    static class LogProviderConfiguration {

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
        LogUserProvider identityHeaderLogUserProvider() {
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
        LogClientProvider sessionLogClientProvider() {
            return new SessionLogClientProvider();
        }
    }

    /**
     * 埋点上报装配（<b>独立嵌套类</b>，类级 {@code @ConditionalOnClass}）。
     *
     * <p><b>为什么必须做成分离的嵌套配置类</b>（2026-10-20 dev 真实启动失败）：
     * 方法级 {@code @ConditionalOnClass} 不足以保证安全——Spring 处理自动配置类时仍会
     * <b>内省该类的全部 {@code @Bean} 方法签名</b>，而本方法的返回类型
     * {@link RemoteTrackIngestClient} 位于 {@code ypbin-starter-tracking}。宿主
     * {@code ypbin-access} 没有引入该模块 ⇒ 内省阶段直接 {@code NoClassDefFoundError}，
     * 整个应用起不来（失败信息还指向本类，与「缺少一个可选依赖」的因果很远）。</p>
     *
     * <p>把这类 {@code @Bean} 收进<b>带类级条件</b>的嵌套 {@code @Configuration}：Spring 先求值
     * 嵌套类的类级条件（ASM 读取，不加载类），条件不满足就<b>整类跳过</b>，不再内省其方法签名。
     * 这是「可选依赖 + 自动配置」的标准写法。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RemoteTrackIngestClient.class, ISystemClient.class})
    static class TrackIngestConfiguration {

        /**
         * 埋点上报实现：把 {@code TrackEvent} 上报给 system。
         *
         * @param systemClient system 服务 Feign 客户端
         * @return 埋点上报 SPI 实现
         */
        @Bean
        @ConditionalOnMissingBean(RemoteTrackIngestClient.class)
        RemoteTrackIngestClient remoteTrackIngestClient(ISystemClient systemClient) {
            return new SystemRemoteTrackIngestClient(systemClient);
        }
    }
}
