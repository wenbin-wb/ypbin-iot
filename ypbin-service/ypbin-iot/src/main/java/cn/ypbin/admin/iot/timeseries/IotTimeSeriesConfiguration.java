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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
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
        // 截至本增量：JDBC 写入器尚未实现 ⇒ 明确拒绝启动，绝不假装在写
        throw new IllegalStateException(TimeSeriesProperties.PREFIX
            + ".enabled=true，但 IoTDB JDBC 写入器尚未实现（见 docs/IOT-PLATFORM-DESIGN.md §5.2.1 与 ROADMAP 四点二十）："
            + "请保持 false，或等实现与容器 IT 就位后再开启");
    }

    /**
     * 时序写入器：未启用时用「WARN 一次并丢弃」的实现（不静默），启用时由后续增量提供 JDBC 实现。
     *
     * @return 写入器
     */
    @Bean
    public TimeSeriesWriter timeSeriesWriter() {
        return new LoggingTimeSeriesWriter(properties.isEnabled() ? "JDBC 写入器未实现" : "未启用");
    }
}
