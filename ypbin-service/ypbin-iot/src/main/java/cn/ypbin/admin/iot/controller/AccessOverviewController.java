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
import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import cn.ypbin.admin.iot.lease.AccessNodeOverviewResp;
import cn.ypbin.admin.iot.mapper.AccessNodeMapper;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.admin.iot.platform.AccessMetricsReportStore;
import cn.ypbin.starter.core.model.R;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
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

    /** 租约状态：在采。与 {@code LeaseState.ACTIVE} 的 code 一致（裸字符串在 SQL 与门禁中已多处存在）。 */
    private static final String STATE_ACTIVE = "active";

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
        List<AccessNodeOverviewResp> nodes = accessNodeMapper.selectList(null).stream()
            .map(row -> new AccessNodeOverviewResp(
                row.getAccessNode(),
                row.getMaxTenants(),
                assignmentMapper.selectCount(Wrappers.lambdaQuery(TenantNodeAssignment.class)
                    .eq(TenantNodeAssignment::getAccessNode, row.getAccessNode())
                    .eq(TenantNodeAssignment::getState, STATE_ACTIVE)),
                row.getLastHeartbeatAt(),
                earliestLeaseExpireAt(row.getAccessNode())))
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
     * 该节点最早的租约到期时刻；**没持有任何租户时返回 `null`**（节点刚注册 / 刚全部释放）。
     *
     * <p>为什么必须判空：空表上 `selectOne` 返回 null，直接取字段会让整个概览接口 500——
     * 而这正是「节点刚注册还没领到租户」的正常状态（测试实证过）。</p>
     *
     * @param accessNode 节点标识
     * @return 最早到期时刻，或 `null`
     */
    private LocalDateTime earliestLeaseExpireAt(String accessNode) {
        TenantNodeAssignment earliest = assignmentMapper.selectOne(
            Wrappers.lambdaQuery(TenantNodeAssignment.class)
                .select(TenantNodeAssignment::getLeaseExpireAt)
                .eq(TenantNodeAssignment::getAccessNode, accessNode)
                .orderByAsc(TenantNodeAssignment::getLeaseExpireAt)
                .last("LIMIT 1"));
        return earliest == null ? null : earliest.getLeaseExpireAt();
    }
}
