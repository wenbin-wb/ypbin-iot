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
import cn.ypbin.admin.iot.lease.ILeaseClient;
import io.micrometer.core.instrument.MeterRegistry;
import cn.ypbin.admin.access.link.LoggingTenantLinkManager;
import cn.ypbin.admin.access.link.TenantLinkManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;


/**
 * access 的租约状态机与链路端口装配。
 *
 * <p>⚠️ <b>必须是 {@code @AutoConfiguration}</b>：{@code @ConditionalOnMissingBean} 的顺序保证只有自动配置才有，
 * 写在用户 {@code @Configuration} 里是假缝（3b 提供的真实现会 back off，协议栈静默不生效）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@AutoConfiguration
@EnableConfigurationProperties(AccessProperties.class)
public class AccessLeaseConfiguration {

    /**
     * 链路控制端口的 3a 实现（宿主提供自己的实现即整体替换）。
     *
     * @return 链路管理实现
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantLinkManager loggingTenantLinkManager() {
        return new LoggingTenantLinkManager();
    }

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
