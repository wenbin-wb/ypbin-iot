/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system;

import cn.ypbin.admin.common.config.LicenseIssuerProperties;
import cn.ypbin.admin.common.config.RemoteIngestClientAutoConfiguration;
import cn.ypbin.admin.system.api.feign.ISystemClientFallback;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * System 服务启动器（微服务版）。
 *
 * <p><b>为什么排除 {@link RemoteIngestClientAutoConfiguration}</b>（2026-10-20 dev 真实启动失败）：
 * 该自动配置按<b>类型</b>注入 {@code ISystemClient}，用于把日志/埋点<b>上报给 system</b>；而 system
 * 自己就是被上报方，不该反向 HTTP 调自己。更直接的后果是「一个类型两个 Bean」：
 * 本仓 {@code SystemClientImpl}（{@code @RestController} 实现 {@code ISystemClient}，服务端）
 * 与 {@code ISystemClientFallback}（{@code @Component}，且在 system 的扫描根
 * {@code cn.ypbin.admin.system.**} 内）同时存在 ⇒ 注入点报
 * {@code required a single bean, but 2 were found}。故在 system 侧排除该自动配置。</p>
 *
 * <p><b>为什么同时把 {@link ISystemClientFallback} 排除出扫描</b>：system 是服务端，降级实现
 * 在它内部只会制造歧义（它真正需要的是 {@code SystemClientImpl}）。两处一起排除后，system 里
 * {@code ISystemClient} 只剩服务端实现，语义清晰。</p>
 *
 * @author wenbin
 * @since 2026-09-01
 */
@SpringBootApplication(exclude = RemoteIngestClientAutoConfiguration.class)
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
    classes = ISystemClientFallback.class))
@MapperScan("cn.ypbin.admin.**.mapper")
@EnableConfigurationProperties(LicenseIssuerProperties.class)
public class SystemServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemServiceApplication.class, args);
    }
}
