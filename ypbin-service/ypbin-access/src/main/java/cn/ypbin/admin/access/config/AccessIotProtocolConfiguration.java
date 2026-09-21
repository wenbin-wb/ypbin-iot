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

import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.access.egress.LoggingAccessReadingSink;
import cn.ypbin.admin.access.egress.LoggingDataSink;
import cn.ypbin.admin.access.lease.AccessLeaseManager;
import cn.ypbin.admin.access.link.AccessConnectionSpecProvider;
import cn.ypbin.admin.access.link.AccessDeviceRegistry;
import cn.ypbin.admin.access.link.AccessSubscriptionPlanner;
import cn.ypbin.admin.access.link.DeviceSpecSource;
import cn.ypbin.admin.access.link.IotProtocolTenantLinkManager;
import cn.ypbin.admin.access.link.SubscriptionPlanner;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;


/**
 * 协议栈接入装配（增量 3b-2）：把 {@link DeviceSpecSource} 接到 iot-starter 的三个宿主 SPI 上。
 *
 * <p><b>开关与顺序</b>：本配置声明在 {@link AccessLeaseConfiguration} <b>之前</b>——3a 的日志实现
 * 是 {@code @ConditionalOnMissingBean}，只有真实现先注册，它才会优雅退让（这是 3a 预留的替换缝）。</p>
 *
 * <p><b>为什么用 {@code @ConditionalOnBean(DeviceSpecSource.class)} 兜底</b>：协议栈要能拿到设备与
 * 连接规格才有意义；取数实现（iot 服务的内部接口客户端）尚未落地时，本配置整体不生效，租约判定
 * 继续使用 3a 的日志实现——保证每一步都能单独部署验证，不会出现「装配了协议栈却没有取数来源」的半成品。</p>
 *
 * <p>启动顺序提醒：{@link AccessDeviceRegistry#loadAll()} 由框架在 {@code ApplicationReadyEvent}
 * 调用一次，因此租约的<b>注册/领取握手必须在此之前完成</b>（3a 的 {@code AccessStartupRunner} 已保证）；
 * 否则首轮会以「无租约」引导设备，表现为「起得来但一个设备都没采」。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@AutoConfiguration(before = AccessLeaseConfiguration.class)
@ConditionalOnBean(DeviceSpecSource.class)
public class AccessIotProtocolConfiguration {

    /**
     * 设备注册表：只喂「本节点租约内」的租户设备。
     *
     * <p>用 {@link ObjectProvider} 惰性取租约集合，避免与本配置里创建的
     * {@link TenantLinkManager}、以及依赖它的 {@link AccessLeaseManager} 形成构造期循环依赖。</p>
     *
     * @param specSource    设备/连接规格来源
     * @param leaseProvider 租约管理器（惰性解析）
     * @return 设备注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessDeviceRegistry accessDeviceRegistry(DeviceSpecSource specSource,
                                                     ObjectProvider<AccessLeaseManager> leaseProvider) {
        Supplier<Set<Long>> heldTenants = () -> {
            AccessLeaseManager leaseManager = leaseProvider.getIfAvailable();
            return leaseManager == null ? Set.of() : leaseManager.heldTenants();
        };
        return new AccessDeviceRegistry(specSource, heldTenants);
    }

    /**
     * 连接参数提供者（框架 SPI）。
     *
     * @param specSource 设备/连接规格来源
     * @return 提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionSpecProvider accessConnectionSpecProvider(DeviceSpecSource specSource) {
        return new AccessConnectionSpecProvider(specSource);
    }

    /**
     * 真实链路管理器（替换 3a 的日志实现）。
     *
     * @param specSource 设备/连接规格来源
     * @param registry   设备注册表
     * @return 链路管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantLinkManager iotProtocolTenantLinkManager(DeviceSpecSource specSource,
                                                          AccessDeviceRegistry registry,
                                                          SubscriptionPlanner planner) {
        return new IotProtocolTenantLinkManager(specSource, registry, planner);
    }

    /**
     * 映射后读数出口（3b-2 用日志占位；M-2 换成有界队列 → 微批 → EMQX）。
     *
     * @return 读数出口
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessReadingSink accessReadingSink() {
        return new LoggingAccessReadingSink();
    }

    /**
     * 协议栈批量出口占位（3b-2 只需日志/内存实现）。
     *
     * @return 批量出口
     */
    @Bean
    @ConditionalOnMissingBean
    public DataSink loggingDataSink() {
        return new LoggingDataSink();
    }

    /**
     * 订阅规划器：会话从协议栈 {@code IotLifecycle.sessions()} 惰性取（避免构造期依赖顺序问题）。
     *
     * @param lifecycleProvider 协议栈生命周期（由 iot-starter 装配）
     * @param objectMapper      点位清单反序列化
     * @param readingSink       映射后读数出口
     * @return 订阅规划器
     */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionPlanner accessSubscriptionPlanner(ObjectProvider<IotLifecycle> lifecycleProvider,
                                                        ObjectMapper objectMapper,
                                                        AccessReadingSink readingSink) {
        Supplier<Map<String, DeviceSession>> sessions = () -> {
            IotLifecycle lifecycle = lifecycleProvider.getIfAvailable();
            return lifecycle == null ? Map.of() : lifecycle.sessions();
        };
        return new AccessSubscriptionPlanner(sessions, objectMapper, readingSink);
    }

    /**
     * 把注册表也暴露为框架的 {@link DeviceRegistry} 类型，供 iot-starter 收集。
     *
     * @param registry 设备注册表
     * @return 同一个实例（按框架 SPI 类型暴露）
     */
    @Bean
    @ConditionalOnMissingBean(DeviceRegistry.class)
    public DeviceRegistry iotDeviceRegistry(AccessDeviceRegistry registry) {
        return registry;
    }
}
