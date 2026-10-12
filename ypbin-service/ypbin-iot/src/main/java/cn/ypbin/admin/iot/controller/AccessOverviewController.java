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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.lease.AccessNodeLeaseAggregate;
import cn.ypbin.admin.iot.lease.AccessNodeOverviewResp;
import cn.ypbin.admin.iot.mapper.AccessNodeMapper;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.admin.iot.platform.AccessMetricsReportStore;
import cn.ypbin.starter.core.model.R;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入侧**只读运维概览**（平台管理员）：节点在不在、谁在采谁、以及 access 侧关键指标。
 *
 * <p>为什么和台账复用同一个权限码（{@code iot:ledger:list}）：两者都是「跨租户生效的平台运维面」，
 * 受众完全相同（平台管理员）；复用已有码比新造一个码 + 改菜单授权面更小。</p>
 *
 * <p>access 侧指标来自 {@link AccessMetricsReportStore}（access **主动推送**，不直连 access）——
 * 因此本页永不因「access 不可达」而报错，只是字段为 {@code null} ⇒ 前端显示「暂无上报」。</p>
 *
 * @author wenbin
 * @since 2026-10-11
 */
@RestController
@RequestMapping("/access-overview")
@RequiredArgsConstructor
public class AccessOverviewController {

    private final AccessNodeMapper accessNodeMapper;
    private final TenantNodeAssignmentMapper assignmentMapper;
    private final AccessMetricsReportStore reportStore;

    /**
     * 概览：节点列表（持有租户数 + 最早到期时刻）+ access 上报的最新指标与心跳。
     *
     * @return 概览（`data.nodes` 为节点列表，`data.metrics` 为指标）
     */
    @GetMapping
    @SaCheckPermission("iot:ledger:list")
    public R<Map<String, Object>> overview() {
        // M7：一次 GROUP BY 取回「每节点在采租户数 + 最早到期」；旧实现每节点
        // 1 次 selectCount + 3 次 selectOne（同一参数里连续调了三次最早到期），N 节点 = 1+4N 条 SQL。
        Map<String, AccessNodeLeaseAggregate> aggregates = new HashMap<>();
        for (AccessNodeLeaseAggregate aggregate : assignmentMapper.selectActiveAggregateByNode()) {
            aggregates.put(aggregate.getAccessNode(), aggregate);
        }
        List<AccessNodeOverviewResp> nodes = accessNodeMapper.selectList(null).stream()
            .map(row -> {
                AccessNodeLeaseAggregate aggregate = aggregates.get(row.getAccessNode());
                LocalDateTime leaseExpireMin = aggregate == null ? null : aggregate.getEarliestExpireAt();
                return new AccessNodeOverviewResp(
                    row.getAccessNode(),
                    row.getMaxTenants(),
                    aggregate == null ? 0L : aggregate.getHoldingCount(),
                    row.getLastHeartbeatAt(),
                    leaseExpireMin,
                    leaseAlive(leaseExpireMin),
                    leaseRemainingSec(leaseExpireMin));
            })
            .toList();

        Map<String, Object> metrics = new LinkedHashMap<>();
        var latest = reportStore.latest();
        metrics.put("reported", latest != null);
        metrics.put("reportLagMs", reportStore.lagMs());
        if (latest != null) {
            metrics.put("reconcileDeferredCount", latest.reconcileDeferredCount());
            metrics.put("tickDurationMaxMs", latest.tickDurationMaxMs());
            metrics.put("reconcileDurationMaxMs", latest.reconcileDurationMaxMs());
            metrics.put("specLoadDurationMaxMs", latest.specLoadDurationMaxMs());
            metrics.put("selfFencedCount", latest.selfFencedCount());
            metrics.put("nodeFencedCount", latest.nodeFencedCount());
            metrics.put("renewFailureCount", latest.renewFailureCount());
            metrics.put("egressPendingCount", latest.egressPendingCount());
        }
        return R.ok(Map.of("nodes", nodes, "metrics", metrics));
    }

    /**
     * 租约是否仍有效（依据「最早到期时刻 vs 当前时刻」）。
     *
     * <p>为什么不能看 {@code access_node.last_heartbeat_at}：那个字段**只在注册/复活时写**，
     * 续约（20s 一次）不碰它 ⇒ 长期显示一个很早的时间，看着像"节点失联几小时"（实测踩到）。
     * 真正的存活性信号是租约到期时刻：只要还在续约，它就一直往后滚。</p>
     *
     * @param leaseExpireMin 最早租约到期时刻（可能为 {@code null} = 无租约）
     * @return {@code true} = 有效；{@code false} = 已过期；{@code null} = 无租约
     */
    private Boolean leaseAlive(LocalDateTime leaseExpireMin) {
        return leaseExpireMin == null ? null : !LocalDateTime.now().isAfter(leaseExpireMin);
    }

    /**
     * 距最早租约到期的秒数（负 = 已过期）。
     *
     * @param leaseExpireMin 最早租约到期时刻（可能为 {@code null}）
     * @return 秒数；无租约返回 {@code null}
     */
    private Long leaseRemainingSec(LocalDateTime leaseExpireMin) {
        if (leaseExpireMin == null) {
            return null;
        }
        return Duration.between(LocalDateTime.now(), leaseExpireMin).toSeconds();
    }

}
