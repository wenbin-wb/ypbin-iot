/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.startup;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.access.lease.AccessLeaseManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动握手：调用状态机的 {@code start()} 并让异常冒出去（应用启动失败）。
 *
 * <p>可被 {@code ypbin.access.startup-handshake-enabled=false} 关闭——只用于「验装配」的测试；
 * 关掉时节点零采集（安全方向），生产必须开启。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Component
@ConditionalOnProperty(prefix = AccessProperties.PREFIX, name = "startup-handshake-enabled",
    havingValue = "true", matchIfMissing = true)
public class AccessStartupRunner implements ApplicationRunner {

    private final AccessLeaseManager leaseManager;

    /**
     * 构造 runner。
     *
     * @param leaseManager 租约状态机
     */
    public AccessStartupRunner(AccessLeaseManager leaseManager) {
        this.leaseManager = leaseManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        leaseManager.start();
    }
}
