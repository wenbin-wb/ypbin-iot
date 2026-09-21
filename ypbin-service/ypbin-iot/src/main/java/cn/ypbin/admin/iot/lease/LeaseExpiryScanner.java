/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import cn.ypbin.admin.iot.service.LeaseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 租约失效扫描：把「已到期但仍是 ACTIVE」的租约置为待接管。
 *
 * <p>没有它，「节点退出 → 待接管」永远不会发生，租户会静默离线——这是本平台最不能省的定时任务。
 * 实现是<b>一条原子 UPDATE</b>（不读-改-写），多副本同时跑也不会互相覆盖。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
@ConditionalOnProperty(prefix = LeaseProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class LeaseExpiryScanner {

    private final LeaseService leaseService;

    /**
     * 构造扫描器。
     *
     * @param leaseService 租约服务
     */
    public LeaseExpiryScanner(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 周期扫描（间隔由 {@code ypbin.lease.scan-interval-ms} 控制）。 */
    @Scheduled(fixedDelayString = "${ypbin.lease.scan-interval-ms:15000}")
    public void scan() {
        leaseService.markExpired();
    }
}
