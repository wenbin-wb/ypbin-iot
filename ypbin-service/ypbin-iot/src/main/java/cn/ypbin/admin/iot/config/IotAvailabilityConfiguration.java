/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.config;

import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 断档与可用率装配 + 参数自检。
 *
 * <p>参数不合法必须在**启动期**就拒绝：K=0 会让「任何时刻都算断档」（可用率恒 0），
 * 兜底周期 ≤0 会让 SQL 的时间推进条件退化成「立即断档」——这类错误只会在运行期以「数据全错」的形式出现，
 * 属于最难查的一类。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AvailabilityProperties.class)
public class IotAvailabilityConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotAvailabilityConfiguration.class);

    private final AvailabilityProperties properties;

    public IotAvailabilityConfiguration(AvailabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 断档扫描已关闭（{}.enabled=false）：内部上报端点仍可用，但静默设备不会被发现",
                AvailabilityProperties.PREFIX);
            return;
        }
        List<String> issues = new ArrayList<>();
        if (properties.getKFactor() < 1) {
            issues.add(AvailabilityProperties.PREFIX + ".k-factor 必须 ≥ 1（0 会让任何时刻都算断档）");
        }
        if (properties.getFallbackIntervalMs() <= 0) {
            issues.add(AvailabilityProperties.PREFIX + ".fallback-interval-ms 必须为正数");
        }
        if (properties.getScanIntervalMs() <= 0) {
            issues.add(AvailabilityProperties.PREFIX + ".scan-interval-ms 必须为正数");
        }
        if (properties.getScanBatchSize() <= 0) {
            issues.add(AvailabilityProperties.PREFIX + ".scan-batch-size 必须为正数");
        }
        if (properties.getDefaultWindowHours() <= 0) {
            issues.add(AvailabilityProperties.PREFIX + ".default-window-hours 必须为正数");
        }
        if (!issues.isEmpty()) {
            throw new IllegalStateException("[iot] 可用率参数自检未通过：" + String.join("；", issues));
        }
        log.info("[iot] 可用率参数自检通过：kFactor={} fallbackIntervalMs={} scanIntervalMs={} scanBatchSize={}",
            properties.getKFactor(), properties.getFallbackIntervalMs(), properties.getScanIntervalMs(),
            properties.getScanBatchSize());
    }
}
