/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lock;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 后台任务分布式锁的装配与启动自检（#220）。
 *
 * <p><b>介质选择</b>：有 Redis（{@code StringRedisTemplate}）⇒ {@link RedisLockStore}（多实例正确）；
 * 没有 ⇒ {@link InMemoryLockStore}（**仅单实例正确**，打 WARN 暴露，不静默）。</p>
 *
 * <p><b>自检</b>：{@code renew-interval} 必须小于 {@code ttl}。否则锁会在第一次续期发生之前过期，
 * 表现为「偶尔两个副本同时执行」——这种配置错误只能在启动时判，绝不能留到生产上随机复现。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@Configuration
@EnableConfigurationProperties(LockProperties.class)
public class IotLockConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IotLockConfiguration.class);

    private final LockProperties properties;

    public IotLockConfiguration(LockProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 后台任务分布式锁已关闭（{}.enabled=false）：每个副本都会执行这些任务，"
                + "**仅单实例部署**可这么配", LockProperties.PREFIX);
            return;
        }
        if (properties.getTtl() == null || properties.getTtl().isZero() || properties.getTtl().isNegative()) {
            throw new IllegalStateException(LockProperties.PREFIX + ".ttl 必须为正数");
        }
        if (properties.getRenewInterval() == null || properties.getRenewInterval().isZero()
            || properties.getRenewInterval().isNegative()) {
            throw new IllegalStateException(LockProperties.PREFIX + ".renew-interval 必须为正数");
        }
        if (properties.getRenewInterval().compareTo(properties.getTtl()) >= 0) {
            throw new IllegalStateException(LockProperties.PREFIX + ".renew-interval("
                + properties.getRenewInterval() + ") 必须小于 ttl(" + properties.getTtl()
                + ")：否则锁会在第一次续期之前过期，多副本下会并发执行");
        }
        if (properties.getKeyPrefix() == null || properties.getKeyPrefix().isBlank()) {
            throw new IllegalStateException(LockProperties.PREFIX + ".key-prefix 不能为空");
        }
        log.info("[iot] 后台任务分布式锁已启用：keyPrefix={} ttl={} renewInterval={} failOpen={}",
            properties.getKeyPrefix(), properties.getTtl(), properties.getRenewInterval(),
            properties.isFailOpen());
    }

    /**
     * 续期看门狗调度器。
     *
     * @return 调度器
     */
    @Bean
    public LeaseRenewalScheduler leaseRenewalScheduler() {
        return new ScheduledLeaseRenewalScheduler();
    }

    /**
     * 分布式锁（按介质选择实现）。
     *
     * @param redisTemplateProvider Redis 模板（可能不存在）
     * @return 锁实现
     */
    @Bean
    public DistributedLock distributedLock(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        if (!properties.isEnabled()) {
            return new NoopDistributedLock();
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("[iot] 未发现 StringRedisTemplate：后台任务互斥退化为**进程内**实现，"
                + "多副本部署下跨实例互斥不生效（告警评估/保留清理等仍会在每个副本各跑一遍；单实例可用）");
            return new LeaseDistributedLock(new InMemoryLockStore(), properties, Clock.systemUTC());
        }
        return new LeaseDistributedLock(new RedisLockStore(redisTemplate), properties, Clock.systemUTC());
    }
}
