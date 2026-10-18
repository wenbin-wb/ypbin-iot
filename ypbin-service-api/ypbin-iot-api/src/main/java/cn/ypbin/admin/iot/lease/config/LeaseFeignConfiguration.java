/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease.config;

/**
 * 租约客户端（access → iot 的 /internal/lease/**）的 Feign 配置锚点。
 *
 * <p><b>本类刻意不带 {@code @Configuration}（issue #298）</b>：它只作为
 * {@code @FeignClient(configuration = LeaseFeignConfiguration.class)} 的**子上下文**配置被引用
 * （官方文档：配置类不必标 {@code @Configuration}；若标了就必须排除组件扫描）。此前带该注解，
 * 而本包落在宿主 {@code scanBasePackages} 内（access 显式扫 {@code cn.ypbin.admin.iot.lease}）⇒
 * 被当普通配置类加载进**主上下文**，其声明的 {@code Request.Options} 于是成了**所有 Feign 客户端的
 * 全局默认值**，静默覆盖 Nacos 里 {@code spring.cloud.openfeign.client.config.*} 的超时且无任何告警
 * （实测：主上下文 {@code Request.Options} 取到 1000/3000）。</p>
 *
 * <p><b>超时值放在配置中心</b>：{@code spring.cloud.openfeign.client.config.<contextId>.connect-timeout
 * / read-timeout}（见 {@code deploy/nacos/ypbin-iot.yaml}）。本类只保留
 * {@link #CONNECT_TIMEOUT_MS}/{@link #READ_TIMEOUT_MS} 两个**常量**——它们是超时口径的单一来源，
 * 供 {@code AccessStartupValidator} 做启动自检（续约周期必须 ≥ 一次调用最坏耗时的 2 倍），
 * 因此**不得删除**；超时若在配置中心被改动，`AccessStartupValidator` 的常量口径需同步评估。</p>
 *
 * <p><b>不重试</b>：租约是有时效的写操作，重试会把「以为失败其实成功」变成归属错乱。该语义由
 * OpenFeign 默认的 {@code Retryer.NEVER_RETRY}（{@code FeignClientsConfiguration#feignRetryer}，
 * 带 {@code @ConditionalOnMissingBean}）保证，无需本类再声明 Bean（声明反而会污染主上下文）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public final class LeaseFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 3_000;

    private LeaseFeignConfiguration() {
    }
}
