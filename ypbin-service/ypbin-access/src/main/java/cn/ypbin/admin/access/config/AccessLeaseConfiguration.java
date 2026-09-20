/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import cn.ypbin.admin.access.lease.AccessLeaseManager;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * access 的租约状态机装配。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Configuration
@EnableConfigurationProperties(AccessProperties.class)
public class AccessLeaseConfiguration {

    /**
     * 租约状态机。
     *
     * @param leaseClient   租约客户端
     * @param linkManager   链路控制端口
     * @param properties    节点参数
     * @param meterRegistry 指标注册表
     * @return 租约状态机
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessLeaseManager accessLeaseManager(ILeaseClient leaseClient, TenantLinkManager linkManager,
            AccessProperties properties, MeterRegistry meterRegistry) {
        return new AccessLeaseManager(leaseClient, linkManager, properties, meterRegistry);
    }
}
