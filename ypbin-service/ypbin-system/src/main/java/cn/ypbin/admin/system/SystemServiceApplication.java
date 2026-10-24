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
 * <p><b>为什么把 {@link ISystemClientFallback} 排除出扫描</b>：system 是服务端，降级实现
 * 在它内部只会制造歧义（它真正需要的是 {@code SystemClientImpl}）。排除后 system 里
 * {@code ISystemClient} 只剩服务端实现，语义清晰。</p>
 *
 * <p><b>历史：此前还 exclude 了 {@code RemoteIngestClientAutoConfiguration}</b>（2026-10-20
 * dev 启动失败：该自动配置按类型注入 {@code ISystemClient}，与上面的两个 Bean 歧义冲突）。
 * 2026-10-23 同步 upstream 后该问题<b>已从根上消失</b>：跨服务上报改由普通 {@code @Configuration}
 * {@code cn.ypbin.admin.common.ingest.RemoteIngestClientConfiguration} 承担，且<b>只由 auth/ai 通过
 * {@code @Import} 引入</b>——system 不引入它，就不存在「Feign 回自己」的回环。故本类不再 exclude
 * （Spring Boot 4 会对「exclude 一个不是自动配置类的类型」直接 fail-fast，2026-10-24 dev 实测）。</p>
 *
 * @author wenbin
 * @since 2026-09-01
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
    classes = ISystemClientFallback.class))
@MapperScan("cn.ypbin.admin.**.mapper")
@EnableConfigurationProperties(LicenseIssuerProperties.class)
public class SystemServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemServiceApplication.class, args);
    }
}
