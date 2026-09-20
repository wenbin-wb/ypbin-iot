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

import java.util.Set;

/**
 * 采集链路控制端口：租约判定与「真的建链/断链」之间的执行面。
 *
 * <p>M0a/3a 只有日志实现（只维护「谁在采」的状态）；增量 3b 接上 {@code ypbin-iot-starter} 后
 * 换成真实现，判定逻辑（{@code AccessLeaseManager}）一行不用改。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface TenantLinkManager {

    /**
     * 开始采集某租户（幂等）。
     *
     * @param tenantId 租户 ID
     */
    void startCollecting(Long tenantId);

    /**
     * 断链停采某租户（幂等）。
     *
     * @param tenantId 租户 ID
     * @param reason   原因（写日志）
     */
    void fence(Long tenantId, String reason);

    /**
     * 整体断链停采（节点级失效时用）。
     *
     * @param reason 原因
     */
    void fenceAll(String reason);

    /**
     * 本节点是否负责该租户（**不代表链路一定活着**，见 docs/IOT-ROADMAP.md 增量 3 的说明）。
     *
     * @param tenantId 租户 ID
     * @return 负责返回 {@code true}
     */
    boolean isCollecting(Long tenantId);

    /** 当前负责的租户集合。 */
    Set<Long> collectingTenants();
}
