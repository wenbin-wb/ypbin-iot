/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 链路控制的 3a 实现：只维护「谁在采」的状态并打日志。
 *
 * <p>它<b>不代表</b>协议栈已经接上：真实建链/断链在增量 3b（接 ypbin-iot-starter）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public class LoggingTenantLinkManager implements TenantLinkManager {

    private static final Logger log = LoggerFactory.getLogger(LoggingTenantLinkManager.class);

    private final Set<Long> collecting = ConcurrentHashMap.newKeySet();

    @Override
    public void startCollecting(Long tenantId) {
        if (collecting.add(tenantId)) {
            log.info("开始采集租户：tenantId={}（3a 仅状态标记，协议栈在增量 3b）",
                LogSanitizer.sanitize(tenantId));
        }
    }

    @Override
    public void fence(Long tenantId, String reason) {
        if (collecting.remove(tenantId)) {
            log.warn("租户断链停采：tenantId={} reason={}", LogSanitizer.sanitize(tenantId),
                LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public void fenceAll(String reason) {
        int size = collecting.size();
        collecting.clear();
        if (size > 0) {
            log.warn("整体断链停采：租户数={} reason={}", size, LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public boolean isCollecting(Long tenantId) {
        return collecting.contains(tenantId);
    }

    @Override
    public Set<Long> collectingTenants() {
        return Set.copyOf(collecting);
    }
}
