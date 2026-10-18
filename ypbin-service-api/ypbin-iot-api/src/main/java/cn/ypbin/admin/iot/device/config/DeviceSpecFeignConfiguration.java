/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.device.config;

/**
 * 设备规格客户端（access → iot 的 /internal/device-specs）的 Feign 配置锚点。
 *
 * <p><b>本类刻意不带 {@code @Configuration}（issue #298）</b>：它只作为
 * {@code @FeignClient(configuration = DeviceSpecFeignConfiguration.class)} 的**子上下文**配置被引用。
 * 此前带该注解且包在宿主扫描范围内 ⇒ 被加载进**主上下文**，其 {@code Request.Options} 成为所有 Feign
 * 客户端的全局默认值，静默覆盖 Nacos 配置的超时（实测：主上下文取到 1000/5000）。</p>
 *
 * <p><b>超时值放在配置中心</b>：{@code spring.cloud.openfeign.client.config.deviceSpecClient.*}
 * （见 {@code deploy/nacos/ypbin-iot.yaml}）。设备规格是一次拉某租户全部设备与点位，
 * 预算比租约端点宽。</p>
 *
 * <p><b>不重试</b>：租约调度线程不能被远端拖住，拉取失败应让本轮失败、下一轮重试，而不是在调用点静默
 * 放大压力。该语义由 OpenFeign 默认的 {@code Retryer.NEVER_RETRY} 保证，无需本类声明 Bean。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public final class DeviceSpecFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /** 读超时（毫秒）：一次要返回某租户全部设备与点位，比租约端点给得宽一些。 */
    public static final int READ_TIMEOUT_MS = 5_000;

    private DeviceSpecFeignConfiguration() {
    }
}
