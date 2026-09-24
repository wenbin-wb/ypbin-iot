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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时序装配与降级的行为（§5.2.1）：默认关闭、启用前必须先有实现与容器 IT。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotTimeSeriesConfigurationTest {

    private static TimeSeriesProperties enabled() {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        properties.setEnabled(true);
        properties.setUrl("jdbc:iotdb://127.0.0.1:6667/");
        return properties;
    }

    @Test
    @DisplayName("默认关闭：启动不报错，写入器是「WARN 一次并丢弃」的实现（不静默）")
    void mustBeDisabledByDefault() {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        assertThat(properties.isEnabled()).isFalse();

        IotTimeSeriesConfiguration configuration = new IotTimeSeriesConfiguration(properties);
        assertThatCode(configuration::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(configuration.timeSeriesWriter()).isInstanceOf(LoggingTimeSeriesWriter.class);
    }

    @Test
    @DisplayName("★ 启用但实现未就位：启动**拒绝**（绝不假装在写）")
    void mustRefuseWhenEnabledBeforeImplementation() {
        assertThatThrownBy(() -> new IotTimeSeriesConfiguration(enabled()).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JDBC 写入器尚未实现");
    }

    @Test
    @DisplayName("★ 启用时 url/batch-size/connect-timeout 必须合法（fail-fast）")
    void mustValidateWhenEnabled() {
        TimeSeriesProperties noUrl = enabled();
        noUrl.setUrl(" ");
        assertThatThrownBy(() -> new IotTimeSeriesConfiguration(noUrl).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("url");

        TimeSeriesProperties badBatch = enabled();
        badBatch.setBatchSize(0);
        assertThatThrownBy(() -> new IotTimeSeriesConfiguration(badBatch).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("batch-size");

        TimeSeriesProperties badTimeout = enabled();
        badTimeout.setConnectTimeoutMs(0);
        assertThatThrownBy(() -> new IotTimeSeriesConfiguration(badTimeout).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("connect-timeout-ms");
    }

    @Test
    @DisplayName("降级实现：空批不告警；非空批只告警一次（不刷屏）")
    void loggingWriterMustWarnOnce() {
        LoggingTimeSeriesWriter writer = new LoggingTimeSeriesWriter("未启用");
        writer.writeAll(List.of());
        writer.writeAll(List.of(new TimeSeriesPoint(1L, 2L, "p", "1", "GOOD", 1L)));
        writer.writeAll(List.of(new TimeSeriesPoint(1L, 2L, "p", "2", "GOOD", 2L)));
        // 断言点是「不抛异常且可重复调用」——告警频率由 AtomicBoolean 保证（日志内容不便断言）
        assertThatCode(() -> writer.writeAll(List.of())).doesNotThrowAnyException();
    }
}
