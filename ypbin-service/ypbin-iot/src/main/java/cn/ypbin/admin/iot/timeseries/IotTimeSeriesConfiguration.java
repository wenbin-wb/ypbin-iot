/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时序写入器的装配与启动自检（§5.2.1）。
 *
 * <p><b>本增量只落「契约 + 装配 + 调用点」</b>：JDBC 写入器与容器 IT 属下一段。
 * 因此当 `enabled=true` 而实现尚未就位时，**启动即拒绝**（fail-fast）而不是静默降级——
 * 否则运维会以为时序在写，实际什么都没落（本仓「禁静默降级」）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Configuration
@EnableConfigurationProperties(TimeSeriesProperties.class)
public class IotTimeSeriesConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotTimeSeriesConfiguration.class);

    /** 表名白名单（会被拼进 SQL 的配置项必须校验）。 */
    private static final String TABLE_NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final TimeSeriesProperties properties;

    public IotTimeSeriesConfiguration(TimeSeriesProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.info("[iot] 时序写入未启用（{}.enabled=false）：读数只写最新值/断档，不落历史时序",
                TimeSeriesProperties.PREFIX);
            return;
        }
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new IllegalStateException(TimeSeriesProperties.PREFIX
                + ".url 不能为空（启用时序写入必须给 IoTDB JDBC 地址）");
        }
        if (properties.getBatchSize() <= 0) {
            throw new IllegalStateException(TimeSeriesProperties.PREFIX + ".batch-size 必须为正数");
        }
        if (properties.getConnectTimeoutMs() <= 0) {
            throw new IllegalStateException(TimeSeriesProperties.PREFIX + ".connect-timeout-ms 必须为正数");
        }
        if (properties.getDbRowsProbeIntervalMs() <= 0) {
            // 间隔非正会让调度器拒绝注册或退化成忙轮询（真值对账本身是全表聚合，不能高频）
            throw new IllegalStateException(TimeSeriesProperties.PREFIX + ".db-rows-probe-interval-ms 必须为正数");
        }
        if (properties.getDbRowsProbeQueryTimeoutSeconds() <= 0) {
            throw new IllegalStateException(TimeSeriesProperties.PREFIX
                + ".db-rows-probe-query-timeout-seconds 必须为正数");
        }
        if (!properties.getTableName().matches(TABLE_NAME_PATTERN)) {
            // 表名会被拼进 SQL：只允许普通标识符（配置注入防护）
            throw new IllegalStateException(TimeSeriesProperties.PREFIX + ".table-name 只允许字母/数字/下划线");
        }
        // 驱动 jar 不含 META-INF/services/java.sql.Driver ⇒ DriverManager 不会自动发现它，
        // 必须显式加载后再自检，否则这里恒抛「驱动不可用」（CI 实测：No suitable driver found）
        IotDbDriverRegistrar.ensureRegistered();
        try {
            if (DriverManager.getDriver(properties.getUrl()) == null) {
                throw new IllegalStateException("未注册任何 JDBC 驱动");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(TimeSeriesProperties.PREFIX
                + ".enabled=true，但 IoTDB JDBC 驱动不可用（请确认 org.apache.iotdb:iotdb-jdbc 在运行时类路径）：url="
                + properties.getUrl(), ex);
        }
        // JDBC 的登录超时是全局设置（驱动层面无逐连接参数）：启动时设一次并记录
        DriverManager.setLoginTimeout(Math.max(1, properties.getConnectTimeoutMs() / 1000));
        log.info("[iot] 时序写入已启用：url={} 表={} 批量={} 真值对账间隔={}ms", properties.getUrl(),
            properties.getTableName(), properties.getBatchSize(), properties.getDbRowsProbeIntervalMs());
    }

    /**
     * 时序写入器：未启用时用「WARN 一次并丢弃」的实现（不静默）；启用时用 IoTDB JDBC 实现。
     *
     * @param meterRegistry 指标注册表
     * @return 写入器
     */
    @Bean
    public TimeSeriesWriter timeSeriesWriter(MeterRegistry meterRegistry) {
        if (!properties.isEnabled()) {
            return new LoggingTimeSeriesWriter("未启用");
        }
        return new IotDbTimeSeriesWriter(properties, meterRegistry);
    }

    /**
     * 历史查询的存储实现：未启用时用「不可用」实现（查询端点据此明确报错，而不是返回空列表）；
     * 启用时用 IoTDB JDBC 实现（查询走 {@code IotDbTimeSeriesStore}）。
     *
     * @return 存储实现
     */
    @Bean
    public TimeSeriesStore timeSeriesStore() {
        if (!properties.isEnabled()) {
            return new UnavailableTimeSeriesStore();
        }
        return new IotDbTimeSeriesStore(properties);
    }

    /**
     * 时序库可达性健康指示器：**仅在启用时注册**（未启用不注册 ⇒ 不会让整体健康状态因「未启用」变 DOWN）。
     *
     * <p>它调用 {@code TimeSeriesStore.reachable()} 做真实探测（建一条连接即视为可达），
     * 结果自带 {@value IotDbTimeSeriesHealthIndicator#DEFAULT_CACHE_TTL_MS} ms 缓存，
     * 避免健康端点被轮询时把探测变成对 IoTDB 的压测。运维影响：启用后 IoTDB 不可达会让
     * {@code /actuator/health} 变 DOWN；当前 compose 未对 ypbin-iot 配 healthcheck，不会触发重启。</p>
     *
     * @param timeSeriesStore 时序存储（真实探测入口）
     * @return 健康指示器
     */
    @Bean
    @ConditionalOnProperty(prefix = TimeSeriesProperties.PREFIX, name = "enabled", havingValue = "true")
    public HealthIndicator iotDbTimeSeriesHealthIndicator(TimeSeriesStore timeSeriesStore) {
        return new IotDbTimeSeriesHealthIndicator(timeSeriesStore, Clock.systemUTC());
    }
}
