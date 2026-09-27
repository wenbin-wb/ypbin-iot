/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRulePointMapper;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 断档 → 告警映射的跨租户编排（用户已批准口径 4：离线/断档**并入告警**，映射既有事件、不新造判定）。
 *
 * <p>与评估器同构的两段式：跨租户加载一次规则（{@code executeIgnore}）→ 按租户分组 →
 * 逐租户进入上下文映射（这样设备/实例/通知的读写都受租户插件约束）。</p>
 *
 * <p><b>「离线规则」的识别方式</b>：**没有点位条件的启用规则**。它复用同一张规则表，因此作用域四级、
 * 级别、通知渠道、重复间隔、静默窗口全部照旧生效——不需要为「离线告警」新造一套配置表，
 * 也不会出现「两种规则口径不一致」。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Service
public class AlertOutageMappingServiceImpl implements AlertOutageMappingService {

    private static final Logger log = LoggerFactory.getLogger(AlertOutageMappingServiceImpl.class);

    private final IotAlertRuleMapper ruleMapper;
    private final IotAlertRulePointMapper pointMapper;
    private final AlertTenantOutageService tenantOutageService;
    private final AlertMetrics metrics;
    private final AlertProperties properties;

    public AlertOutageMappingServiceImpl(IotAlertRuleMapper ruleMapper,
                                         IotAlertRulePointMapper pointMapper,
                                         AlertTenantOutageService tenantOutageService,
                                         AlertMetrics metrics, AlertProperties properties) {
        this.ruleMapper = ruleMapper;
        this.pointMapper = pointMapper;
        this.tenantOutageService = tenantOutageService;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public OutageOutcome mapOnce() {
        if (!properties.isEnabled()) {
            return OutageOutcome.skipped(false);
        }
        if (!properties.isOutageEnabled()) {
            log.warn("[iot] 断档 → 告警映射已关闭（{}.outage-enabled=false）：离线/数据中断不会产生告警",
                AlertProperties.PREFIX);
            return OutageOutcome.skipped(true);
        }
        Map<Long, List<IotAlertRule>> outageRulesByTenant;
        try {
            List<IotAlertRule> rules = TenantContext.executeIgnore(ruleMapper::selectEnabledForEvaluation);
            Map<Long, List<IotAlertRulePoint>> pointsByRuleId = loadPoints(rules);
            outageRulesByTenant = filterOfflineRules(rules, pointsByRuleId);
        } catch (RuntimeException ex) {
            metrics.ruleLoadFailed();
            log.error("[iot] 断档告警映射：规则加载失败，本轮整体放弃（不产生任何新建/恢复）", ex);
            return OutageOutcome.ruleLoadFailure();
        }
        int tenants = 0;
        int devices = 0;
        int open = 0;
        int created = 0;
        int resolved = 0;
        int queued = 0;
        int orphan = 0;
        for (Map.Entry<Long, List<IotAlertRule>> entry : outageRulesByTenant.entrySet()) {
            Long tenantId = entry.getKey();
            AlertTenantOutageService.TenantOutageOutcome outcome;
            try {
                outcome = TenantContext.executeWithTenant(tenantId,
                    () -> tenantOutageService.mapTenant(entry.getValue()));
            } catch (RuntimeException ex) {
                log.error("[iot] 断档告警映射：租户 {} 本轮失败（其它租户照常）", tenantId, ex);
                continue;
            }
            tenants++;
            devices += outcome.devices();
            open += outcome.open();
            created += outcome.created();
            resolved += outcome.resolved();
            queued += outcome.queued();
            orphan += outcome.orphan();
        }
        if (created > 0 || resolved > 0 || orphan > 0) {
            log.info("[iot] 断档告警映射完成：租户 {}（覆盖设备 {}、进行中断档 {}）、新建 {}、恢复 {}、"
                + "通知 {}、孤立 {}", tenants, devices, open, created, resolved, queued, orphan);
        } else {
            log.debug("[iot] 断档告警映射完成：无状态变化（租户 {}、覆盖设备 {}）", tenants, devices);
        }
        return new OutageOutcome(true, false, false, tenants, devices, open, created, resolved, queued,
            orphan);
    }

    /** 批量加载点位条件（跨租户读；空集合直接短路，避免生成 {@code IN ()}）。 */
    private Map<Long, List<IotAlertRulePoint>> loadPoints(List<IotAlertRule> rules) {
        Map<Long, List<IotAlertRulePoint>> pointsByRuleId = new LinkedHashMap<>();
        if (rules.isEmpty()) {
            return pointsByRuleId;
        }
        List<Long> ruleIds = new ArrayList<>(rules.size());
        for (IotAlertRule rule : rules) {
            ruleIds.add(rule.getId());
        }
        for (IotAlertRulePoint point : TenantContext.executeIgnore(
            () -> pointMapper.selectByRuleIds(ruleIds))) {
            pointsByRuleId.computeIfAbsent(point.getRuleId(), key -> new ArrayList<>()).add(point);
        }
        return pointsByRuleId;
    }

    /** 只保留「没有点位条件」的启用规则（= 离线/数据中断类），并按租户分组。 */
    private static Map<Long, List<IotAlertRule>> filterOfflineRules(List<IotAlertRule> rules,
                                                                    Map<Long, List<IotAlertRulePoint>> points) {
        Map<Long, List<IotAlertRule>> grouped = new LinkedHashMap<>();
        for (IotAlertRule rule : rules) {
            List<IotAlertRulePoint> rulePoints = points.get(rule.getId());
            if (rulePoints != null && !rulePoints.isEmpty()) {
                continue;
            }
            if (rule.getTenantId() == null) {
                log.error("[iot] 断档告警映射：规则 {} 缺少 tenant_id，已跳过（数据异常）", rule.getId());
                continue;
            }
            grouped.computeIfAbsent(rule.getTenantId(), key -> new ArrayList<>()).add(rule);
        }
        return grouped;
    }
}
