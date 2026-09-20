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

import cn.ypbin.admin.iot.lease.LeaseProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 租约装配与启动自检。
 *
 * <p>自检是 <b>fail-fast</b>：租约参数配错（ttl 小于一个续约周期、周期为 0）会让节点反复把自己判失效、
 * 触发无谓接管，而这类错误只在启动瞬间可判。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LeaseProperties.class)
public class IotLeaseConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotLeaseConfiguration.class);

    private final LeaseProperties properties;

    /**
     * 构造装配。
     *
     * @param properties 租约参数
     */
    public IotLeaseConfiguration(LeaseProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 租约维护已关闭（{}.enabled=false）：内部端点与失效扫描都不装配，"
                + "access 调用会得到 404 —— 只应在本地调试时这么配", LeaseProperties.PREFIX);
            return;
        }
        List<String> issues = new ArrayList<>();
        if (properties.getTtl() == null || properties.getTtl().isZero() || properties.getTtl().isNegative()) {
            issues.add(LeaseProperties.PREFIX + ".ttl 必须为正数");
        }
        if (properties.getScanIntervalMs() <= 0) {
            issues.add(LeaseProperties.PREFIX + ".scan-interval-ms 必须为正数");
        }
        if (properties.getExpectedRenewInterval() == null || properties.getExpectedRenewInterval().isZero()
            || properties.getExpectedRenewInterval().isNegative()) {
            issues.add(LeaseProperties.PREFIX + ".expected-renew-interval 必须为正数");
        }
        if (issues.isEmpty()) {
            Duration ttl = properties.getTtl();
            if (ttl.compareTo(properties.getExpectedRenewInterval()) <= 0) {
                issues.add(LeaseProperties.PREFIX + ".ttl(" + ttl + ") 必须大于 expected-renew-interval("
                    + properties.getExpectedRenewInterval() + ")：否则一次抖动就会让续约跨过到期时间、"
                    + "把活着的节点判成失效并触发接管");
            }
            if (ttl.compareTo(properties.getExpectedRenewInterval().plusSeconds(4)) <= 0) {
                // 与旧仓一致的口径：还要容得下「一次续约最坏耗时」（connect 1s + read 3s）
                issues.add(LeaseProperties.PREFIX + ".ttl(" + ttl + ") 必须大于 expected-renew-interval + 4s"
                    + "（一次内部调用最坏耗时）");
            }
        }
        if (!issues.isEmpty()) {
            throw new IllegalStateException("[iot] 租约参数自检未通过：" + String.join("；", issues));
        }
        log.info("[iot] 租约参数自检通过：ttl={} scanIntervalMs={} expectedRenewInterval={} assignableTenants={}",
            properties.getTtl(), properties.getScanIntervalMs(), properties.getExpectedRenewInterval(),
            properties.getAssignableTenantIds().size());
    }
}
