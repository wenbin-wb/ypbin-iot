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

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseRenewAck;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * access 的租约状态机：注册 → 领取 → 周期续约 → **self-fencing** → 周期重领。
 *
 * <p>四条硬要求（spec §3.1①）：</p>
 * <ol>
 *   <li><b>启动握手 fail-fast</b>：注册/领取任何非成功信封或传输异常都让应用启动失败——
 *       节点绝不在「没有归属」的状态下开始采集；</li>
 *   <li><b>周期续约</b>：拿到回执就刷新本地快照（含服务端 epoch）；</li>
 *   <li><b>self-fencing</b>：{@code revokedTenantIds} 逐租户停采、{@code nodeFenced} 整体停采后
 *       重新注册并重新领取、**本地过期自检**（续约失败/超时后，到期就自己停采，不等业务侧）；</li>
 *   <li><b>周期重领</b>：只在启动时领取会让「待接管」的租户永远无人接手，到点必须再领。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public class AccessLeaseManager {

    private static final Logger log = LoggerFactory.getLogger(AccessLeaseManager.class);

    /** 指标前缀。 */
    static final String METRIC_PREFIX = "iot.access.lease.";

    private final ILeaseClient leaseClient;
    private final TenantLinkManager linkManager;
    private final AccessProperties properties;
    private final Map<Long, LeaseSnapshot> holdings = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDateTime> lastAcquireAt = new AtomicReference<>();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final Counter renewSuccess;
    private final Counter renewFailure;
    private final Counter revokedCounter;
    private final Counter selfFencedCounter;
    private final Counter nodeFencedCounter;
    private final Counter acquiredCounter;

    /**
     * 构造状态机。
     *
     * @param leaseClient  租约客户端
     * @param linkManager  链路控制端口
     * @param properties   节点参数
     * @param meterRegistry 指标注册表
     */
    public AccessLeaseManager(ILeaseClient leaseClient, TenantLinkManager linkManager,
            AccessProperties properties, MeterRegistry meterRegistry) {
        this.leaseClient = leaseClient;
        this.linkManager = linkManager;
        this.properties = properties;
        this.renewSuccess = Counter.builder(METRIC_PREFIX + "renew.success").register(meterRegistry);
        this.renewFailure = Counter.builder(METRIC_PREFIX + "renew.failure").register(meterRegistry);
        this.revokedCounter = Counter.builder(METRIC_PREFIX + "revoked").register(meterRegistry);
        this.selfFencedCounter = Counter.builder(METRIC_PREFIX + "self_fenced").register(meterRegistry);
        this.nodeFencedCounter = Counter.builder(METRIC_PREFIX + "node_fenced").register(meterRegistry);
        this.acquiredCounter = Counter.builder(METRIC_PREFIX + "acquired").register(meterRegistry);
    }

    /** 启动握手（fail-fast）：注册 + 领取。 */
    public void start() {
        registerOrFail();
        // 必须在领取之前记录：否则调度器若在这一瞬抢跑，会把「还没握手」误判为「到点重领」
        lastAcquireAt.set(LocalDateTime.now());
        acquireOrFail();
        lastAcquireAt.set(LocalDateTime.now());
    }

    /** 对外入口（调度器用）。 */
    public void renewAndSelfCheck() {
        renewAndSelfCheck(LocalDateTime.now());
    }

    /**
     * 带时间注入的实现（测试用它模拟过期，不必真的等）。
     *
     * @param now 当前时刻
     */
    void renewAndSelfCheck(LocalDateTime now) {
        selfFenceExpiredLocally(now);
        if (holdings.isEmpty()) {
            log.debug("本地没有持有租户，跳过续约");
        } else {
            renew(now);
        }
        refreshAssignmentsIfDue(now);
    }

    /**
     * 本地过期自检：到期就停采（不依赖业务侧）。
     *
     * @param now 当前时刻
     */
    private void selfFenceExpiredLocally(LocalDateTime now) {
        List<Long> expired = holdings.entrySet().stream()
            .filter(entry -> entry.getValue().mustSelfFence(now))
            .map(Map.Entry::getKey)
            .toList();
        for (Long tenantId : expired) {
            holdings.remove(tenantId);
            linkManager.fence(tenantId, "本地租约已过期（未成功续约）");
            selfFencedCounter.increment();
            log.warn("本地租约过期，自行停采：tenantId={}", LogSanitizer.sanitize(tenantId));
        }
    }

    /**
     * 周期续约：一次批量续约，按回执刷新快照、按回收清单停采。
     *
     * @param now 当前时刻
     */
    private void renew(LocalDateTime now) {
        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode(properties.getNodeId());
        List<LeaseRenewItem> items = new ArrayList<>();
        for (Map.Entry<Long, LeaseSnapshot> entry : holdings.entrySet()) {
            LeaseRenewItem item = new LeaseRenewItem();
            item.setTenantId(entry.getKey());
            item.setEpoch(entry.getValue().epoch());
            items.add(item);
        }
        req.setLeases(items);
        R<LeaseRenewResp> resp;
        try {
            resp = leaseClient.renew(req);
        } catch (RuntimeException ex) {
            renewFailure.increment();
            log.error("续约失败（本地到期时间不会延长，到期后将自行停采）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
            return;
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            renewFailure.increment();
            log.error("续约返回非成功信封：node={} code={}", LogSanitizer.sanitize(properties.getNodeId()),
                resp == null ? "null" : resp.getCode());
            return;
        }
        renewSuccess.increment();
        applyRenewResponse(resp.getData(), now);
    }

    /**
     * 落地续约回执。
     *
     * @param resp 回执
     * @param now  当前时刻
     */
    private void applyRenewResponse(LeaseRenewResp resp, LocalDateTime now) {
        if (resp.isNodeFenced()) {
            // 优先处理并返回：否则会先对「已被判定失效」的节点 startCollecting（3b 会真的建链），
            // 再被 fenceAll 拆掉 —— 白建一次链
            handleNodeFenced(now);
            return;
        }
        for (LeaseRenewAck ack : resp.getRenewedLeases()) {
            holdings.put(ack.getTenantId(), new LeaseSnapshot(ack.getLeaseExpireAt(), ack.getEpoch()));
        }
        for (Long tenantId : resp.getRevokedTenantIds()) {
            // 无条件停采 + 计数：fence 是幂等的；3b 换真实现后本地快照可能已无该租户，
            // 但「服务端要求停采」这件事必须执行（否则会漏停采）
            holdings.remove(tenantId);
            revokedCounter.increment();
            linkManager.fence(tenantId, "business 判定该租户已失效/被接管");
            log.warn("租户被回收，断链停采：tenantId={}", LogSanitizer.sanitize(tenantId));
        }
    }

    /**
     * 节点级失效：整体停采 → 重新注册并重新领取；失败则保持停采并把 registered 置回 false。
     *
     * @param now 当前时刻
     */
    private void handleNodeFenced(LocalDateTime now) {
        nodeFencedCounter.increment();
        log.error("business 判定本节点已失效（nodeFenced）：整体停采并重新注册：node={}",
            LogSanitizer.sanitize(properties.getNodeId()));
        linkManager.fenceAll("business 判定节点失效");
        holdings.clear();
        lastAcquireAt.set(now);
        try {
            registerOrFail();
            acquireOrFail();
        } catch (RuntimeException ex) {
            registered.set(false);
            log.error("重新注册/领取失败，节点保持停采（下一轮补注册后再试）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
        }
    }

    /**
     * 周期重领（接管的执行入口）。
     *
     * @param now 当前时刻
     */
    private void refreshAssignmentsIfDue(LocalDateTime now) {
        if (!registered.get()) {
            if (lastAcquireAt.get() == null) {
                // 握手还没开始（start() 还没跑到预置那行）⇒ **绝不抢跑**：
                // 旧栈复核实测过调度器会在这一瞬完成 register+acquire，令「注册→领取→采集」的顺序变成偶然。
                return;
            }
            // nodeFenced 恢复失败时 registered 会停在 false，而服务端的 acquire 拒绝未注册节点
            // ⇒ 不在这里补注册，节点会永久零采集（旧实现只记错误，活性缺陷）。register 是幂等覆盖。
            try {
                registerOrFail();
            } catch (RuntimeException ex) {
                log.error("重领前补注册失败（节点仍处停采状态）：node={}",
                    LogSanitizer.sanitize(properties.getNodeId()), ex);
                return;
            }
        }
        LocalDateTime last = lastAcquireAt.get();
        if (last != null && Duration.between(last, now).toMillis() < properties.getAcquireIntervalMs()) {
            return;
        }
        lastAcquireAt.set(now);
        R<LeaseAcquireResp> resp;
        try {
            resp = leaseClient.acquire(acquireRequest());
        } catch (RuntimeException ex) {
            log.error("周期重领失败（已在采的租户不受影响）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
            return;
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            // 服务端可能已经重启、丢了进程内的节点注册（acquire 显式拒绝未注册节点）⇒ 置回未注册，
            // 下一轮先补注册。否则「0 租户节点 + 无 renew」会永远拿不到 nodeFenced ⇒ 永久零采集。
            registered.set(false);
            log.error("周期重领返回非成功信封：node={} code={}（已置回未注册，下一轮补注册）",
                LogSanitizer.sanitize(properties.getNodeId()), resp == null ? "null" : resp.getCode());
            return;
        }
        int gained = applyAcquireResponse(resp.getData());
        if (gained > 0) {
            acquiredCounter.increment(gained);
            log.warn("周期重领到租户（接管/新分配）：node={} 新增={}",
                LogSanitizer.sanitize(properties.getNodeId()), gained);
        }
    }

    /** 注册（失败即启动失败）。 */
    private void registerOrFail() {
        AccessNodeRegisterReq req = new AccessNodeRegisterReq();
        req.setAccessNode(properties.getNodeId());
        req.setMaxTenants(properties.getCapacity());
        R<Void> resp;
        try {
            resp = leaseClient.register(req);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("access 启动失败：无法完成节点注册（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + "）", ex);
        }
        if (resp == null || resp.getCode() != 200) {
            throw new IllegalStateException("access 启动失败：注册节点未成功（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + ", code="
                + (resp == null ? "null" : resp.getCode()) + "）");
        }
        registered.set(true);
        log.info("节点注册成功：node={} capacity={}", LogSanitizer.sanitize(properties.getNodeId()),
            properties.getCapacity() == null ? "不限" : properties.getCapacity());
    }

    /** 领取（失败即启动失败）。 */
    private void acquireOrFail() {
        R<LeaseAcquireResp> resp;
        try {
            resp = leaseClient.acquire(acquireRequest());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("access 启动失败：无法领取租约（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + "）", ex);
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            throw new IllegalStateException("access 启动失败：领取租约未成功（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + ", code="
                + (resp == null ? "null" : resp.getCode()) + "）");
        }
        applyAcquireResponse(resp.getData());
        log.info("租户领取完成：node={} 持有租户={}", LogSanitizer.sanitize(properties.getNodeId()),
            LogSanitizer.sanitize(holdings.keySet()));
    }

    /**
     * 落地领取响应：写入快照并开始采集。
     *
     * @param data 领取响应
     * @return 本次新增（此前未持有）的租户数
     */
    private int applyAcquireResponse(LeaseAcquireResp data) {
        int gained = 0;
        for (LeaseAssignmentDto assignment : data.getAssignments()) {
            boolean isNew = !holdings.containsKey(assignment.getTenantId());
            holdings.put(assignment.getTenantId(),
                new LeaseSnapshot(assignment.getLeaseExpireAt(), assignment.getEpoch()));
            linkManager.startCollecting(assignment.getTenantId());
            if (isNew) {
                gained++;
            }
        }
        return gained;
    }

    /** 领取请求（启动领取与周期重领共用）。 */
    private LeaseAcquireReq acquireRequest() {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(properties.getNodeId());
        return req;
    }

    /** 当前持有的租户（测试/观测用）。 */
    public Set<Long> heldTenants() {
        return Set.copyOf(holdings.keySet());
    }
}
