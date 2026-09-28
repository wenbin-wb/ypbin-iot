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

import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 告警能力的装配与**启动自检**。
 *
 * <p>自检的意义与 {@code IotRetentionConfiguration} 同源：配置写错（间隔为 0、保留天数为负、
 * 退避次数与最大尝试次数不匹配、渠道码打错）必须在**启动时**就拒绝，而不是等到某个凌晨
 * 「清理把表删空」或「通知一条都发不出去」才发现。</p>
 *
 * <p>自检只做**纯配置**层面的校验，不碰数据库：把「配置错」与「环境不通」这两类失败分开，
 * 值班才知道该改哪一边。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AlertProperties.class)
public class IotAlertConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotAlertConfiguration.class);

    private final AlertProperties properties;

    public IotAlertConfiguration(AlertProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 告警能力已关闭（{}.enabled=false）：评估器不装配、接口返回明确失败"
                + "（**不是**空列表——空列表会被误读成「没有告警」）", AlertProperties.PREFIX);
            return;
        }
        requirePositive(properties.getEvaluateIntervalMs(), "evaluate-interval-ms");
        requireNonNegative(properties.getEvaluateInitialDelayMs(), "evaluate-initial-delay-ms");
        requirePositive(properties.getEvaluateBatchSize(), "evaluate-batch-size");
        requirePositive(properties.getStalenessFallbackTtlMs(), "staleness-fallback-ttl-ms");
        requirePositive(properties.getStalenessMaxTtlMs(), "staleness-max-ttl-ms");
        if (properties.getStalenessFallbackTtlMs() > properties.getStalenessMaxTtlMs()) {
            throw new IllegalStateException(AlertProperties.PREFIX
                + ".staleness-fallback-ttl-ms 不能大于 staleness-max-ttl-ms（否则兜底值永远被夹掉）");
        }
        if (properties.getStalenessIntervalMultiplier() < 1) {
            throw new IllegalStateException(AlertProperties.PREFIX
                + ".staleness-interval-multiplier 必须 ≥ 1（否则任何读数都会被判陈旧）");
        }
        requirePositive(properties.getDefaultTriggerThreshold(), "default-trigger-threshold");
        requirePositive(properties.getDefaultPendingTtlSec(), "default-pending-ttl-sec");
        requirePositive(properties.getDefaultRepeatIntervalSec(), "default-repeat-interval-sec");
        requirePositive(properties.getMaxPointsPerRule(), "max-points-per-rule");
        requirePositive(properties.getMaxPageSize(), "max-page-size");
        requireCode(AlertSeverity.of(properties.getDefaultSeverity()) != null,
            "default-severity 只能是 INFO / WARNING / CRITICAL");
        requireChannels(properties.getDefaultNotifyChannels(), "default-notify-channels");
        requirePositive(properties.getRetentionResolvedDays(), "retention-resolved-days");
        requirePositive(properties.getRetentionCleanupIntervalMs(), "retention-cleanup-interval-ms");
        requireNonNegative(properties.getRetentionInitialDelayMs(), "retention-initial-delay-ms");
        requirePositive(properties.getRetentionBatchSize(), "retention-batch-size");
        requirePositive(properties.getNotifyMaxAttempt(), "notify-max-attempt");
        requirePositive(properties.getNotifyThrottlePerMinute(), "notify-throttle-per-minute");
        requirePositive(properties.getNotifyDispatchBatchSize(), "notify-dispatch-batch-size");
        requirePositive(properties.getNotifyDispatchIntervalMs(), "notify-dispatch-interval-ms");
        requireNonNegative(properties.getNotifyInitialDelayMs(), "notify-initial-delay-ms");
        requirePositive(properties.getNotifyMaxErrorLength(), "notify-max-error-length");
        requirePositive(properties.getNotifyMaxTextLength(), "notify-max-text-length");
        if (properties.getNotifyBackoffSeconds().isEmpty()) {
            throw new IllegalStateException(AlertProperties.PREFIX
                + ".notify-backoff-seconds 不能为空（至少给一个退避值）");
        }
        for (Long backoff : properties.getNotifyBackoffSeconds()) {
            if (backoff == null || backoff <= 0) {
                throw new IllegalStateException(AlertProperties.PREFIX
                    + ".notify-backoff-seconds 的每个值都必须为正数");
            }
        }
        if (properties.getNotifyMaxAttempt() > properties.getNotifyBackoffSeconds().size() + 1) {
            // 不是致命错误，但会让「第 N 次失败后退避多久」落到兜底值上——必须让人知道
            log.warn("[iot] 告警通知最大尝试次数 {} 大于退避步数 {} + 1："
                    + "超出部分将复用最后一个退避值（建议两者对齐）",
                properties.getNotifyMaxAttempt(), properties.getNotifyBackoffSeconds().size());
        }
        if (!properties.isOutageEnabled()) {
            log.warn("[iot] 断档 → 告警映射已关闭（{}.outage-enabled=false）："
                + "设备离线/数据中断不会产生告警", AlertProperties.PREFIX);
        }
        log.info("[iot] 告警能力已启用：评估周期 {} ms、连续 {} 次、重复通知 {} s、"
                + "已恢复保留 {} 天、通知退避 {} s、每渠道每分钟上限 {}",
            properties.getEvaluateIntervalMs(), properties.getDefaultTriggerThreshold(),
            properties.getDefaultRepeatIntervalSec(), properties.getRetentionResolvedDays(),
            properties.getNotifyBackoffSeconds(), properties.getNotifyThrottlePerMinute());
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalStateException(AlertProperties.PREFIX + "." + key + " 必须为正数");
        }
    }

    private static void requireNonNegative(long value, String key) {
        if (value < 0) {
            throw new IllegalStateException(AlertProperties.PREFIX + "." + key + " 不能为负数");
        }
    }

    private static void requireCode(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(AlertProperties.PREFIX + "." + message);
        }
    }

    private static void requireChannels(String raw, String key) {
        try {
            AlertChannel.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(AlertProperties.PREFIX + "." + key + " 含未知渠道码："
                + ex.getMessage());
        }
    }
}
