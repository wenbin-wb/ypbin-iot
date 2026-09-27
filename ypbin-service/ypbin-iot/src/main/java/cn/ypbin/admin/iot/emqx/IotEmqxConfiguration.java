/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * EMQX 相关配置的绑定入口。
 *
 * <p>只做参数绑定：本环境尚未部署 broker（决策 D3 的降级形态），因此这里**不装配任何
 * HTTP 客户端**——「平台自持凭据」的四个端点不依赖 broker，等 broker 到位再在本包加
 * REST 客户端（签发用户 / publish），届时用 {@link EmqxProperties#getBrokerHost()} 之外的管理面地址。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Configuration
@EnableConfigurationProperties(EmqxProperties.class)
public class IotEmqxConfiguration {
}
