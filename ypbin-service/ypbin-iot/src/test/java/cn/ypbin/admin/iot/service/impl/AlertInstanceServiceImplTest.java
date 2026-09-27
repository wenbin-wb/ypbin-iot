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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.alert.AlertGate;
import cn.ypbin.admin.iot.alert.AlertMetrics;
import cn.ypbin.admin.iot.alert.AlertNotifyPlanner;
import cn.ypbin.admin.iot.alert.AlertNotifyScheduler;
import cn.ypbin.admin.iot.alert.AlertProperties;
import cn.ypbin.admin.iot.alert.AlertRules;
import cn.ypbin.admin.iot.alert.AlertSilencePolicy;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.crud.model.PageResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 告警实例读/确认/静默面的用例（设计 §3.4-S5/S7 的用户侧动作与 §3.6.2 的总开关口径）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertInstanceServiceImplTest {

    static {
        cn.ypbin.admin.iot.alert.AlertMybatisTestSupport.initMetadata(IotDevice.class,
            MaintenanceWindow.class, IotAlertInstance.class);
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private IotAlertInstanceMapper instanceMapper;

    private IotAlertNotificationMapper notificationMapper;

    private IotAlertRuleMapper ruleMapper;

    private IotDeviceMapper deviceMapper;

    private AlertInstanceServiceImpl service;

    private final List<IotAlertNotification> queued = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        AlertProperties properties = new AlertProperties();
        instanceMapper = mock(IotAlertInstanceMapper.class);
        notificationMapper = mock(IotAlertNotificationMapper.class);
        ruleMapper = mock(IotAlertRuleMapper.class);
        deviceMapper = mock(IotDeviceMapper.class);
        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        MaintenanceWindowMapper windowMapper = mock(MaintenanceWindowMapper.class);
        when(livenessMapper.selectNow()).thenReturn(NOW);
        when(windowMapper.selectList(any())).thenReturn(List.of());
        when(notificationMapper.insertBatchIdempotent(anyList())).thenAnswer(invocation -> {
            List<IotAlertNotification> rows = invocation.getArgument(0);
            queued.addAll(rows);
            return rows.size();
        });
        AlertMetrics metrics = new AlertMetrics(new SimpleMeterRegistry());
        AlertSilencePolicy silencePolicy = new AlertSilencePolicy(windowMapper);
        service = new AlertInstanceServiceImpl(instanceMapper, notificationMapper, ruleMapper, deviceMapper,
            livenessMapper, silencePolicy, new AlertNotifyPlanner(properties),
            new AlertNotifyScheduler(silencePolicy, new AlertNotifyPlanner(properties), metrics, properties),
            metrics, new AlertGate(properties), properties);
    }

    private static IotAlertInstance instance(AlertState state) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(1L);
        instance.setTenantId(1L);
        instance.setRuleId(7L);
        instance.setDeviceId(9L);
        instance.setPropertyId("temperature");
        instance.setDedupKey(AlertRules.dedupKey(7L, 9L, "temperature"));
        instance.setActiveDedupKey(instance.getDedupKey());
        instance.setSeverity(AlertSeverity.WARNING.getCode());
        instance.setState(state.getCode());
        instance.setStartTs(NOW.minusMinutes(10));
        instance.setFiringTs(NOW.minusMinutes(9));
        instance.setNotifyCount(0);
        return instance;
    }

    private static IotAlertRule rule() {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(7L);
        rule.setRuleName("演示规则");
        rule.setNotifyChannels("INBOX");
        rule.setNotifyTargets("1001");
        return rule;
    }

    @Test
    @DisplayName("状态筛选非法 ⇒ 人话报错（不静默当成「全部」）")
    void invalidStateFilterFails() {
        AlertInstanceQuery query = new AlertInstanceQuery();
        query.setState("FIRING,SOMETHING");
        assertThatThrownBy(() -> service.page(query)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("告警状态只能是");
    }

    @Test
    @DisplayName("分页装配：设备名/规则名/投递记录各一次批量查询，且总数为数字语义")
    void pageAssemblesWithBatchQueries() {
        IotAlertInstance active = instance(AlertState.RESOLVED);
        // 已恢复实例的时长必须由**写的两个时刻**决定（不依赖真实系统时钟，否则用例会随运行日期漂移）
        active.setResolvedTs(NOW.minusMinutes(1));
        Page<IotAlertInstance> page = new Page<>(1, 10);
        page.setRecords(List.of(active));
        page.setTotal(1);
        when(instanceMapper.selectPage(any(), any())).thenReturn(page);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(rule()));
        IotAlertNotification row = new IotAlertNotification();
        row.setId(5L);
        row.setInstanceId(1L);
        row.setChannel("INBOX");
        row.setEvent(AlertNotifyEvent.FIRING.getCode());
        row.setNotifyStatus("SENT");
        when(notificationMapper.selectByInstanceIds(anyList())).thenReturn(List.of(row));

        PageResult<AlertInstanceResp> result = service.page(new AlertInstanceQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        AlertInstanceResp resp = result.getItems().get(0);
        assertThat(resp.getDeviceName()).isEqualTo("演示设备");
        assertThat(resp.getRuleName()).isEqualTo("演示规则");
        assertThat(resp.getOutage()).isFalse();
        // startTs = NOW-10min、resolvedTs = NOW-1min ⇒ 时长恰好 540 秒
        assertThat(resp.getDurationSeconds()).isEqualTo(540L);
        assertThat(resp.getNotifications()).hasSize(1);
        // 三条批量查询各一次（与行数无关）
        verify(deviceMapper, times(1)).selectList(any());
        verify(ruleMapper, times(1)).selectByIds(anyList());
        verify(notificationMapper, times(1)).selectByInstanceIds(anyList());
    }

    @Test
    @DisplayName("断档类实例（ruleId=0）识别为离线告警，不显示不存在的规则名")
    void outageInstanceIsFlagged() {
        IotAlertInstance active = instance(AlertState.FIRING);
        active.setRuleId(AlertRules.RULE_ID_OUTAGE);
        active.setPropertyId(null);
        Page<IotAlertInstance> page = new Page<>(1, 10);
        page.setRecords(List.of(active));
        page.setTotal(1);
        when(instanceMapper.selectPage(any(), any())).thenReturn(page);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(notificationMapper.selectByInstanceIds(anyList())).thenReturn(List.of());

        AlertInstanceResp resp = service.page(new AlertInstanceQuery()).getItems().get(0);
        assertThat(resp.getOutage()).isTrue();
        assertThat(resp.getRuleName()).isNull();
        // 断档类不查规则表（避免无意义的查询）
        verify(ruleMapper, times(0)).selectByIds(anyList());
    }

    @Test
    @DisplayName("一键 ACK：走批量 CAS 更新，返回实际生效条数")
    void ackUsesBatchCas() {
        when(instanceMapper.batchAck(anyList(), any(), any())).thenReturn(2);
        assertThat(service.ack(List.of(1L, 2L))).isEqualTo(2);
        verify(instanceMapper, times(1)).batchAck(eq(List.of(1L, 2L)), any(), eq(NOW));
        assertThat(service.ack(List.of())).isZero();
    }

    @Test
    @DisplayName("一键静默：截止时刻 = 数据库时钟 + 时长；非法时长明确报错")
    void silenceComputesDeadline() {
        when(instanceMapper.batchSilence(anyList(), any())).thenReturn(1);
        assertThat(service.silence(List.of(1L), 60)).isEqualTo(1);
        verify(instanceMapper, times(1)).batchSilence(eq(List.of(1L)), eq(NOW.plusHours(1)));
        assertThatThrownBy(() -> service.silence(List.of(1L), 0))
            .isInstanceOf(BusinessException.class).hasMessageContaining("静默时长必须大于 0 分钟");
    }

    @Test
    @DisplayName("概览摘要：一条聚合语句映射成八个计数（不拿列表长度当计数）")
    void summaryMapsAggregateRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activeCount", 3L);
        row.put("pendingCount", 1L);
        row.put("firingCount", 2L);
        row.put("ackedCount", 0L);
        row.put("criticalCount", 1L);
        row.put("warningCount", 2L);
        row.put("infoCount", 0L);
        row.put("resolvedLast24h", 5L);
        when(instanceMapper.selectSummary(any(), any())).thenReturn(row);

        AlertSummaryResp resp = service.summary(9L);
        assertThat(resp.getActiveCount()).isEqualTo(3L);
        assertThat(resp.getFiringCount()).isEqualTo(2L);
        assertThat(resp.getResolvedLast24h()).isEqualTo(5L);
    }

    @Test
    @DisplayName("按设备批量取活动告警数：空入参直接返回空（不生成 IN ()）")
    void activeCountsShortCircuitsOnEmptyInput() {
        assertThat(service.activeCountsByDevice(List.of())).isEmpty();
        verify(instanceMapper, times(0)).countActiveByDeviceIds(anyList());
    }

    @Test
    @DisplayName("停用规则收口：批量置 RESOLVED/释放去重键/批量写恢复通知，且不逐条更新")
    void resolveByRuleIdsBatches() {
        IotAlertInstance active = instance(AlertState.FIRING);
        when(instanceMapper.selectActiveByRuleIds(anyList())).thenReturn(List.of(active));
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(rule()));

        int resolved = service.resolveByRuleIds(List.of(7L));

        assertThat(resolved).isEqualTo(1);
        assertThat(active.getState()).isEqualTo(AlertState.RESOLVED.getCode());
        assertThat(active.getReason()).isEqualTo(AlertReason.RULE_DISABLED.getCode());
        assertThat(active.getActiveDedupKey()).isNull();
        assertThat(active.getResolvedTs()).isEqualTo(NOW);
        verify(instanceMapper, times(1)).batchUpdate(anyList());
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).getEvent()).isEqualTo(AlertNotifyEvent.RESOLVED.getCode());
    }

    @Test
    @DisplayName("停用规则收口时，维护窗口覆盖的设备不发通知（静默只影响通知，不影响收口事实）")
    void resolveByRuleIdsRespectsSilence() {
        IotAlertInstance active = instance(AlertState.FIRING);
        when(instanceMapper.selectActiveByRuleIds(anyList())).thenReturn(List.of(active));
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(rule()));
        MaintenanceWindow window = new MaintenanceWindow();
        window.setDeviceId(9L);
        window.setStartTs(NOW.minusHours(1));
        window.setEndTs(NOW.plusHours(1));

        AlertInstanceServiceImpl silenced = silencedService(List.of(window));
        assertThat(silenced.resolveByRuleIds(List.of(7L))).isEqualTo(1);
        assertThat(active.getState()).isEqualTo(AlertState.RESOLVED.getCode());
        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("总开关关闭 ⇒ 所有实例接口返回失败（不是空结果）")
    void disabledGateFails() {
        AlertProperties disabled = new AlertProperties();
        disabled.setEnabled(false);
        AlertMetrics disabledMetrics = new AlertMetrics(new SimpleMeterRegistry());
        AlertSilencePolicy disabledSilence = new AlertSilencePolicy(mock(MaintenanceWindowMapper.class));
        AlertInstanceServiceImpl gated = new AlertInstanceServiceImpl(instanceMapper, notificationMapper,
            ruleMapper, deviceMapper, mock(DeviceLivenessMapper.class), disabledSilence,
            new AlertNotifyPlanner(disabled),
            new AlertNotifyScheduler(disabledSilence, new AlertNotifyPlanner(disabled), disabledMetrics,
                disabled),
            disabledMetrics, new AlertGate(disabled), disabled);
        assertThatThrownBy(() -> gated.page(new AlertInstanceQuery()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("告警能力未启用");
        assertThatThrownBy(() -> gated.summary(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gated.activeCountsByDevice(List.of(1L)))
            .isInstanceOf(BusinessException.class);
    }

    /** 造一个「维护窗口命中」的服务实例（与 setUp 同构，只多一个窗口）。 */
    private AlertInstanceServiceImpl silencedService(List<MaintenanceWindow> windows) {
        AlertProperties properties = new AlertProperties();
        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        when(livenessMapper.selectNow()).thenReturn(NOW);
        MaintenanceWindowMapper windowMapper = mock(MaintenanceWindowMapper.class);
        when(windowMapper.selectList(any())).thenReturn(windows);
        AlertMetrics metrics = new AlertMetrics(new SimpleMeterRegistry());
        AlertSilencePolicy silencePolicy = new AlertSilencePolicy(windowMapper);
        return new AlertInstanceServiceImpl(instanceMapper, notificationMapper, ruleMapper, deviceMapper,
            livenessMapper, silencePolicy, new AlertNotifyPlanner(properties),
            new AlertNotifyScheduler(silencePolicy, new AlertNotifyPlanner(properties), metrics, properties),
            metrics, new AlertGate(properties), properties);
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(9L);
        device.setTenantId(1L);
        device.setProductId(77L);
        device.setDeviceName("演示设备");
        device.setDeviceCode("demo-dev-curve");
        return device;
    }
}
