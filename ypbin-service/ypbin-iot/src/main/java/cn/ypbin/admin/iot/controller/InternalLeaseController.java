/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.AssignmentQueryReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseProperties;
import cn.ypbin.admin.iot.lease.LeaseReleaseReq;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.service.LeaseService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租约内部端点（仅供 access 单元调用，不对外）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （凭证未配置一律拒绝）；网关不对外暴露该前缀。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@RestController
@RequestMapping("/internal/lease")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = LeaseProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class InternalLeaseController {

    private final LeaseService leaseService;

    /**
     * 注册 access 节点。
     *
     * @param req 注册请求
     * @return 空响应
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody AccessNodeRegisterReq req) {
        leaseService.register(req);
        return R.ok();
    }

    /**
     * 领取/续期。
     *
     * @param req 领取请求
     * @return 本节点归属清单
     */
    @PostMapping("/acquire")
    public R<LeaseAcquireResp> acquire(@Valid @RequestBody LeaseAcquireReq req) {
        return R.ok(leaseService.acquire(req));
    }

    /**
     * 续约。
     *
     * @param req 续约请求
     * @return 回执 + 回收清单 + 节点失效标记
     */
    @PostMapping("/renew")
    public R<LeaseRenewResp> renew(@Valid @RequestBody LeaseRenewReq req) {
        return R.ok(leaseService.renew(req));
    }

    /**
     * 主动释放。
     *
     * @param req 释放请求
     * @return 空响应
     */
    @PostMapping("/release")
    public R<Void> release(@Valid @RequestBody LeaseReleaseReq req) {
        leaseService.release(req);
        return R.ok();
    }

    /**
     * 查询归属。
     *
     * @param req 查询请求
     * @return 归属；从未分配时 data 为 null
     */
    @PostMapping("/assignment")
    public R<LeaseAssignmentDto> queryAssignment(@Valid @RequestBody AssignmentQueryReq req) {
        return R.ok(leaseService.queryAssignment(req));
    }

    /**
     * 批量对账（各租户 epoch）。
     *
     * @return 各租户版本号 + 读取时刻
     */
    @GetMapping("/epochs")
    public R<TenantEpochBatchResp> batchEpoch() {
        return R.ok(leaseService.batchEpoch());
    }
}
