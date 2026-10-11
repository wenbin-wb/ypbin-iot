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

import java.time.LocalDateTime;

/**
 * 接入节点概览行（平台运维页用：这个节点还活着吗、正在采几个租户、容量多大）。
 *
 * @param accessNode       节点标识
 * @param maxTenants       容量上限（`null` = 不限）
 * @param holdingCount     当前持有（`active`）租户数
 * @param lastHeartbeatAt  最近一次心跳（续约）时刻
 * @param leaseExpireMin   该节点最早到期时刻（用来判断「下一个接管点」）
 * @author wenbin
 * @since 2026-10-11
 */
public record AccessNodeOverviewResp(
    String accessNode,
    Integer maxTenants,
    Long holdingCount,
    LocalDateTime lastHeartbeatAt,
    LocalDateTime leaseExpireMin) {
}
