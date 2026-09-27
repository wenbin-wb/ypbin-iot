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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动自检的用例：非法配置必须**在启动时**就拒绝，而不是等到凌晨清理把表删空、或通知一条都发不出去。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class IotAlertConfigurationTest {

    private static IotAlertConfiguration configuration(AlertProperties properties) {
        return new IotAlertConfiguration(properties);
    }

    @Test
    @DisplayName("默认配置必须能通过自检（开箱可用）")
    void defaultsPass() {
        assertThatCode(() -> configuration(new AlertProperties()).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("总开关关闭时自检直接放行（关闭状态不做其余校验）")
    void disabledSkipsValidation() {
        AlertProperties properties = new AlertProperties();
        properties.setEnabled(false);
        properties.setEvaluateIntervalMs(0);
        assertThatCode(() -> configuration(properties).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("评估周期 / 批大小 / 保留天数非法 ⇒ 启动即失败")
    void nonPositiveValuesRejected() {
        AlertProperties zeroInterval = new AlertProperties();
        zeroInterval.setEvaluateIntervalMs(0);
        assertThatThrownBy(() -> configuration(zeroInterval).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("evaluate-interval-ms");

        AlertProperties zeroBatch = new AlertProperties();
        zeroBatch.setEvaluateBatchSize(0);
        assertThatThrownBy(() -> configuration(zeroBatch).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("evaluate-batch-size");

        AlertProperties negativeRetention = new AlertProperties();
        negativeRetention.setRetentionResolvedDays(-1);
        assertThatThrownBy(() -> configuration(negativeRetention).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("retention-resolved-days");
    }

    @Test
    @DisplayName("陈旧兜底 TTL 大于上限 / 倍数小于 1 ⇒ 启动即失败（否则陈旧判定永不生效或永远生效）")
    void stalenessConfigRejected() {
        AlertProperties fallbackTooBig = new AlertProperties();
        fallbackTooBig.setStalenessFallbackTtlMs(9_000_000L);
        assertThatThrownBy(() -> configuration(fallbackTooBig).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("staleness-fallback-ttl-ms 不能大于 staleness-max-ttl-ms");

        AlertProperties zeroMultiplier = new AlertProperties();
        zeroMultiplier.setStalenessIntervalMultiplier(0);
        assertThatThrownBy(() -> configuration(zeroMultiplier).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("staleness-interval-multiplier 必须 ≥ 1");
    }

    @Test
    @DisplayName("渠道码 / 级别码打错 ⇒ 启动即失败（打错一个字母就等于「通知静默失效」）")
    void enumCodesValidated() {
        AlertProperties badChannel = new AlertProperties();
        badChannel.setDefaultNotifyChannels("INBOX,WEBHOOK");
        assertThatThrownBy(() -> configuration(badChannel).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("含未知渠道码");

        AlertProperties badSeverity = new AlertProperties();
        badSeverity.setDefaultSeverity("FATAL");
        assertThatThrownBy(() -> configuration(badSeverity).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("default-severity");

    }

    @Test
    @DisplayName("退避配置为空或含非正数 ⇒ 启动即失败")
    void backoffValidated() {
        AlertProperties empty = new AlertProperties();
        empty.setNotifyBackoffSeconds(List.of());
        assertThatThrownBy(() -> configuration(empty).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("notify-backoff-seconds");

        AlertProperties zero = new AlertProperties();
        zero.setNotifyBackoffSeconds(List.of(0L));
        assertThatThrownBy(() -> configuration(zero).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("notify-backoff-seconds 的每个值都必须为正数");
    }

    @Test
    @DisplayName("最大尝试次数多于退避步数 ⇒ 只告警不阻断（不是配置错误，但必须让人知道）")
    void attemptBeyondBackoffOnlyWarns() {
        AlertProperties properties = new AlertProperties();
        properties.setNotifyMaxAttempt(9);
        assertThatCode(() -> configuration(properties).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
