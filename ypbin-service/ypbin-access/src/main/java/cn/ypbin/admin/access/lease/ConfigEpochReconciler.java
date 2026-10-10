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

import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.LeaseEpochRules;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置版本号对账（M-2：落地 {@code config_epoch} 的消费方，同时修 G7）。
 *
 * <p><b>解决什么问题</b>：接入侧此前只在「首次开始采集」时拉一次设备清单，之后无论上游怎么改
 * （新增设备、改点位映射、停用/删除设备）都不会再拉——租约持续续约时连 fence 都不会发生，
 * 于是变更永远不被发现，只能靠链路重建。这里把业务侧台账的 {@code config_epoch}
 * 变成变更信号：<b>版本号不一致才拉全量</b>，一致时一个字段都不多取。</p>
 *
 * <p><b>为什么「首次观测」也要对账一次</b>：本进程第一次看到某个租户时并无本地版本号可比。
 * 若此时只记录版本号而不对账，就会出现「采集时刻早于版本号读取时刻」的竞态：
 * 变更发生在「startCollecting 拉完清单」与「第一次读到版本号」之间时，本地会记下新版本号
 * 却拿着旧清单，该次变更被永久吞掉（静默零数据）。多拉一次是有界的（每租户每进程一次），
 * 漏一次变更却是永久性的。</p>
 *
 * <p><b>版本号只在「对账已完成」时才推进</b>：{@link TenantLinkManager#reconcile(Long)} 返回
 * {@code false}（取数失败、空清单退避、不在采集）时不推进 ⇒ 下一轮重试。
 * 反之若先推进再对账，一次失败就会永久吞掉一次变更。</p>
 *
 * <p><b>周期安全网（信号链路可能整体缺失）</b>：单租户部署（未开租户插件）或台账没有该租户时，
 * 业务侧**无法**推进 {@code config_epoch} ⇒ 版本号恒不变，纯信号驱动会退化成「永不重取」。
 * 因此对「本轮没有被版本号驱动的对账覆盖到」的租户，超过
 * {@code ypbin.access.config-refresh-interval-ms}（默认 5 分钟）未尝试过对账就强制对账一次；
 * <b>每轮最多强制 1 个租户</b>，把额外远端调用限流为「每 tick 至多一次」，避免把调度 tick 拖长。</p>
 *
 * <p>线程模型：与租约状态机同线程调用（{@code LeaseRenewScheduler} 单线程），
 * 内部状态用并发容器以便将来被其它线程读取。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class ConfigEpochReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConfigEpochReconciler.class);

    /** 指标前缀（与 {@link AccessLeaseManager} 及订阅指标统一为 {@code iot.access.*}）。 */
    static final String METRIC_PREFIX = "iot.access.config.";

    private final ILeaseClient leaseClient;

    /** 本节点标识：epoch 对账必须按节点过滤（服务端拒空，不带就等于拉全平台）。 */
    private final String nodeId;

    private final TenantLinkManager linkManager;
    private final Clock clock;

    /** 周期安全网间隔（毫秒）；{@code <= 0} 表示关闭。 */
    private final long refreshIntervalMs;

    /** 单轮对账的时间预算（毫秒）；{@code <= 0} 表示不限制（R8-2）。 */
    private final long reconcileBudgetMs;

    /** 已按之完成对账的配置版本号：tenantId → configEpoch。 */
    private final Map<Long, Long> appliedConfigEpochs = new ConcurrentHashMap<>();

    /** 已计过一次「未完成对账」的版本号：tenantId → configEpoch（防指标被重试放大）。 */
    private final Map<Long, Long> countedNotAppliedEpochs = new ConcurrentHashMap<>();

    /** 最近一次**尝试**对账的时刻（无论成败）：周期安全网的判据。 */
    private final Map<Long, Instant> lastAttemptAt = new ConcurrentHashMap<>();

    /** 版本号变化的次数（观测「真的有多少次配置变更」）。 */
    private final Counter changedCounter;

    /** 对账请求（{@code /internal/lease/epochs}）失败次数。 */
    private final Counter checkFailureCounter;

    /** 版本号已变化但**未能完成**对账的次数（同一版本号只计一次；持续增长说明取数一直在失败）。 */
    private final Counter notAppliedCounter;

    /** 周期安全网实际触发的强制对账次数。 */
    private final Counter forcedCounter;

    /**
     * 因**时间预算用尽**而被推迟到下一 tick 的租户次数（R8-2）。
     *
     * <p>它应该与 {@code iot.access.config.reconcile.duration} 一起看：duration 的 {@code max} 顶到预算
     * 且本计数持续增长 ⇒ 说明「本轮确实做不完」，要么调大预算、要么按 R8-2 的其它选项（有界并发/收敛安全网）。</p>
     */
    private final Counter deferredCounter;

    /**
     * 一次对账（拉 epoch + 逐租户按需重取设备清单）的**耗时**。
     *
     * <p>R8-2 的可观测性前置：本阶段是 tick 里唯一会串行打远端（每租户 6s 最坏）的部分，
     * 它的耗时分布是「要不要给对账加时间预算/并发上限」的唯一依据。</p>
     */
    private final Timer reconcileTimer;

    /**
     * 构造对账器。
     *
     * @param leaseClient      租约客户端（提供按节点过滤 + 分页的 epoch 对账接口）
     * @param nodeId           本节点标识（epoch 对账的过滤键；必填，空由服务端拒绝）
     * @param linkManager      链路控制端口（提供按最新配置对账设备清单的能力）
     * @param meterRegistry    指标注册表
     * @param clock            时间源（安全网判据；单测注入可推进的假时钟）
     * @param refreshIntervalMs 周期安全网间隔（毫秒，{@code <= 0} 关闭）
     * @param reconcileBudgetMs 单轮对账时间预算（毫秒，{@code <= 0} 不限制）
     */
    public ConfigEpochReconciler(ILeaseClient leaseClient, String nodeId, TenantLinkManager linkManager,
            MeterRegistry meterRegistry, Clock clock, long refreshIntervalMs, long reconcileBudgetMs) {
        // 与 service 侧共用同一校验口径：nodeId 为空/全空白时**构造期失败**（fail-fast），
        // 不留到运行期每 10s 打一次必然失败的远端调用（启动自检也会拦，这里是第二道）
        LeaseEpochRules.validateAccessNode(nodeId);
        this.leaseClient = leaseClient;
        this.nodeId = nodeId;
        this.linkManager = linkManager;
        this.clock = clock;
        this.refreshIntervalMs = refreshIntervalMs;
        this.reconcileBudgetMs = reconcileBudgetMs;
        this.changedCounter = Counter.builder(METRIC_PREFIX + "changed")
            .description("配置版本号变化次数").register(meterRegistry);
        this.checkFailureCounter = Counter.builder(METRIC_PREFIX + "check.failure")
            .description("配置版本号对账请求失败次数").register(meterRegistry);
        this.notAppliedCounter = Counter.builder(METRIC_PREFIX + "reconcile.not_applied")
            .description("版本号已变化但未能完成对账的租户数（同一版本号只计一次）").register(meterRegistry);
        this.deferredCounter = Counter.builder(METRIC_PREFIX + "reconcile.deferred")
            .description("因时间预算用尽而推迟到下一 tick 的租户次数").register(meterRegistry);
        this.reconcileTimer = Timer.builder(METRIC_PREFIX + "reconcile.duration")
            .description("一次配置版本对账（拉 epoch + 逐租户按需重取清单）的耗时").register(meterRegistry);
        this.forcedCounter = Counter.builder(METRIC_PREFIX + "reconcile.forced")
            .description("周期安全网触发的强制对账次数").register(meterRegistry);
    }

    /**
     * 对「本节点持有的租户」做一次配置版本对账。
     *
     * @param heldTenants 本节点当前持有的租户（空集合时**不打远端**）
     */
    public void reconcile(Set<Long> heldTenants) {
        if (heldTenants == null || heldTenants.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            doReconcile(heldTenants, startedAt);
        } finally {
            reconcileTimer.record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    /**
     * 时间预算是否已用尽（R8-2）。
     *
     * <p>用 {@code System.nanoTime()} 而不是业务时钟：预算是**进程内的耗时上界**，
     * 不该受可注入的业务 Clock（可能被假时钟/偏移校准影响）支配。</p>
     *
     * @param startedAt 本轮对账起点（{@code System.nanoTime()}）
     * @return 已用尽返回 {@code true}
     */
    private boolean budgetExhausted(long startedAt) {
        if (reconcileBudgetMs <= 0) {
            return false;
        }
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= reconcileBudgetMs;
    }

    /**
     * 对账主体（计时包装在 {@link #reconcile(Set)}）。
     *
     * @param heldTenants 本节点当前持有的租户
     * @param startedAt   本轮对账起点（{@code System.nanoTime()}，用于时间预算判定）
     */
    private void doReconcile(Set<Long> heldTenants, long startedAt) {
        R<TenantEpochBatchResp> resp;
        try {
            // 按节点过滤的一次批量调用（服务端已把过滤下沉到 SQL）；刻意不分页：分页会把一次批量
            // 调用变成每页一次串行 RPC，反而增加 tick 开销（见 LeaseEpochRules#validateAccessNode）
            resp = leaseClient.batchEpoch(nodeId);
        } catch (RuntimeException ex) {
            checkFailureCounter.increment();
            log.error("配置版本对账失败（不推进任何版本号，下一轮重试）：node={} 持有租户数={}",
                LogSanitizer.sanitize(nodeId), heldTenants.size(), ex);
            return;
        }
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            checkFailureCounter.increment();
            log.error("配置版本对账返回非成功信封（下一轮重试）：node={} code={}",
                LogSanitizer.sanitize(nodeId), resp == null ? "null" : resp.getCode());
            return;
        }
        Set<Long> attempted = new HashSet<>();
        int deferred = 0;
        // getItems() 自带 null 防御（返回空列表），故版本偏差返回 items=null 不会打断整个 tick
        for (TenantEpochItem item : resp.getData().getItems()) {
            if (item == null || item.getTenantId() == null) {
                continue;
            }
            Long tenantId = item.getTenantId();
            if (!heldTenants.contains(tenantId)) {
                // 本节点不负责的租户：不取数、不记账（否则会把别的节点的租户也拉一遍）
                continue;
            }
            long latest = item.getConfigEpoch() == null ? 0L : item.getConfigEpoch();
            Long applied = appliedConfigEpochs.get(tenantId);
            if (applied != null && applied == latest) {
                // 版本号没变 ⇒ 不需要远端取数，不计预算
                continue;
            }
            if (budgetExhausted(startedAt)) {
                // 预算用尽：本租户（以及后面的候选）推迟到下一 tick——**不丢**，下一轮仍会重试，
                // 安全网也在；否则把 tick 拖过 TTL 会触发下一轮批量自我 fence（R8-2）
                deferred++;
                continue;
            }
            if (applied != null) {
                changedCounter.increment();
                log.info("配置版本变化，触发设备清单对账：tenantId={} 已应用={} 最新={}",
                    LogSanitizer.sanitize(tenantId), applied, latest);
            }
            attempt(tenantId, latest);
            attempted.add(tenantId);
        }
        if (deferred > 0) {
            deferredCounter.increment(deferred);
            log.warn("对账时间预算用尽（{}ms），本轮推迟 {} 个租户到下一 tick（node={}）",
                reconcileBudgetMs, deferred, LogSanitizer.sanitize(nodeId));
            // 预算已用尽时**不再跑周期安全网**：它会立刻再强制一个租户（最坏 +6s），把预算白设
            return;
        }
        forceNextStaleTenant(heldTenants, attempted);
    }

    /**
     * 周期安全网：挑一个「超过安全网间隔未尝试过对账」的租户强制对账一次（每轮最多一个）。
     *
     * <p>为什么必须有它：{@code config_epoch} 信号依赖业务侧的写入口与租户上下文，
     * 单租户部署（未开租户插件）或台账没有该租户时**根本没有信号**——纯信号驱动会退化成
     * 「设备/点位改了永远发现不了」。安全网把这种情况兜成「最坏等一个安全网周期即收敛」，
     * 代价是每租户每周期一次全量对账（远端调用被「每 tick 至多一次」限流）。</p>
     *
     * @param heldTenants 本节点持有的租户
     * @param attempted   本轮已由版本号驱动尝试过的租户
     */
    private void forceNextStaleTenant(Set<Long> heldTenants, Set<Long> attempted) {
        if (refreshIntervalMs <= 0) {
            return;
        }
        Instant now = clock.instant();
        for (Long tenantId : heldTenants) {
            if (attempted.contains(tenantId)) {
                continue;
            }
            Instant last = lastAttemptAt.get(tenantId);
            if (last != null && Duration.between(last, now).toMillis() < refreshIntervalMs) {
                continue;
            }
            Long applied = appliedConfigEpochs.get(tenantId);
            // 用已应用版本号提交（对账成功后版本号不变）；未知（从未成功）按 0 提交，
            // 这样「零设备租户」在设备出现后也能被安全网带出「未应用」状态
            long latest = applied == null ? 0L : applied;
            forcedCounter.increment();
            log.debug("配置信号缺失的周期安全网：强制对账一次：tenantId={} 上次尝试={}",
                LogSanitizer.sanitize(tenantId), last);
            attempt(tenantId, latest);
            // 每轮最多强制一个：把新增的远端调用限流为每 tick 至多一次，不拖长调度 tick
            return;
        }
    }

    /**
     * 尝试对账一次并按结果推进/保留版本号。
     *
     * @param tenantId 租户 ID
     * @param latest   本次要应用的配置版本号
     */
    private void attempt(Long tenantId, long latest) {
        lastAttemptAt.put(tenantId, clock.instant());
        if (linkManager.reconcile(tenantId)) {
            appliedConfigEpochs.put(tenantId, latest);
            countedNotAppliedEpochs.remove(tenantId);
            return;
        }
        // 未完成：**同一个版本号只计一次**（零设备租户与持续失败的租户会每轮重试，
        // 若每次都计数，指标会无界单调增长而失去意义），但重试本身必须继续。
        Long counted = countedNotAppliedEpochs.put(tenantId, latest);
        if (counted == null || counted != latest) {
            notAppliedCounter.increment();
            log.warn("配置版本已变化但本轮未完成对账（下一轮重试）：tenantId={} 最新={}"
                + "（常见原因：取数失败、空清单退避、或该租户尚无设备清单）",
                LogSanitizer.sanitize(tenantId), latest);
        } else {
            log.debug("配置版本仍未完成对账（重试中，不再重复计数）：tenantId={} latest={}",
                LogSanitizer.sanitize(tenantId), latest);
        }
    }

    /**
     * 忘记某租户的已应用版本号：本节点重新持有该租户（或被回收后再次领取）时必须重新对账。
     *
     * <p>为什么必须清：租户被 fence 再重领时，上游可能已经改过配置；若沿用旧版本号，
     * 由于「版本号相同就不拉」，会拿着上一次的清单一无所知地继续采。</p>
     *
     * @param tenantId 租户 ID
     */
    public void forget(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        appliedConfigEpochs.remove(tenantId);
        countedNotAppliedEpochs.remove(tenantId);
        if (lastAttemptAt.remove(tenantId) != null) {
            log.debug("清理配置版本号记录（重新领取后会重新对账）：tenantId={}",
                LogSanitizer.sanitize(tenantId));
        }
    }

    /** 已记录配置版本的租户数（观测/测试用）。 */
    public int trackedTenantCount() {
        return appliedConfigEpochs.size();
    }
}
