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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跨租户的评估轮次编排（设计 §2.2.2）。
 *
 * <p><b>两段式租户处理</b>：规则**一次全量跨租户加载**（规则数百~千级，一次扫描远优于「按租户列表逐个查」），
 * 随后**逐租户进入各自上下文**评估——这样所有设备/实例/通知的读写都受租户插件约束，
 * 而不是靠手写 {@code tenant_id} 条件（后者会被租户隔离门禁拦下，且一旦漏写就是跨租户越权）。</p>
 *
 * <p><b>失败隔离</b>：规则加载失败 ⇒ 本轮**整体放弃**（宁可不判，也不按空规则集判成「全部恢复」）；
 * 单租户失败 ⇒ 只放弃该租户，其它租户照常（并把失败计数上报）。两种失败都被显式计数，
 * 不会出现「评估器悄悄死了但没人知道」（设计 §2.2.4 结尾那段）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Service
public class AlertEvaluatorServiceImpl implements AlertEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluatorServiceImpl.class);

    private final IotAlertRuleMapper ruleMapper;
    private final IotAlertRulePointMapper pointMapper;
    private final AlertTenantEvaluator tenantEvaluator;
    private final AlertMetrics metrics;
    private final AlertProperties properties;

    public AlertEvaluatorServiceImpl(IotAlertRuleMapper ruleMapper, IotAlertRulePointMapper pointMapper,
                                     AlertTenantEvaluator tenantEvaluator, AlertMetrics metrics,
                                     AlertProperties properties) {
        this.ruleMapper = ruleMapper;
        this.pointMapper = pointMapper;
        this.tenantEvaluator = tenantEvaluator;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public AlertRoundOutcome evaluateOnce() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 告警总开关已关闭（{}.enabled=false）：评估器不执行；接口会返回明确失败",
                AlertProperties.PREFIX);
            return AlertRoundOutcome.disabled();
        }
        metrics.roundStarted();
        long startedAt = System.currentTimeMillis();
        Map<Long, List<IotAlertRule>> rulesByTenant;
        Map<Long, List<IotAlertRulePoint>> pointsByRuleId;
        try {
            // 跨租户加载（没有租户身份 ⇒ 必须显式忽略，否则租户插件 fail-closed 直接拒绝执行）
            List<IotAlertRule> rules = TenantContext.executeIgnore(ruleMapper::selectEnabledForEvaluation);
            pointsByRuleId = loadPoints(rules);
            rulesByTenant = groupByTenant(rules);
        } catch (RuntimeException ex) {
            metrics.ruleLoadFailed();
            log.error("[iot] 告警评估：规则加载失败，本轮**整体放弃**"
                + "（不产生任何触发、也不产生任何恢复）", ex);
            metrics.recordRoundDuration(Duration.ofMillis(System.currentTimeMillis() - startedAt));
            return AlertRoundOutcome.ruleLoadFailure();
        }
        int tenants = 0;
        int failed = 0;
        int redisFailed = 0;
        int truncated = 0;
        int candidates = 0;
        int fired = 0;
        int resolved = 0;
        int queued = 0;
        for (Map.Entry<Long, List<IotAlertRule>> entry : rulesByTenant.entrySet()) {
            Long tenantId = entry.getKey();
            AlertTenantEvaluator.TenantOutcome outcome;
            try {
                outcome = TenantContext.executeWithTenant(tenantId,
                    () -> tenantEvaluator.evaluateTenant(tenantId, entry.getValue(), pointsByRuleId));
            } catch (RuntimeException ex) {
                metrics.tenantFailed();
                failed++;
                log.error("[iot] 告警评估：租户 {} 本轮评估失败（该租户放弃，其它租户照常）", tenantId, ex);
                continue;
            }
            tenants++;
            candidates += outcome.candidates();
            fired += outcome.fired();
            resolved += outcome.resolved();
            queued += outcome.queued();
            if (outcome.redisFailed()) {
                redisFailed++;
            }
            if (outcome.truncated()) {
                truncated++;
            }
        }
        metrics.recordRoundDuration(Duration.ofMillis(System.currentTimeMillis() - startedAt));
        // 健康 = 本轮没有任何租户失败、也没有 Redis 读取失败。只要有一项不满足就**不刷新**活性时刻，
        // 让 iot.alert.evaluate.lag 持续增长（设计 §2.2.4：评估器不健康必须能被人看见）
        if (failed == 0 && redisFailed == 0) {
            metrics.roundSucceeded();
        } else {
            metrics.roundFailed();
            log.error("[iot] 告警评估轮次不健康：失败租户 {}、Redis 失败租户 {}（last_success_ts 不刷新，"
                + "lag 会持续增长）", failed, redisFailed);
        }
        if (fired > 0 || resolved > 0 || queued > 0) {
            log.info("[iot] 告警评估完成：租户 {}（失败 {}）、候选 {}、触发 {}、恢复 {}、待投递通知 {}",
                tenants, failed, candidates, fired, resolved, queued);
        } else {
            log.debug("[iot] 告警评估完成：租户 {}、候选 {}、无状态变化", tenants, candidates);
        }
        return new AlertRoundOutcome(true, false, tenants, failed, redisFailed, truncated, candidates,
            fired, resolved, queued);
    }

    /**
     * 批量加载点位条件并**一次**暴露「规则 ID → 条件行」（跨租户读取，显式忽略租户）。
     *
     * <p>判空短路是必须的：空集合会生成 {@code IN ()} 语法错误（本仓纪律：批量 IN 前先判空返回空集合）。
     *
     * @param rules 本轮涉及的规则
     * @return 规则 ID → 条件行（无条件行的规则不出现在 map 里）
     */
    private Map<Long, List<IotAlertRulePoint>> loadPoints(List<IotAlertRule> rules) {
        Map<Long, List<IotAlertRulePoint>> pointsByRuleId = new LinkedHashMap<>();
        if (rules.isEmpty()) {
            return pointsByRuleId;
        }
        List<Long> ruleIds = new ArrayList<>(rules.size());
        for (IotAlertRule rule : rules) {
            ruleIds.add(rule.getId());
        }
        if (ruleIds.isEmpty()) {
            return pointsByRuleId;
        }
        List<IotAlertRulePoint> points = TenantContext.executeIgnore(
            () -> pointMapper.selectByRuleIds(ruleIds));
        for (IotAlertRulePoint point : points) {
            pointsByRuleId.computeIfAbsent(point.getRuleId(), key -> new ArrayList<>()).add(point);
        }
        return pointsByRuleId;
    }

    /** 按租户分组（纯内存；保持租户 ID 升序，让每轮处理顺序稳定、便于排查）。 */
    private static Map<Long, List<IotAlertRule>> groupByTenant(List<IotAlertRule> rules) {
        Map<Long, List<IotAlertRule>> grouped = new LinkedHashMap<>();
        for (IotAlertRule rule : rules) {
            if (rule.getTenantId() == null) {
                // 租户表插入时由插件/回填写入 tenant_id，取不到说明数据异常：跳过并留痕，绝不跨租户乱跑
                log.error("[iot] 告警评估：规则 {} 缺少 tenant_id，已跳过（数据异常）", rule.getId());
                continue;
            }
            grouped.computeIfAbsent(rule.getTenantId(), key -> new ArrayList<>()).add(rule);
        }
        return grouped;
    }
}
