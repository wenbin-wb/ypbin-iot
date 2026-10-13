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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 锁装配与启动自检（#220）：可注入时钟/介质选择、非法配置 fail-fast。
 *
 * @author wenbin
 * @since 2026-10-13
 */
@SuppressWarnings("unchecked")
class IotLockConfigurationTest {

    @Test
    @DisplayName("启动自检：renew-interval 必须小于 ttl（否则锁在续期前就过期）")
    void rejectsRenewIntervalNotLessThanTtl() {
        LockProperties properties = new LockProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setRenewInterval(Duration.ofSeconds(30));
        assertThatThrownBy(() -> new IotLockConfiguration(properties).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("renew-interval");
    }

    @Test
    @DisplayName("启动自检：ttl/renew-interval 必须为正、key-prefix 不能为空")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> configWith(Duration.ZERO, Duration.ofSeconds(1)).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ttl");
        assertThatThrownBy(() -> configWith(Duration.ofSeconds(30), Duration.ZERO).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("renew-interval");

        LockProperties blank = new LockProperties();
        blank.setKeyPrefix("  ");
        assertThatThrownBy(() -> new IotLockConfiguration(blank).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-prefix");
    }

    private static IotLockConfiguration configWith(Duration ttl, Duration renewInterval) {
        LockProperties properties = new LockProperties();
        properties.setTtl(ttl);
        properties.setRenewInterval(renewInterval);
        return new IotLockConfiguration(properties);
    }

    @Test
    @DisplayName("关闭锁时不做参数校验（不互斥就无所谓 TTL），且装配为空锁")
    void disabledSkipsValidationAndUsesNoopLock() {
        LockProperties properties = new LockProperties();
        properties.setEnabled(false);
        properties.setTtl(Duration.ZERO);
        IotLockConfiguration configuration = new IotLockConfiguration(properties);

        configuration.afterPropertiesSet();

        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        assertThat(configuration.distributedLock(provider)).isInstanceOf(NoopDistributedLock.class);
    }

    @Test
    @DisplayName("没有 Redis ⇒ 退化为进程内锁（单实例正确；打 WARN），仍具备互斥语义")
    void fallsBackToInMemoryLockWithoutRedis() {
        LockProperties properties = new LockProperties();
        IotLockConfiguration configuration = new IotLockConfiguration(properties);
        configuration.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        DistributedLock lock = configuration.distributedLock(provider);

        assertThat(lock.tryAcquire("fallback-key")).isNotNull();
        assertThat(lock.tryAcquire("fallback-key")).as("内存锁同样互斥").isNull();
    }

    @Test
    @DisplayName("有 Redis ⇒ 装配 Redis 锁（不依赖真实连接即可装配）")
    void usesRedisLockWhenTemplatePresent() {
        LockProperties properties = new LockProperties();
        IotLockConfiguration configuration = new IotLockConfiguration(properties);
        configuration.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new StringRedisTemplate());

        assertThat(configuration.distributedLock(provider)).isNotNull();
        assertThat(configuration.leaseRenewalScheduler()).isNotNull();
    }
}
