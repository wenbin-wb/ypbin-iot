/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.starter.core.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 总开关门禁的用例（设计 §3.6.2）。
 *
 * <p>核心断言只有一条但极其重要：{@code ypbin.alert.enabled=false} 时**抛异常**，
 * 而不是返回空集合——空集合会被读成「没有告警」（最危险的假阴性）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertGateTest {

    @Test
    @DisplayName("★ 总开关关闭时必须抛业务异常（不能返回空列表——空列表 = 「没有告警」的假阴性）")
    void disabledMustFailInsteadOfReturningEmpty() {
        AlertProperties properties = new AlertProperties();
        properties.setEnabled(false);
        AlertGate gate = new AlertGate(properties);
        assertThatThrownBy(gate::requireEnabled)
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("告警能力未启用")
            .hasMessageContaining("空结果会被误读成「没有告警」");
    }

    @Test
    @DisplayName("总开关打开时放行（不自检异常）")
    void enabledPasses() {
        AlertProperties properties = new AlertProperties();
        properties.setEnabled(true);
        new AlertGate(properties).requireEnabled();
    }

    @Test
    @DisplayName("N1/N2 指标齐备：活性三项 + 六类失败/跳过计数都在注册表里可读")
    void metricsAreRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertMetrics metrics = new AlertMetrics(registry);
        // N1 活性
        assertThat(registry.find(AlertMetrics.METRIC_LAST_SUCCESS_TS).gauge()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_LAG).gauge()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_ROUND_DURATION).timer()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_ROUNDS).counter()).isNotNull();
        // N2 「不可判定」与「读不到」
        assertThat(registry.find("iot.alert.evaluate.skipped_non_numeric").counter()).isNotNull();
        assertThat(registry.find("iot.alert.evaluate.skipped_bad_quality").counter()).isNotNull();
        assertThat(registry.find("iot.alert.evaluate.skipped_stale").counter()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_REDIS_FAILED).counter()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_RULE_LOAD_FAILED).counter()).isNotNull();
        assertThat(registry.find(AlertMetrics.METRIC_TIMESERIES_FAILED).counter()).isNotNull();
        // 每个「跳过类」判定结果都必须有对应指标（防止新增结果类型时漏接指标）
        for (AlertValueVerdict verdict : AlertValueVerdict.values()) {
            if (verdict.getMetricName() == null) {
                continue;
            }
            assertThat(metrics.registeredSkipMetrics()).containsKey(verdict);
            assertThat(registry.find(verdict.getMetricName()).counter())
                .as("%s 的指标必须真的注册了", verdict).isNotNull();
        }
        assertThat(AlertValueVerdict.skippedMetricNames())
            .containsExactlyInAnyOrder("iot.alert.evaluate.skipped_missing",
                "iot.alert.evaluate.skipped_non_numeric", "iot.alert.evaluate.skipped_bad_quality",
                "iot.alert.evaluate.skipped_stale", "iot.alert.evaluate.skipped_invalid_condition");
    }

    @Test
    @DisplayName("活性指标语义：从未成功过时 lag = -1（**不用 0**，0 与「刚刚跑过」读数相同）")
    void lagIsMinusOneBeforeFirstSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertMetrics metrics = new AlertMetrics(registry);
        assertThat(registry.find(AlertMetrics.METRIC_LAG).gauge().value()).isEqualTo(-1d);
        metrics.roundSucceeded();
        assertThat(registry.find(AlertMetrics.METRIC_LAG).gauge().value()).isGreaterThanOrEqualTo(0d);
        assertThat(registry.find(AlertMetrics.METRIC_LAST_SUCCESS_TS).gauge().value()).isGreaterThan(0d);
    }
}
