/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 批量命令下发的配置装配。
 *
 * <p>为什么单独一个类而不是在 {@link CommandBatchProperties} 上标 {@code @Component}：本仓
 * （与 starter 一致，全仓 45 个 {@code @ConfigurationProperties} 类无一处标 {@code @Component}）
 * 的统一做法是「配置类只做绑定、由 {@code @EnableConfigurationProperties} 显式装配」——
 * 这样配置类**不在组件扫描里**，不会被无意注入到其它上下文（测试切片尤其容易踩到），
 * 装配点也集中可见。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Configuration
@EnableConfigurationProperties(CommandBatchProperties.class)
public class CommandBatchConfiguration {
}
