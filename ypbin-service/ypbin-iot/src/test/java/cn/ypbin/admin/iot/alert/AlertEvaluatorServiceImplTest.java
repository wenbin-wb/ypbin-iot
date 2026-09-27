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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRulePointMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 跨租户评估编排的用例（设计 §3.1-T10、§2.2.4、§3.5-N5）。
 *
 * <p>本类补的正是独立复核（2026-10-03）指出的**覆盖真空**：{@code AlertEvaluatorServiceImpl} 此前零直接用例，
 * 于是「规则加载失败 ⇒ 本轮整体不判定、不产生任何恢复」（T10）与「租户边界对照」（N5）都没人守。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertEvaluatorServiceImplTest {

    private IotAlertRuleMapper ruleMapper;

    private IotAlertRulePointMapper pointMapper;

    private AlertTenantEvaluator tenantEvaluator;

    private SimpleMeterRegistry registry;

    private AlertEvaluatorServiceImpl service;

    @BeforeEach
    void setUp() {
        AlertProperties properties = new AlertProperties();
        ruleMapper = mock(IotAlertRuleMapper.class);
        pointMapper = mock(IotAlertRulePointMapper.class);
        tenantEvaluator = mock(AlertTenantEvaluator.class);
        registry = new SimpleMeterRegistry();
        service = new AlertEvaluatorServiceImpl(ruleMapper, pointMapper, tenantEvaluator,
            new AlertMetrics(registry), properties);
    }

    private static IotAlertRule rule(Long id, Long tenantId, String scopeType) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(id);
        rule.setTenantId(tenantId);
        rule.setRuleName("规则 " + id);
        rule.setScopeType(scopeType);
        rule.setScopeDeviceId(9L);
        rule.setScopeProductId(77L);
        rule.setSeverity("WARNING");
        rule.setEnabled(true);
        rule.setTriggerMode("IMMEDIATE");
        rule.setTriggerThreshold(0);
        return rule;
    }

    private static IotAlertRulePoint point(Long id, Long ruleId) {
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setId(id);
        point.setRuleId(ruleId);
        point.setPropertyId("temperature");
        point.setOperator("GT");
        point.setValueType("NUMERIC");
        return point;
    }

    @Test
    @DisplayName("★ T10 规则查询失败 ⇒ 本轮**整体放弃**：不评估任何租户、不产生触发、更不产生恢复")
    void t10RuleLoadFailureAbandonsRound() {
        when(ruleMapper.selectEnabledForEvaluation()).thenThrow(new RuntimeException("模拟规则表不可用"));

        AlertEvaluatorService.AlertRoundOutcome outcome = service.evaluateOnce();

        assertThat(outcome.ruleLoadFailed()).isTrue();
        assertThat(outcome.tenants()).isZero();
        assertThat(outcome.fired()).isZero();
        assertThat(outcome.resolved()).isZero();
        // 关键：一次租户评估都没有发生（否则「按空规则集判定」会把所有活动告警判成恢复）
        verify(tenantEvaluator, never()).evaluateTenant(anyLong(), anyList(), any());
        assertThat(registry.find(AlertMetrics.METRIC_RULE_LOAD_FAILED).counter().count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("N5 两个租户各自评估、规则不串租户（同一轮里分别进入各自上下文）")
    void n5TenantsAreEvaluatedSeparately() {
        IotAlertRule tenantOne = rule(1L, 1L, AlertScopeType.DEVICE.getCode());
        IotAlertRule tenantTwo = rule(2L, 2L, AlertScopeType.DEVICE.getCode());
        when(ruleMapper.selectEnabledForEvaluation()).thenReturn(List.of(tenantOne, tenantTwo));
        when(pointMapper.selectByRuleIds(anyList()))
            .thenReturn(List.of(point(11L, 1L), point(12L, 2L)));
        when(tenantEvaluator.evaluateTenant(anyLong(), anyList(), any()))
            .thenReturn(new AlertTenantEvaluator.TenantOutcome(1, 1, 0, 1, false, false));

        AlertEvaluatorService.AlertRoundOutcome outcome = service.evaluateOnce();

        assertThat(outcome.tenants()).isEqualTo(2);
        assertThat(outcome.fired()).isEqualTo(2);
        assertThat(outcome.queued()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IotAlertRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(tenantEvaluator, times(2)).evaluateTenant(anyLong(), captor.capture(), any());
        List<List<IotAlertRule>> captured = captor.getAllValues();
        assertThat(captured.get(0)).extracting(IotAlertRule::getTenantId).containsExactly(1L);
        assertThat(captured.get(1)).extracting(IotAlertRule::getTenantId).containsExactly(2L);
    }

    @Test
    @DisplayName("单租户失败不拖累其它租户，并把该轮标记为**不健康**（lag 持续增长）")
    void tenantFailureIsIsolatedAndMarksRoundUnhealthy() {
        IotAlertRule tenantOne = rule(1L, 1L, AlertScopeType.DEVICE.getCode());
        IotAlertRule tenantTwo = rule(2L, 2L, AlertScopeType.DEVICE.getCode());
        when(ruleMapper.selectEnabledForEvaluation()).thenReturn(List.of(tenantOne, tenantTwo));
        when(pointMapper.selectByRuleIds(anyList())).thenReturn(List.of());
        when(tenantEvaluator.evaluateTenant(eq(1L), anyList(), any()))
            .thenThrow(new RuntimeException("模拟租户 1 评估失败"));
        when(tenantEvaluator.evaluateTenant(eq(2L), anyList(), any()))
            .thenReturn(new AlertTenantEvaluator.TenantOutcome(1, 0, 0, 0, false, false));

        AlertEvaluatorService.AlertRoundOutcome outcome = service.evaluateOnce();

        assertThat(outcome.tenants()).isEqualTo(1);
        assertThat(outcome.failedTenants()).isEqualTo(1);
        assertThat(registry.find(AlertMetrics.METRIC_TENANT_FAILED).counter().count()).isEqualTo(1d);
        // 不健康轮次：last_success_ts 不刷新（gauge 初值 0），并计入 round.failed
        assertThat(registry.find(AlertMetrics.METRIC_LAST_SUCCESS_TS).gauge().value()).isZero();
        assertThat(registry.find(AlertMetrics.METRIC_ROUND_FAILED).counter().count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("Redis 失败的租户同样让该轮不健康（这正是设计 §2.2.4 要防的「评估器悄悄死了」）")
    void redisFailureMarksRoundUnhealthy() {
        when(ruleMapper.selectEnabledForEvaluation())
            .thenReturn(List.of(rule(1L, 1L, AlertScopeType.DEVICE.getCode())));
        when(pointMapper.selectByRuleIds(anyList())).thenReturn(List.of());
        when(tenantEvaluator.evaluateTenant(anyLong(), anyList(), any()))
            .thenReturn(new AlertTenantEvaluator.TenantOutcome(0, 0, 0, 0, false, true));

        AlertEvaluatorService.AlertRoundOutcome outcome = service.evaluateOnce();

        assertThat(outcome.redisFailedTenants()).isEqualTo(1);
        assertThat(registry.find(AlertMetrics.METRIC_LAST_SUCCESS_TS).gauge().value()).isZero();
        assertThat(registry.find(AlertMetrics.METRIC_ROUND_FAILED).counter().count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("健康轮次刷新活性：last_success_ts > 0，lag 有值，round.failed 为零")
    void healthyRoundRefreshesLiveness() {
        when(ruleMapper.selectEnabledForEvaluation())
            .thenReturn(List.of(rule(1L, 1L, AlertScopeType.DEVICE.getCode())));
        when(pointMapper.selectByRuleIds(anyList())).thenReturn(List.of());
        when(tenantEvaluator.evaluateTenant(anyLong(), anyList(), any()))
            .thenReturn(new AlertTenantEvaluator.TenantOutcome(0, 0, 0, 0, false, false));

        service.evaluateOnce();

        assertThat(registry.find(AlertMetrics.METRIC_LAST_SUCCESS_TS).gauge().value()).isGreaterThan(0d);
        assertThat(registry.find(AlertMetrics.METRIC_LAG).gauge().value()).isGreaterThanOrEqualTo(0d);
        assertThat(registry.find(AlertMetrics.METRIC_ROUND_FAILED).counter().count()).isZero();
    }

    @Test
    @DisplayName("总开关关闭 ⇒ 不执行任何评估，并明确报告未启用（不是「没有告警」）")
    void disabledReturnsDisabledOutcome() {
        AlertProperties disabled = new AlertProperties();
        disabled.setEnabled(false);
        AlertEvaluatorServiceImpl gated = new AlertEvaluatorServiceImpl(ruleMapper, pointMapper,
            tenantEvaluator, new AlertMetrics(new SimpleMeterRegistry()), disabled);

        AlertEvaluatorService.AlertRoundOutcome outcome = gated.evaluateOnce();

        assertThat(outcome.enabled()).isFalse();
        verify(ruleMapper, never()).selectEnabledForEvaluation();
    }

    @Test
    @DisplayName("无启用规则 ⇒ 一次查询即结束（不进入任何租户）")
    void noRulesEndsQuietly() {
        when(ruleMapper.selectEnabledForEvaluation()).thenReturn(List.of());
        AlertEvaluatorService.AlertRoundOutcome outcome = service.evaluateOnce();
        assertThat(outcome.tenants()).isZero();
        verify(tenantEvaluator, never()).evaluateTenant(anyLong(), anyList(), any());
        verify(pointMapper, never()).selectByRuleIds(anyList());
    }

    @Test
    @DisplayName("点位批量加载一次完成并挂在对应规则下（不逐规则查）")
    void pointsAreLoadedOncePerRound() {
        when(ruleMapper.selectEnabledForEvaluation())
            .thenReturn(List.of(rule(1L, 1L, AlertScopeType.DEVICE.getCode()),
                rule(2L, 1L, AlertScopeType.DEVICE.getCode())));
        when(pointMapper.selectByRuleIds(anyList()))
            .thenReturn(List.of(point(11L, 1L), point(12L, 1L), point(13L, 2L)));
        when(tenantEvaluator.evaluateTenant(anyLong(), anyList(), any()))
            .thenReturn(new AlertTenantEvaluator.TenantOutcome(0, 0, 0, 0, false, false));

        service.evaluateOnce();

        verify(pointMapper, times(1)).selectByRuleIds(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<IotAlertRulePoint>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tenantEvaluator, times(1)).evaluateTenant(anyLong(), anyList(), captor.capture());
        assertThat(captor.getValue().get(1L)).hasSize(2);
        assertThat(captor.getValue().get(2L)).hasSize(1);
    }
}
