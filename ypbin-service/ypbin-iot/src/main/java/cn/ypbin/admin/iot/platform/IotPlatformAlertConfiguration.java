/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台自告警配置装配（看板 #10）——与 `IotAlertConfiguration` 同范式：
 * `@Configuration` + `@EnableConfigurationProperties`。
 *
 * <p>本类**不**加 `@EnableScheduling`：该注解已由 `IotAlertConfiguration` 全局开启
 * （仓内多个模块共用同一个调度器），此处再开一次没有意义、还会让"谁开了调度"变模糊。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Configuration
@EnableConfigurationProperties(PlatformAlertProperties.class)
public class IotPlatformAlertConfiguration {
}
