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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台任务分布式锁配置（#220）。
 *
 * <p>默认值面向「15 秒级后台任务」：TTL 60 秒（一次任务最坏耗时的上界），
 * 续期间隔 20 秒（TTL 的 1/3，允许两次续期都失败才丢锁）。配置层自检保证
 * {@code renew-interval < ttl}，否则锁会在第一次续期之前过期。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@ConfigurationProperties(prefix = LockProperties.PREFIX)
public class LockProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.lock";

    /** 是否启用跨实例互斥（关闭 = 退回「每个副本都执行」，仅单实例部署可关）。 */
    private boolean enabled = true;

    /** 锁键前缀（所有任务的锁键都带它，便于在 Redis 里识别与控制）。 */
    private String keyPrefix = "ypbin:iot:lock:";

    /** 租约时长（持锁实例崩溃后，最坏这么久别的实例才能接管）。 */
    private Duration ttl = Duration.ofSeconds(60);

    /** 续期间隔（必须小于 TTL）。 */
    private Duration renewInterval = Duration.ofSeconds(20);

    /**
     * 存储层不可用时是否「继续执行」（fail-open）。
     *
     * <p>默认 {@code true}：这些是幂等的后台任务，Redis 抖动时宁可多跑一轮，也不该让告警评估/保留清理
     * 整体停摆。要严格「只有一个实例执行」时可设为 {@code false}（fail-closed，存储异常直接把任务抛错）。</p>
     */
    private boolean failOpen = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getRenewInterval() {
        return renewInterval;
    }

    public void setRenewInterval(Duration renewInterval) {
        this.renewInterval = renewInterval;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }
}
