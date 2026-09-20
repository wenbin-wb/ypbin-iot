/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.lease;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期续约调度（默认 10s）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Component
public class LeaseRenewScheduler {

    private final AccessLeaseManager leaseManager;

    /**
     * 构造调度器。
     *
     * @param leaseManager 租约状态机
     */
    public LeaseRenewScheduler(AccessLeaseManager leaseManager) {
        this.leaseManager = leaseManager;
    }

    /** 周期续约 + 过期自检 + 到点重领。 */
    @Scheduled(fixedDelayString = "${ypbin.access.renew-interval-ms:10000}")
    public void renew() {
        leaseManager.renewAndSelfCheck();
    }
}
