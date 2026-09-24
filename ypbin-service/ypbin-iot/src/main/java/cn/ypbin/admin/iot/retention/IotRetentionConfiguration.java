/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 保留清理的装配与**启动自检**（D0.8）。
 *
 * <p>自检的意义：删除不可逆，配置写错（0/负数天、0 间隔）必须在**启动时**就拒绝，
 * 而不是等到某个凌晨把整表删了才发现。与 {@code IotAvailabilityConfiguration} 同一取向。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RetentionProperties.class)
public class IotRetentionConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotRetentionConfiguration.class);

    private final RetentionProperties properties;

    public IotRetentionConfiguration(RetentionProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 保留清理已关闭（{}.enabled=false）：断档事件与维护窗口将无限增长",
                RetentionProperties.PREFIX);
            return;
        }
        if (properties.getOutageEventDays() <= 0 || properties.getMaintenanceWindowDays() <= 0) {
            throw new IllegalStateException(RetentionProperties.PREFIX
                + ".outage-event-days / maintenance-window-days 必须为正数（0 或负数会让清理变成清空全表）");
        }
        if (properties.getCleanupIntervalMs() <= 0) {
            throw new IllegalStateException(RetentionProperties.PREFIX + ".cleanup-interval-ms 必须为正数");
        }
        log.info("[iot] 保留清理已启用：断档事件 {} 天、维护窗口 {} 天，每 {} ms 清理一次",
            properties.getOutageEventDays(), properties.getMaintenanceWindowDays(),
            properties.getCleanupIntervalMs());
    }

    /**
     * 清理服务（用 {@code @Bean} 装配，便于测试替换）。
     *
     * @param outageEventMapper      断档事件 Mapper
     * @param maintenanceWindowMapper 维护窗口 Mapper
     * @param livenessMapper         提供数据库时钟
     * @param registry               指标
     * @return 清理服务
     */
    @Bean
    public RetentionCleanupService retentionCleanupService(
            cn.ypbin.admin.iot.mapper.OutageEventMapper outageEventMapper,
            cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper maintenanceWindowMapper,
            cn.ypbin.admin.iot.mapper.DeviceLivenessMapper livenessMapper,
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new RetentionCleanupServiceImpl(outageEventMapper, maintenanceWindowMapper, livenessMapper,
            properties, registry);
    }
}
