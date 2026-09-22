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
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final TenantLinkManager linkManager;

    /** 已按之完成对账的配置版本号：tenantId → configEpoch。 */
    private final Map<Long, Long> appliedConfigEpochs = new ConcurrentHashMap<>();

    /** 版本号变化的次数（观测「真的有多少次配置变更」）。 */
    private final Counter changedCounter;

    /** 对账请求（{@code /internal/lease/epochs}）失败次数。 */
    private final Counter checkFailureCounter;

    /** 已变化但本轮未能完成对账的次数（下轮重试；持续增长说明取数一直在失败）。 */
    private final Counter notAppliedCounter;

    /**
     * 构造对账器。
     *
     * @param leaseClient  租约客户端（提供批量 epoch 对账接口）
     * @param linkManager  链路控制端口（提供按最新配置对账设备清单的能力）
     * @param meterRegistry 指标注册表
     */
    public ConfigEpochReconciler(ILeaseClient leaseClient, TenantLinkManager linkManager,
            MeterRegistry meterRegistry) {
        this.leaseClient = leaseClient;
        this.linkManager = linkManager;
        this.changedCounter = Counter.builder(METRIC_PREFIX + "changed")
            .description("配置版本号变化次数").register(meterRegistry);
        this.checkFailureCounter = Counter.builder(METRIC_PREFIX + "check.failure")
            .description("配置版本号对账请求失败次数").register(meterRegistry);
        this.notAppliedCounter = Counter.builder(METRIC_PREFIX + "reconcile.not_applied")
            .description("版本号已变化但本轮未完成对账的次数（下一轮重试）").register(meterRegistry);
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
        R<TenantEpochBatchResp> resp;
        try {
            resp = leaseClient.batchEpoch();
        } catch (RuntimeException ex) {
            checkFailureCounter.increment();
            log.error("配置版本对账失败（不推进任何版本号，下一轮重试）：node 持有租户数={}",
                heldTenants.size(), ex);
            return;
        }
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            checkFailureCounter.increment();
            log.error("配置版本对账返回非成功信封（下一轮重试）：code={}",
                resp == null ? "null" : resp.getCode());
            return;
        }
        for (TenantEpochItem item : resp.getData().getItems()) {
            reconcileItem(item, heldTenants);
        }
    }

    /**
     * 对单个租户的版本号条目做对账。
     *
     * @param item        业务侧返回的版本号条目
     * @param heldTenants 本节点持有的租户
     */
    private void reconcileItem(TenantEpochItem item, Set<Long> heldTenants) {
        if (item == null || item.getTenantId() == null) {
            return;
        }
        Long tenantId = item.getTenantId();
        if (!heldTenants.contains(tenantId)) {
            // 本节点不负责的租户：不取数、不记账（否则会把别的节点的租户也拉一遍）
            return;
        }
        long latest = item.getConfigEpoch() == null ? 0L : item.getConfigEpoch();
        Long applied = appliedConfigEpochs.get(tenantId);
        if (applied != null && applied == latest) {
            return;
        }
        boolean firstSight = applied == null;
        if (!firstSight) {
            changedCounter.increment();
            log.info("配置版本变化，触发设备清单对账：tenantId={} 已应用={} 最新={}",
                LogSanitizer.sanitize(tenantId), applied, latest);
        }
        if (linkManager.reconcile(tenantId)) {
            appliedConfigEpochs.put(tenantId, latest);
        } else {
            notAppliedCounter.increment();
            log.warn("配置版本已变化但本轮未完成对账（下一轮重试）：tenantId={} 最新={}"
                + "（常见原因：取数失败或处于空清单退避窗口）",
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
        if (tenantId != null && appliedConfigEpochs.remove(tenantId) != null) {
            log.debug("清理配置版本号记录（重新领取后会重新对账）：tenantId={}",
                LogSanitizer.sanitize(tenantId));
        }
    }

    /** 已记录配置版本的租户数（观测/测试用）。 */
    public int trackedTenantCount() {
        return appliedConfigEpochs.size();
    }
}
