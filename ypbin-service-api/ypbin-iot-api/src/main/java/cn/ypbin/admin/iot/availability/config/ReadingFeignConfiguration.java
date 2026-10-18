/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability.config;

/**
 * 读数上报客户端（access → iot 的 /internal/readings）的 Feign 配置锚点。
 *
 * <p><b>本类刻意不带 {@code @Configuration}（issue #298）</b>：它只作为
 * {@code @FeignClient(configuration = ReadingFeignConfiguration.class)} 的**子上下文**配置被引用。
 * 此前带该注解且包在宿主扫描范围内 ⇒ 被加载进**主上下文**，其 {@code Request.Options} 成为所有 Feign
 * 客户端的全局默认值，静默覆盖 Nacos 配置的超时（实测：主上下文取到 1000/5000）。</p>
 *
 * <p><b>超时值放在配置中心</b>：{@code spring.cloud.openfeign.client.config.readingClient.*}
 * （见 {@code deploy/nacos/ypbin-iot.yaml}）。读数上报是高频小批量，与设备规格的全量拉取预算不同，
 * 故用各自的 {@code contextId} 分别配置。</p>
 *
 * <p><b>不重试</b>：access 侧出口本就有界队列，重试只会把 flush 线程占住并放大远端压力；失败由指标与
 * 日志暴露（见 {@code HttpAccessReadingSink}）。该语义由 OpenFeign 默认的
 * {@code Retryer.NEVER_RETRY} 保证，无需本类声明 Bean。</p>
 *
 * <p><b>内部凭证不在这里注入</b>：由 {@code ypbin-starter-cloud-core} 的全局 Feign 拦截器按
 * {@code ypbin.cloud.feign.internal-token} 统一携带（覆盖所有 Feign 客户端）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public final class ReadingFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）：一批 ≤500 条观察，正常是毫秒级。 */
    public static final int READ_TIMEOUT_MS = 5_000;

    private ReadingFeignConfiguration() {
    }
}
