/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.AssignmentQueryReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseReleaseReq;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import java.time.LocalDateTime;

/**
 * 租约与归属服务：决定「哪个节点采哪个租户」。
 *
 * <p>并发正确性靠 <b>数据库条件更新（CAS）</b> 而不是进程内锁：所有归属变更都是
 * 「带守卫条件的单条 UPDATE / 受唯一键约束的 INSERT」，因此多副本部署也只有一个赢家
 * （这正是旧独立栈 ADR-0001 里要求 M0b 补的原子性）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface LeaseService {

    /**
     * 注册 access 节点（幂等覆盖）。
     *
     * @param req 注册请求
     */
    void register(AccessNodeRegisterReq req);

    /**
     * 领取/续期：返回本节点当前应该采集的租户清单。
     *
     * @param req 领取请求
     * @return 本节点的归属清单
     */
    LeaseAcquireResp acquire(LeaseAcquireReq req);

    /**
     * 续约：成功则刷新到期时间；失败（已被回收/版本过期）则进 revoked 清单。
     *
     * @param req 续约请求
     * @return 回执 + 被回收的租户 + 节点级失效标记
     */
    LeaseRenewResp renew(LeaseRenewReq req);

    /**
     * 主动释放（节点正常下线）。
     *
     * @param req 释放请求
     */
    void release(LeaseReleaseReq req);

    /**
     * 查询单个租户的归属。
     *
     * @param req 查询请求
     * @return 归属；从未分配返回 {@code null}
     */
    LeaseAssignmentDto queryAssignment(AssignmentQueryReq req);

    /**
     * 批量对账：一次拉取所有租户的 epoch（判据只用 epoch）。
     *
     * @return 各租户版本号 + 读取时刻
     */
    TenantEpochBatchResp batchEpoch();

    /**
     * 失效扫描：把已到期且仍为 ACTIVE 的租约置为待接管（单条原子 UPDATE）。
     *
     * @return 本次置为待接管的租户数
     */
    int markExpired();
}
