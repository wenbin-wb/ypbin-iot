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

import cn.ypbin.admin.iot.lease.config.LeaseFeignConfiguration;
import cn.ypbin.starter.core.model.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 租约内部接口的客户端（access → iot）。
 *
 * <p>契约与 {@code docs/LEASE.md} 一一对应；**没有 fallback**：租约拿不到就是不能采，
 * 静默降级会让节点在没有归属的情况下开始采集（正是 self-fencing 要防的事）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@FeignClient(name = "ypbin-iot", contextId = "leaseClient", path = "/internal/lease",
    configuration = LeaseFeignConfiguration.class)
public interface ILeaseClient {

    /**
     * 注册节点。
     *
     * @param req 注册请求
     * @return 空响应
     */
    @PostMapping("/register")
    R<Void> register(@RequestBody AccessNodeRegisterReq req);

    /**
     * 领取/续期。
     *
     * @param req 领取请求
     * @return 本节点归属清单
     */
    @PostMapping("/acquire")
    R<LeaseAcquireResp> acquire(@RequestBody LeaseAcquireReq req);

    /**
     * 续约。
     *
     * @param req 续约请求
     * @return 回执 + 回收清单 + 节点失效标记
     */
    @PostMapping("/renew")
    R<LeaseRenewResp> renew(@RequestBody LeaseRenewReq req);

    /**
     * 主动释放。
     *
     * @param req 释放请求
     * @return 空响应
     */
    @PostMapping("/release")
    R<Void> release(@RequestBody LeaseReleaseReq req);

    /**
     * 查询归属。
     *
     * @param req 查询请求
     * @return 归属；未分配时 data 为 null
     */
    @PostMapping("/assignment")
    R<LeaseAssignmentDto> queryAssignment(@RequestBody AssignmentQueryReq req);

    /**
     * 批量对账（各租户 epoch）。
     *
     * @return 各租户版本号 + 读取时刻
     */
    @GetMapping("/epochs")
    R<TenantEpochBatchResp> batchEpoch();
}
