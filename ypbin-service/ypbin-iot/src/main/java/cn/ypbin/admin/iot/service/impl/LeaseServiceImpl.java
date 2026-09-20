/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.AccessNodeRegistry;
import cn.ypbin.admin.iot.lease.AssignmentQueryReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseEpochRules;
import cn.ypbin.admin.iot.lease.LeaseProperties;
import cn.ypbin.admin.iot.lease.LeaseReleaseReq;
import cn.ypbin.admin.iot.lease.LeaseRenewAck;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.LeaseState;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.admin.iot.service.LeaseService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.locks.Lock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 租约与归属服务实现。
 *
 * <p><b>并发模型</b>：<b>归属裁决</b>不使用进程内锁，全部依赖数据库的条件更新（CAS）与唯一键——
 * 每个「归属变更」都是一条带守卫条件的 UPDATE，{@code affectedRows == 1} 才算赢；
 * 新分配的 INSERT 靠 {@code uk_tenant_node_assignment_tenant} 唯一键兜底，
 * 冲突即视为「别人先到」（不重试、不覆盖）。这样多副本部署也不会出现双主。
 * <b>容量计数</b>另用「每节点进程内锁」（仅同一 JVM 有效，见 {@link #acquire}）；
 * 数据库级原子容量属 M0b（见 docs/LEASE.md §5 的 1b）。</p>
 *
 * <p><b>续约语义</b>：一次批量 UPDATE + 一次批量查询，不做逐条 CAS（那是 N+1）。
 * 请求里带的本地 epoch <b>不再用于拒绝续约</b>：只要归属仍在本节点名下就续期，并把<b>服务端 epoch</b>
 * 放进回执——节点据此自更新（旧设计把「本地 epoch 落后」当吊销，会让一次视图滞后换来整租户断链，
 * 抖动大且没必要）。真正需要停采的是「归属已不在本节点名下」，那一条仍然回收。</p>
 *
 * <p><b>epoch 语义（本增量定死）</b>：归属<b>每次变更</b>都推进 epoch——
 * 新分配 = {@link LeaseEpochRules#INITIAL_EPOCH}，接管/释放后重新分配 = 当前值 + 1。
 * 这解决了旧设计里「释放→再分配不涨 epoch 会让旧快照被采纳」的悬空问题
 * （旧仓 ADR-0001 把它列为待决项）：只要归属变过，旧快照的 epoch 一定更小，无法被采纳。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Service
public class LeaseServiceImpl implements LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseServiceImpl.class);

    /** 指标前缀。 */
    static final String METRIC_PREFIX = "iot.lease.";

    private final TenantNodeAssignmentMapper mapper;
    private final AccessNodeRegistry nodeRegistry;
    private final LeaseProperties properties;
    private final Counter takeoverCounter;
    private final Counter expiredCounter;
    private final Counter revokedCounter;
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造租约服务。
     *
     * @param mapper              归属 Mapper
     * @param nodeRegistry        节点注册表
     * @param properties          租约参数
     * @param meterRegistry       指标注册表
     * @param transactionTemplate 事务模板（领取需要在「锁内、事务中」执行，见 {@link #acquire}）
     */
    public LeaseServiceImpl(TenantNodeAssignmentMapper mapper, AccessNodeRegistry nodeRegistry,
            LeaseProperties properties, MeterRegistry meterRegistry,
            TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.mapper = mapper;
        this.nodeRegistry = nodeRegistry;
        this.properties = properties;
        this.takeoverCounter = Counter.builder(METRIC_PREFIX + "takeover")
            .description("接管（含释放后重新分配）成功的租户数").register(meterRegistry);
        this.expiredCounter = Counter.builder(METRIC_PREFIX + "expired")
            .description("失效扫描置为待接管的租户数").register(meterRegistry);
        this.revokedCounter = Counter.builder(METRIC_PREFIX + "revoked")
            .description("续约时被回收（服务端已不认为属于该节点）的租户数").register(meterRegistry);
    }

    @Override
    public void register(AccessNodeRegisterReq req) {
        nodeRegistry.register(req.getAccessNode(), req.getMaxTenants());
        log.info("[iot] access 节点注册：node={} capacity={}", LogSanitizer.sanitize(req.getAccessNode()),
            req.getMaxTenants() == null ? "不限" : req.getMaxTenants());
    }

    @Override
    public LeaseAcquireResp acquire(LeaseAcquireReq req) {
        String node = req.getAccessNode();
        if (!nodeRegistry.isRegistered(node)) {
            // 不隐式注册：未注册节点拿到归属会让「谁在线」不可审计
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "节点未注册，请先调用 register：" + node);
        }
        // 容量是「先读后写」：同一进程内并发领取同一节点必须串行。
        // ⚠️ 锁必须在**事务之外**：若把锁放在 @Transactional 方法内，方法返回即解锁、而事务提交发生在
        // 代理边界之后 ⇒ 第二个线程会在第一个线程提交前读到旧计数，锁等于没生效。这里用事务模板把
        // 「计数 + 限量分配」整体放进锁内、并保证提交先于解锁。
        Lock lock = nodeRegistry.lockFor(node);
        lock.lock();
        try {
            return transactionTemplate.execute(status -> doAcquire(req));
        } finally {
            lock.unlock();
        }
    }

    /** 领取的实际逻辑（在节点锁内、同一事务中执行）。 */
    private LeaseAcquireResp doAcquire(LeaseAcquireReq req) {
        String node = req.getAccessNode();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plus(properties.getTtl());

        // ① 续期自己在采的（单条原子 UPDATE）。**不读 affectedRows**：真实持有的租户以 ② 的批量查询为准
        //    （affectedRows 只说明匹配了多少行，区分不了状态，误用会让 ack/回收清单失真）
        mapper.update(null, Wrappers.<TenantNodeAssignment>lambdaUpdate()
            .eq(TenantNodeAssignment::getAccessNode, node)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .set(TenantNodeAssignment::getLeaseExpireAt, expireAt)
            .set(TenantNodeAssignment::getUpdateTime, now));

        int capacity = capacityOf(node);
        // 在锁内重新计数（不依赖 renewed）：容量判断与「限量分配」必须基于同一时刻的事实
        int held = countHeld(node);
        int room = capacity == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, capacity - held);

        // ② 接管：待接管 / 已释放 / 已过期但仍标 ACTIVE 的（扫描滞后时兜底）。
        // 一条原子 UPDATE 完成，并用 LIMIT 把容量上限压在同一条语句里——
        // 逐行 CAS 会变成 N+1（架构门禁会拦），而且多副本下每行都要重新读一次状态。
        if (room > 0) {
            // ⚠️ 这里**不能**加 `access_node <> 本节点` 的守卫：节点必须能重领自己名下
            // 那些已变成 pending_takeover / released 的租户，否则单节点部署下 ttl 一过就永久丢采集
            // （markExpired 只有「置为待接管」这一条路，没有回退路径）。
            // 安全性不受影响：候选条件本身要求 state≠active 或「active 且已过期」，
            // 而本节点自己的 active 行已在步骤①被续期到 now+ttl，因此不会误抢本节点在采的租户
            // （更不会抢别的节点在采的租户）。
            int takenOver = mapper.update(null, Wrappers.<TenantNodeAssignment>lambdaUpdate()
                .and(w -> w.eq(TenantNodeAssignment::getState, LeaseState.PENDING_TAKEOVER.getCode())
                    .or().eq(TenantNodeAssignment::getState, LeaseState.RELEASED.getCode())
                    .or(x -> x.eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
                        .le(TenantNodeAssignment::getLeaseExpireAt, now)))
                .setSql("epoch = epoch + 1")
                .set(TenantNodeAssignment::getAccessNode, node)
                .set(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
                .set(TenantNodeAssignment::getLeaseExpireAt, expireAt)
                .set(TenantNodeAssignment::getUpdateTime, now)
                .last("LIMIT " + room));
            if (takenOver > 0) {
                takeoverCounter.increment(takenOver);
                log.info("[iot] 租户接管完成：node={} 接管数={}", LogSanitizer.sanitize(node), takenOver);
            }
        }

        // ③ 新分配：assignable 里还没有归属行的租户，一次批量插入（唯一键冲突的跳过）
        int remaining = capacity == Integer.MAX_VALUE
            ? Integer.MAX_VALUE : Math.max(0, capacity - countHeld(node));
        if (remaining > 0) {
            List<TenantNodeAssignment> fresh = newTenantsWithoutAssignment(remaining, node, expireAt);
            if (!fresh.isEmpty()) {
                int inserted = mapper.insertIgnoringDuplicates(fresh);
                if (inserted > 0) {
                    takeoverCounter.increment(inserted);
                }
            }
        }

        LeaseAcquireResp resp = new LeaseAcquireResp();
        resp.setAccessNode(node);
        resp.setAssignments(listAssignmentsOf(node));
        log.info("[iot] 节点领取完成：node={} 持有租户={}", LogSanitizer.sanitize(node),
            LogSanitizer.sanitize(resp.getAssignments().stream().map(LeaseAssignmentDto::getTenantId).toList()));
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeaseRenewResp renew(LeaseRenewReq req) {
        String node = req.getAccessNode();
        LeaseRenewResp resp = new LeaseRenewResp();
        if (!nodeRegistry.isRegistered(node)) {
            // 节点级失效：节点必须整体停采并重新注册（契约里的 nodeFenced）
            resp.setNodeFenced(true);
            log.warn("[iot] 续约来自未注册节点，判定节点失效：node={}", LogSanitizer.sanitize(node));
            return resp;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plus(properties.getTtl());
        List<Long> requested = new ArrayList<>();
        for (LeaseRenewItem item : req.getLeases()) {
            requested.add(item.getTenantId());
        }

        // ① 一次批量续期（不在循环里逐条 UPDATE：那是 N+1）
        mapper.update(null, Wrappers.<TenantNodeAssignment>lambdaUpdate()
            .eq(TenantNodeAssignment::getAccessNode, node)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .in(TenantNodeAssignment::getTenantId, requested)
            .set(TenantNodeAssignment::getLeaseExpireAt, expireAt)
            .set(TenantNodeAssignment::getUpdateTime, now));

        // ② 一次批量查询：实际仍属于本节点的（顺带拿到服务端 epoch）
        List<TenantNodeAssignment> renewed = mapper.selectList(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .eq(TenantNodeAssignment::getAccessNode, node)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .in(TenantNodeAssignment::getTenantId, requested));
        List<LeaseRenewAck> acks = new ArrayList<>();
        Set<Long> renewedIds = new LinkedHashSet<>();
        for (TenantNodeAssignment assignment : renewed) {
            LeaseRenewAck ack = new LeaseRenewAck();
            ack.setTenantId(assignment.getTenantId());
            ack.setLeaseExpireAt(expireAt);
            // 回执带**服务端**的 epoch：节点本地视图落后时据此自更新（比直接吊销更少抖动，也更快收敛）
            ack.setEpoch(assignment.getEpoch());
            acks.add(ack);
            renewedIds.add(assignment.getTenantId());
        }

        // ③ 请求了但不再属于本节点的 ⇒ 回收（节点必须立即断链停采）
        List<Long> revoked = new ArrayList<>();
        for (Long tenantId : requested) {
            if (!renewedIds.contains(tenantId)) {
                revoked.add(tenantId);
            }
        }
        if (!revoked.isEmpty()) {
            revokedCounter.increment(revoked.size());
            log.warn("[iot] 续约回收租户：node={} tenantIds={}", LogSanitizer.sanitize(node),
                LogSanitizer.sanitize(revoked));
        }
        resp.setRenewedLeases(acks);
        resp.setRevokedTenantIds(revoked);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(LeaseReleaseReq req) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, Wrappers.<TenantNodeAssignment>lambdaUpdate()
            .eq(TenantNodeAssignment::getAccessNode, req.getAccessNode())
            .in(TenantNodeAssignment::getTenantId, req.getTenantIds())
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .set(TenantNodeAssignment::getState, LeaseState.RELEASED.getCode())
            .set(TenantNodeAssignment::getLeaseExpireAt, now)
            .set(TenantNodeAssignment::getUpdateTime, now));
        log.info("[iot] 节点释放租户：node={} 请求={} 实际释放={}", LogSanitizer.sanitize(req.getAccessNode()),
            req.getTenantIds().size(), rows);
    }

    @Override
    public LeaseAssignmentDto queryAssignment(AssignmentQueryReq req) {
        TenantNodeAssignment assignment = mapper.selectOne(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .eq(TenantNodeAssignment::getTenantId, req.getTenantId()));
        return toDto(assignment);
    }

    @Override
    public TenantEpochBatchResp batchEpoch() {
        List<TenantNodeAssignment> all = mapper.selectList(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .select(TenantNodeAssignment::getTenantId, TenantNodeAssignment::getEpoch));
        List<TenantEpochItem> items = new ArrayList<>();
        for (TenantNodeAssignment assignment : all) {
            TenantEpochItem item = new TenantEpochItem();
            item.setTenantId(assignment.getTenantId());
            item.setEpoch(assignment.getEpoch());
            items.add(item);
        }
        TenantEpochBatchResp resp = new TenantEpochBatchResp();
        resp.setItems(items);
        resp.setReadAt(LocalDateTime.now());
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markExpired(LocalDateTime now) {
        int rows = mapper.update(null, Wrappers.<TenantNodeAssignment>lambdaUpdate()
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .le(TenantNodeAssignment::getLeaseExpireAt, now)
            .set(TenantNodeAssignment::getState, LeaseState.PENDING_TAKEOVER.getCode())
            .set(TenantNodeAssignment::getUpdateTime, now));
        if (rows > 0) {
            expiredCounter.increment(rows);
            log.warn("[iot] 失效扫描：{} 个租约已到期，置为待接管", rows);
        }
        return rows;
    }

    /**
     * 构造「首次分配」的实体清单（一次批量插入）。
     *
     * <p>id 用 MyBatis-Plus 的 {@code IdWorker} 预生成：批量语句绕过了 Mapper 的字段填充，
     * 不显式给 id 会写入 0 并在第二行冲突。</p>
     *
     * @param limit    本次最多分配几个（容量剩余）
     * @param node     节点
     * @param expireAt 到期时间
     * @return 待插入清单（可能为空）
     */
    private List<TenantNodeAssignment> newTenantsWithoutAssignment(int limit, String node,
            LocalDateTime expireAt) {
        List<Long> missing = missingTenants();
        List<TenantNodeAssignment> fresh = new ArrayList<>();
        for (Long tenantId : missing) {
            if (fresh.size() >= limit) {
                break;
            }
            TenantNodeAssignment assignment = new TenantNodeAssignment();
            assignment.setId(IdWorker.getId());
            assignment.setTenantId(tenantId);
            assignment.setAccessNode(node);
            assignment.setEpoch(LeaseEpochRules.INITIAL_EPOCH);
            assignment.setState(LeaseState.ACTIVE.getCode());
            assignment.setLeaseExpireAt(expireAt);
            fresh.add(assignment);
        }
        return fresh;
    }

    /** 节点当前持有的租户数。 */
    private int countHeld(String node) {
        Long count = mapper.selectCount(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .eq(TenantNodeAssignment::getAccessNode, node)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode()));
        return count == null ? 0 : count.intValue();
    }

    /** 分配容量（注册表已把「不限」映射成哨兵值）。 */
    private int capacityOf(String node) {
        return nodeRegistry.capacityOf(node);
    }

    /** 本节点当前的归属清单（按租户排序，便于对账）。 */
    private List<LeaseAssignmentDto> listAssignmentsOf(String node) {
        List<TenantNodeAssignment> list = mapper.selectList(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .eq(TenantNodeAssignment::getAccessNode, node)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode())
            .orderByAsc(TenantNodeAssignment::getTenantId));
        List<LeaseAssignmentDto> result = new ArrayList<>();
        for (TenantNodeAssignment assignment : list) {
            result.add(toDto(assignment));
        }
        return result;
    }

    /** 可分配但尚无归属行的租户（一次 IN 查询；入参为空时短路返回空集合）。 */
    private List<Long> missingTenants() {
        List<Long> assignable = properties.getAssignableTenantIds();
        if (assignable == null || assignable.isEmpty()) {
            return List.of();
        }
        List<TenantNodeAssignment> existing = mapper.selectList(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .select(TenantNodeAssignment::getTenantId)
            .in(TenantNodeAssignment::getTenantId, assignable));
        Set<Long> assigned = new LinkedHashSet<>();
        for (TenantNodeAssignment assignment : existing) {
            assigned.add(assignment.getTenantId());
        }
        List<Long> missing = new ArrayList<>();
        for (Long tenantId : assignable) {
            if (!assigned.contains(tenantId)) {
                missing.add(tenantId);
            }
        }
        return missing;
    }

    /** 实体 → 契约模型。 */
    private LeaseAssignmentDto toDto(TenantNodeAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(assignment.getTenantId());
        dto.setAccessNode(assignment.getAccessNode());
        dto.setLeaseExpireAt(assignment.getLeaseExpireAt());
        dto.setEpoch(assignment.getEpoch());
        dto.setState(LeaseState.ofCode(assignment.getState()));
        return dto;
    }
}
