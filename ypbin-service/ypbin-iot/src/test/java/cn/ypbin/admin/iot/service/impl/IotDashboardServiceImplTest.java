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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.OnlineStatus;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.service.IotDashboardSnapshotService;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.AlertTrendPoint;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.DeviceGrowthTrendPoint;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.MessageTrendPoint;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.RecentAlert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行总览聚合的纯逻辑单测（Mockito mock Mapper，不起 Spring、不连库）。
 *
 * <p><b>为什么用 mock 而不是真实 SQL</b>：本类只负责「查什么口径 + 结果怎么合并」，
 * 分页/租户条件/SQL 生成由 MyBatis-Plus 与租户插件承担；真实库上的租户隔离与分组 SQL
 * 由集成测试面覆盖。这里钉住的是回归风险最高的几处：计数的租户无关正确性、30 日趋势的
 * 补零与上下行合并、告警趋势的 severity 透视、设备增长趋势补零、规则 TOP5 排序与名称解析、
 * 空数据回落、recentAlerts 的批量名称解析与触发时间回落。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class IotDashboardServiceImplTest {

    private static final long DEVICE_ID_1 = 9300012L;

    private static final long DEVICE_ID_2 = 9300013L;

    private static final long RULE_ID = 9200001L;

    private static final long RULE_ID_2 = 9200002L;

    private static final long PRODUCT_ID = 9100001L;

    private static final long UNKNOWN_PRODUCT_ID = 9100002L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final IotProductMapper productMapper = mock(IotProductMapper.class);

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);

    private final IotMqttIngestReceiptMapper receiptMapper = mock(IotMqttIngestReceiptMapper.class);

    private final IotCommandInstanceMapper commandMapper = mock(IotCommandInstanceMapper.class);

    private final IotAlertInstanceMapper alertMapper = mock(IotAlertInstanceMapper.class);

    private final IotAlertRuleMapper ruleMapper = mock(IotAlertRuleMapper.class);

    private final IotDashboardSnapshotService snapshotService = mock(IotDashboardSnapshotService.class);

    private final IotDashboardServiceImpl service = new IotDashboardServiceImpl(
        deviceMapper, productMapper, propertyMapper, receiptMapper, commandMapper, alertMapper, ruleMapper,
        snapshotService);

    @Test
    @DisplayName("完整数据：设备/消息/告警计数、30 日趋势合并、协议/级别/产品 TOP 与最近告警")
    void overviewShouldAggregateEverything() {
        stubDeviceCounts(2148L, 1976L, 135L, 37L, 96L);
        when(productMapper.selectCount(any())).thenReturn(48L);
        when(propertyMapper.selectCount(any())).thenReturn(8642L);

        LocalDate today = LocalDate.now();
        long uplinkToday = 1_284_506L;
        long downlinkToday = 96_420L;
        when(receiptMapper.selectMaps(any())).thenAnswer(invocation -> {
            if (sqlSelectOf(invocation.getArgument(0)).contains("DATE(create_time)")) {
                return List.of(
                    row("day", today.minusDays(40).toString(), "total", 999L),
                    row("day", today.minusDays(2).toString(), "total", 100L),
                    row("day", today.toString(), "total", uplinkToday));
            }
            return List.of(row("total", uplinkToday));
        });
        when(commandMapper.selectCount(any())).thenReturn(downlinkToday);
        when(commandMapper.selectMaps(any())).thenReturn(List.of(
            row("day", today.minusDays(2).toString(), "total", 200L),
            row("day", today.toString(), "total", downlinkToday)));

        when(deviceMapper.selectMaps(any())).thenAnswer(invocation -> {
            String select = sqlSelectOf(invocation.getArgument(0));
            if (select.contains("DATE(create_time)")) {
                return List.of(
                    row("day", today.minusDays(1).toString(), "total", 5L),
                    row("day", today.toString(), "total", 2L));
            }
            if (select.contains("protocol")) {
                return List.of(row("name", "mqtt", "value", 986L), row("name", "modbus", "value", 512L));
            }
            return List.of(row("product_id", PRODUCT_ID, "device_count", 428L));
        });
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(PRODUCT_ID, "智能电表 · 三相")));

        stubAlertCounts(71L, 58L, 34L);
        when(alertMapper.selectMaps(any())).thenAnswer(invocation -> {
            String select = sqlSelectOf(invocation.getArgument(0));
            if (select.contains("rule_id")) {
                return List.of(row("rule_id", RULE_ID, "alert_count", 12L));
            }
            if (select.contains("DATE(create_time)")) {
                return List.of(
                    row("day", today.minusDays(40).toString(), "severity", "CRITICAL", "total", 99L),
                    row("day", today.minusDays(2).toString(), "severity", "CRITICAL", "total", 3L),
                    row("day", today.minusDays(2).toString(), "severity", "WARNING", "total", 1L),
                    row("day", today.toString(), "severity", "INFO", "total", 5L));
            }
            // 库内是大写码，总览响应要按前端 i18n key 口径转小写
            return List.of(
                row("name", "INFO", "value", 41L),
                row("name", "WARNING", "value", 23L),
                row("name", "CRITICAL", "value", 7L));
        });

        IotAlertInstance first = alert(9001L, DEVICE_ID_1, RULE_ID, "CRITICAL", "FIRING",
            LocalDateTime.of(2026, 10, 14, 10, 30), LocalDateTime.of(2026, 10, 14, 10, 0));
        IotAlertInstance second = alert(9002L, DEVICE_ID_2, 0L, "WARNING", "RESOLVED",
            null, LocalDateTime.of(2026, 10, 14, 9, 5));
        when(alertMapper.selectList(any())).thenReturn(List.of(first, second));
        when(deviceMapper.selectByIds(any())).thenReturn(List.of(
            device(DEVICE_ID_1, "电表-A3-0172"), device(DEVICE_ID_2, "冷链-仓B-008")));
        when(ruleMapper.selectByIds(any())).thenReturn(List.of(rule(RULE_ID, "电流超上限")));
        when(snapshotService.loadRecent(anyInt())).thenReturn(List.of(
            snapshot(today.minusDays(2), 1970, 141),
            snapshot(today, 1976, 135)));

        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getDeviceTotal()).isEqualTo(2148);
        assertThat(resp.getDeviceOnline()).isEqualTo(1976);
        assertThat(resp.getDeviceOffline()).isEqualTo(135);
        assertThat(resp.getDeviceDisabled()).isEqualTo(37);
        assertThat(resp.getOnlineRate()).isEqualTo(93.6);
        assertThat(resp.getProductTotal()).isEqualTo(48);
        assertThat(resp.getPointTotal()).isEqualTo(8642);
        assertThat(resp.getNewDevicesWeek()).isEqualTo(96);
        assertThat(resp.getMessageUplinkToday()).isEqualTo(1_284_506);
        assertThat(resp.getMessageDownlinkToday()).isEqualTo(96_420);
        assertThat(resp.getMessageToday()).isEqualTo(1_380_926);
        assertThat(resp.getAlertPending()).isEqualTo(71);
        assertThat(resp.getAlertToday()).isEqualTo(58);
        assertThat(resp.getAlertResolvedToday()).isEqualTo(34);

        assertThat(resp.getMessageTrend()).hasSize(30);
        assertThat(resp.getMessageTrend().get(0).getDate()).isEqualTo(today.minusDays(29).toString());
        assertThat(resp.getMessageTrend().get(29).getDate()).isEqualTo(today.toString());
        MessageTrendPoint twoDaysAgo = pointAt(resp, today.minusDays(2));
        assertThat(twoDaysAgo.getUplink()).isEqualTo(100);
        assertThat(twoDaysAgo.getDownlink()).isEqualTo(200);
        MessageTrendPoint noDataDay = pointAt(resp, today.minusDays(10));
        assertThat(noDataDay.getUplink()).isZero();
        assertThat(noDataDay.getDownlink()).isZero();
        assertThat(resp.getMessageTrend().get(29).getUplink()).isEqualTo(1_284_506);
        assertThat(resp.getMessageTrend().get(29).getDownlink()).isEqualTo(96_420);

        assertThat(resp.getOnlineTrend()).hasSize(2);
        assertThat(resp.getOnlineTrend().get(0).getDate()).isEqualTo(today.minusDays(2).toString());
        assertThat(resp.getOnlineTrend().get(0).getOnline()).isEqualTo(1970);
        assertThat(resp.getOnlineTrend().get(0).getOffline()).isEqualTo(141);
        assertThat(resp.getOnlineTrend().get(1).getDate()).isEqualTo(today.toString());
        assertThat(resp.getOnlineTrend().get(1).getOnline()).isEqualTo(1976);
        assertThat(resp.getOnlineTrend().get(1).getOffline()).isEqualTo(135);
        verify(snapshotService).captureCurrentTenant();

        assertThat(resp.getProtocolDistribution()).hasSize(2);
        assertThat(resp.getProtocolDistribution().get(0).getName()).isEqualTo("mqtt");
        assertThat(resp.getProtocolDistribution().get(0).getValue()).isEqualTo(986);
        assertThat(resp.getSeverityDistribution()).hasSize(3);
        assertThat(resp.getSeverityDistribution().get(0).getName()).isEqualTo("info");
        assertThat(resp.getSeverityDistribution().get(0).getValue()).isEqualTo(41);

        assertThat(resp.getProductTop()).hasSize(1);
        assertThat(resp.getProductTop().get(0).getName()).isEqualTo("智能电表 · 三相");
        assertThat(resp.getProductTop().get(0).getValue()).isEqualTo(428);

        assertThat(resp.getAlertTrend()).hasSize(30);
        assertThat(resp.getAlertTrend().get(0).getDate()).isEqualTo(today.minusDays(29).toString());
        assertThat(resp.getAlertTrend().get(29).getDate()).isEqualTo(today.toString());
        AlertTrendPoint alertTwoDaysAgo = alertPointAt(resp, today.minusDays(2));
        assertThat(alertTwoDaysAgo.getCritical()).isEqualTo(3);
        assertThat(alertTwoDaysAgo.getWarning()).isEqualTo(1);
        assertThat(alertTwoDaysAgo.getInfo()).isZero();
        AlertTrendPoint alertNoDataDay = alertPointAt(resp, today.minusDays(10));
        assertThat(alertNoDataDay.getCritical()).isZero();
        assertThat(alertNoDataDay.getWarning()).isZero();
        assertThat(alertNoDataDay.getInfo()).isZero();
        assertThat(alertPointAt(resp, today).getInfo()).isEqualTo(5);
        assertThat(alertPointAt(resp, today).getCritical()).isZero();

        assertThat(resp.getDeviceGrowthTrend()).hasSize(14);
        assertThat(resp.getDeviceGrowthTrend().get(0).getDate()).isEqualTo(today.minusDays(13).toString());
        assertThat(resp.getDeviceGrowthTrend().get(13).getDate()).isEqualTo(today.toString());
        assertThat(growthAt(resp, today.minusDays(1)).getAdded()).isEqualTo(5);
        assertThat(growthAt(resp, today).getAdded()).isEqualTo(2);
        assertThat(growthAt(resp, today.minusDays(5)).getAdded()).isZero();

        assertThat(resp.getAlertRuleTop()).hasSize(1);
        assertThat(resp.getAlertRuleTop().get(0).getName()).isEqualTo("电流超上限");
        assertThat(resp.getAlertRuleTop().get(0).getValue()).isEqualTo(12);

        assertThat(resp.getRecentAlerts()).hasSize(2);
        RecentAlert firstResp = resp.getRecentAlerts().get(0);
        assertThat(firstResp.getId()).isEqualTo("9001");
        assertThat(firstResp.getDeviceName()).isEqualTo("电表-A3-0172");
        assertThat(firstResp.getRuleName()).isEqualTo("电流超上限");
        assertThat(firstResp.getSeverity()).isEqualTo("critical");
        assertThat(firstResp.getState()).isEqualTo("firing");
        assertThat(firstResp.getTriggeredAt()).isEqualTo("2026-10-14 10:30");
        RecentAlert secondResp = resp.getRecentAlerts().get(1);
        assertThat(secondResp.getTriggeredAt()).isEqualTo("2026-10-14 09:05");
        assertThat(secondResp.getRuleName()).as("断档类告警（ruleId=0）没有规则名，不展示 0").isNull();

        verify(deviceMapper).selectByIds(argThat(ids -> ids.size() == 2
            && ids.contains(DEVICE_ID_1) && ids.contains(DEVICE_ID_2)));
        // 最近告警与规则 TOP 各解析一次规则名（同一个规则 ID）
        verify(ruleMapper, times(2)).selectByIds(argThat(ids -> ids.size() == 1 && ids.contains(RULE_ID)));
        verify(productMapper).selectByIds(argThat(ids -> ids.size() == 1 && ids.contains(PRODUCT_ID)));
    }

    @Test
    @DisplayName("空数据回落：计数为 0、在线率 0、趋势补零 30 天、各集合为空且不空转批量查询")
    void emptyDataShouldFallBackToZerosAndEmptyCollections() {
        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getDeviceTotal()).isZero();
        assertThat(resp.getDeviceOnline()).isZero();
        assertThat(resp.getDeviceOffline()).isZero();
        assertThat(resp.getDeviceDisabled()).isZero();
        assertThat(resp.getOnlineRate()).isEqualTo(0.0);
        assertThat(resp.getProductTotal()).isZero();
        assertThat(resp.getPointTotal()).isZero();
        assertThat(resp.getNewDevicesWeek()).isZero();
        assertThat(resp.getMessageToday()).isZero();
        assertThat(resp.getMessageUplinkToday()).isZero();
        assertThat(resp.getMessageDownlinkToday()).isZero();
        assertThat(resp.getAlertPending()).isZero();
        assertThat(resp.getAlertToday()).isZero();
        assertThat(resp.getAlertResolvedToday()).isZero();

        assertThat(resp.getMessageTrend()).hasSize(30)
            .allSatisfy(point -> {
                assertThat(point.getUplink()).isZero();
                assertThat(point.getDownlink()).isZero();
            });
        assertThat(resp.getMessageTrend().get(29).getDate()).isEqualTo(LocalDate.now().toString());
        assertThat(resp.getOnlineTrend()).isEmpty();
        assertThat(resp.getProtocolDistribution()).isEmpty();
        assertThat(resp.getSeverityDistribution()).isEmpty();
        assertThat(resp.getProductTop()).isEmpty();
        assertThat(resp.getRecentAlerts()).isEmpty();

        assertThat(resp.getAlertTrend()).hasSize(30)
            .allSatisfy(point -> {
                assertThat(point.getCritical()).isZero();
                assertThat(point.getWarning()).isZero();
                assertThat(point.getInfo()).isZero();
            });
        assertThat(resp.getAlertTrend().get(29).getDate()).isEqualTo(LocalDate.now().toString());
        assertThat(resp.getDeviceGrowthTrend()).hasSize(14)
            .allSatisfy(point -> assertThat(point.getAdded()).isZero());
        assertThat(resp.getDeviceGrowthTrend().get(0).getDate())
            .isEqualTo(LocalDate.now().minusDays(13).toString());
        assertThat(resp.getAlertRuleTop()).isEmpty();

        verify(deviceMapper, never()).selectByIds(any());
        verify(ruleMapper, never()).selectByIds(any());
        verify(productMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("新增趋势：告警按 severity 透视并逐日补 0、设备增长按日补 0")
    void newTrendsShouldPivotSeveritiesAndFillMissingDays() {
        LocalDate today = LocalDate.now();
        when(alertMapper.selectMaps(any())).thenAnswer(invocation -> {
            if (sqlSelectOf(invocation.getArgument(0)).contains("DATE(create_time)")) {
                return List.of(
                    row("day", today.minusDays(2).toString(), "severity", "CRITICAL", "total", 3L),
                    row("day", today.minusDays(2).toString(), "severity", "WARNING", "total", 1L),
                    row("day", today.toString(), "severity", "INFO", "total", 5L));
            }
            return List.of();
        });
        when(deviceMapper.selectMaps(any())).thenAnswer(invocation -> {
            if (sqlSelectOf(invocation.getArgument(0)).contains("DATE(create_time)")) {
                return List.of(row("day", today.minusDays(1).toString(), "total", 4L));
            }
            return List.of();
        });

        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getAlertTrend()).hasSize(30);
        assertThat(resp.getAlertTrend().get(0).getDate()).isEqualTo(today.minusDays(29).toString());
        assertThat(resp.getAlertTrend().get(29).getDate()).isEqualTo(today.toString());
        AlertTrendPoint twoDaysAgo = alertPointAt(resp, today.minusDays(2));
        assertThat(twoDaysAgo.getCritical()).isEqualTo(3);
        assertThat(twoDaysAgo.getWarning()).isEqualTo(1);
        assertThat(twoDaysAgo.getInfo()).isZero();
        AlertTrendPoint todayPoint = alertPointAt(resp, today);
        assertThat(todayPoint.getCritical()).isZero();
        assertThat(todayPoint.getWarning()).isZero();
        assertThat(todayPoint.getInfo()).isEqualTo(5);
        AlertTrendPoint missingDay = alertPointAt(resp, today.minusDays(10));
        assertThat(missingDay.getCritical()).isZero();
        assertThat(missingDay.getWarning()).isZero();
        assertThat(missingDay.getInfo()).isZero();

        assertThat(resp.getDeviceGrowthTrend()).hasSize(14);
        assertThat(resp.getDeviceGrowthTrend().get(0).getDate()).isEqualTo(today.minusDays(13).toString());
        assertThat(resp.getDeviceGrowthTrend().get(13).getDate()).isEqualTo(today.toString());
        assertThat(growthAt(resp, today.minusDays(1)).getAdded()).isEqualTo(4);
        assertThat(growthAt(resp, today).getAdded()).isZero();
        assertThat(growthAt(resp, today.minusDays(5)).getAdded()).isZero();
    }

    @Test
    @DisplayName("规则 TOP：近 30 天告警数降序、名称一次批量解析、缺失回落规则 ID")
    void alertRuleTopShouldSortAndResolveRuleNames() {
        when(alertMapper.selectMaps(any())).thenAnswer(invocation -> {
            if (sqlSelectOf(invocation.getArgument(0)).contains("rule_id")) {
                return List.of(
                    row("rule_id", RULE_ID, "alert_count", 5L),
                    row("rule_id", RULE_ID_2, "alert_count", 9L));
            }
            return List.of();
        });
        when(ruleMapper.selectByIds(any())).thenReturn(List.of(rule(RULE_ID_2, "温度过高")));

        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getAlertRuleTop()).hasSize(2);
        assertThat(resp.getAlertRuleTop().get(0).getName()).isEqualTo("温度过高");
        assertThat(resp.getAlertRuleTop().get(0).getValue()).isEqualTo(9);
        assertThat(resp.getAlertRuleTop().get(1).getName()).as("规则不可见时回落规则 ID").isEqualTo(
            String.valueOf(RULE_ID));
        assertThat(resp.getAlertRuleTop().get(1).getValue()).isEqualTo(5);
        // 一次批量（不是每条规则一查）
        verify(ruleMapper).selectByIds(argThat(ids -> ids.size() == 2
            && ids.contains(RULE_ID) && ids.contains(RULE_ID_2)));
    }

    @Test
    @DisplayName("趋势合并：缺日补 0、上下行按日对齐、别名大小写与下划线不敏感")
    void messageTrendShouldMergeDirectionsAndTolerateAliasCase() {
        LocalDate today = LocalDate.now();
        when(receiptMapper.selectMaps(any())).thenAnswer(invocation -> {
            if (sqlSelectOf(invocation.getArgument(0)).contains("DATE(create_time)")) {
                return List.of(row("DAY", today.minusDays(1).toString(), "TOTAL", 11L));
            }
            return List.of(row("TOTAL", 50L));
        });
        when(commandMapper.selectCount(any())).thenReturn(3L);
        when(commandMapper.selectMaps(any())).thenReturn(List.of(
            row("day", today.minusDays(1).toString(), "total", 7L),
            row("day", today.toString(), "total", 3L)));

        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getMessageUplinkToday()).isEqualTo(50);
        assertThat(resp.getMessageDownlinkToday()).isEqualTo(3);
        assertThat(resp.getMessageToday()).isEqualTo(53);
        List<MessageTrendPoint> trend = resp.getMessageTrend();
        assertThat(trend).hasSize(30);
        assertThat(trend.get(0).getDate()).isEqualTo(today.minusDays(29).toString());
        assertThat(trend.get(29).getDate()).isEqualTo(today.toString());
        assertThat(trend.get(28).getUplink()).isEqualTo(11);
        assertThat(trend.get(28).getDownlink()).isEqualTo(7);
        assertThat(trend.get(27).getUplink()).isZero();
        assertThat(trend.get(27).getDownlink()).isZero();
        assertThat(trend.get(29).getUplink()).isZero();
        assertThat(trend.get(29).getDownlink()).isEqualTo(3);
    }

    @Test
    @DisplayName("最近告警：设备/规则名各一次批量查询、缺口回落 null、断档规则 0 不参与解析")
    void recentAlertsShouldResolveNamesInBatch() {
        IotAlertInstance known = alert(9001L, DEVICE_ID_1, RULE_ID, "CRITICAL", "FIRING",
            LocalDateTime.of(2026, 10, 14, 10, 30), LocalDateTime.of(2026, 10, 14, 10, 0));
        IotAlertInstance outage = alert(9002L, DEVICE_ID_2, 0L, "WARNING", "RESOLVED",
            null, LocalDateTime.of(2026, 10, 14, 9, 5));
        IotAlertInstance unknown = alert(9003L, 9999L, RULE_ID, "INFO", "FIRING",
            LocalDateTime.of(2026, 10, 14, 8, 0), LocalDateTime.of(2026, 10, 14, 8, 0));
        when(alertMapper.selectList(any())).thenReturn(List.of(known, outage, unknown));
        when(deviceMapper.selectByIds(any())).thenReturn(List.of(device(DEVICE_ID_1, "电表-A3-0172")));
        when(ruleMapper.selectByIds(any())).thenReturn(List.of(rule(RULE_ID, "电流超上限")));

        List<RecentAlert> alerts = service.overview().getRecentAlerts();

        assertThat(alerts).hasSize(3);
        assertThat(alerts.get(0).getDeviceName()).isEqualTo("电表-A3-0172");
        assertThat(alerts.get(0).getRuleName()).isEqualTo("电流超上限");
        assertThat(alerts.get(0).getSeverity()).as("总览页 i18n key 用小写码").isEqualTo("critical");
        assertThat(alerts.get(0).getState()).as("总览页 i18n key 用小写码").isEqualTo("firing");
        assertThat(alerts.get(1).getRuleName()).isNull();
        assertThat(alerts.get(1).getTriggeredAt()).isEqualTo("2026-10-14 09:05");
        assertThat(alerts.get(2).getDeviceName()).as("设备不可见时回落 null，不编造名称").isNull();

        // 一次批量（不是每行一查）：设备去重后 3 个、规则去重后仅 1 个（0 被排除）
        verify(deviceMapper).selectByIds(argThat(ids -> ids.size() == 3
            && ids.contains(DEVICE_ID_1) && ids.contains(DEVICE_ID_2) && ids.contains(9999L)));
        verify(ruleMapper).selectByIds(argThat(ids -> ids.size() == 1 && ids.contains(RULE_ID)));
    }

    @Test
    @DisplayName("产品 TOP：按设备数降序，产品不可见时名称回落产品 ID（可见兜底、不编造）")
    void productTopShouldSortByDeviceCountAndFallBackToProductId() {
        when(deviceMapper.selectMaps(any())).thenAnswer(invocation -> {
            String select = sqlSelectOf(invocation.getArgument(0));
            if (select.contains("DATE(create_time)")) {
                return List.of();
            }
            if (select.contains("protocol")) {
                return List.of();
            }
            return List.of(
                row("product_id", UNKNOWN_PRODUCT_ID, "device_count", 10L),
                row("product_id", PRODUCT_ID, "device_count", 20L));
        });
        when(productMapper.selectByIds(any())).thenReturn(List.of(product(PRODUCT_ID, "智能电表")));

        DashboardOverviewResp resp = service.overview();

        assertThat(resp.getProductTop()).hasSize(2);
        assertThat(resp.getProductTop().get(0).getName()).isEqualTo("智能电表");
        assertThat(resp.getProductTop().get(0).getValue()).isEqualTo(20);
        assertThat(resp.getProductTop().get(1).getName()).isEqualTo(String.valueOf(UNKNOWN_PRODUCT_ID));
        assertThat(resp.getProductTop().get(1).getValue()).isEqualTo(10);
    }

    /**
     * 按 SQL 条件片段区分同一 Mapper 上的多个 {@code selectCount}：返回非空 Long 以避免 NPE。
     */
    private void stubDeviceCounts(long total, long online, long offline, long disabled, long newDevices) {
        when(deviceMapper.selectCount(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            String segment = wrapper.getSqlSegment();
            if (segment.contains("online_status")) {
                return wrapper.getParamNameValuePairs().containsValue(OnlineStatus.ONLINE.getCode())
                    ? online : offline;
            }
            if (segment.contains("create_time")) {
                return newDevices;
            }
            if (segment.contains("status")) {
                return disabled;
            }
            return total;
        });
    }

    /**
     * 按 SQL 条件片段区分三个告警计数：未恢复 / 今日新增 / 今日恢复。
     */
    private void stubAlertCounts(long pending, long today, long resolvedToday) {
        when(alertMapper.selectCount(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            String segment = wrapper.getSqlSegment();
            if (segment.contains("resolved_ts IS NULL")) {
                return pending;
            }
            if (segment.contains("resolved_ts")) {
                return resolvedToday;
            }
            return today;
        });
    }

    /** 取 {@code QueryWrapper.select(...)} 的列清单（区分同一 Mapper 上的多个分组查询）。 */
    private static String sqlSelectOf(Object wrapper) {
        return wrapper instanceof QueryWrapper<?> query ? String.valueOf(query.getSqlSelect()) : "";
    }

    /** 构造 selectMaps 返回行（可空值，不能用 Map.of）。 */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            row.put((String) keyValues[index], keyValues[index + 1]);
        }
        return row;
    }

    /** 告警趋势中指定日期的点。 */
    private static AlertTrendPoint alertPointAt(DashboardOverviewResp resp, LocalDate day) {
        return resp.getAlertTrend().stream()
            .filter(point -> day.toString().equals(point.getDate()))
            .findFirst()
            .orElseThrow();
    }

    /** 设备增长趋势中指定日期的点。 */
    private static DeviceGrowthTrendPoint growthAt(DashboardOverviewResp resp, LocalDate day) {
        return resp.getDeviceGrowthTrend().stream()
            .filter(point -> day.toString().equals(point.getDate()))
            .findFirst()
            .orElseThrow();
    }

    /** 趋势中指定日期的点。 */
    private static MessageTrendPoint pointAt(DashboardOverviewResp resp, LocalDate day) {
        return resp.getMessageTrend().stream()
            .filter(point -> day.toString().equals(point.getDate()))
            .findFirst()
            .orElseThrow();
    }

    private static IotDashboardDailySnapshot snapshot(LocalDate date, int online, int offline) {
        IotDashboardDailySnapshot snapshot = new IotDashboardDailySnapshot();
        snapshot.setSnapshotDate(date);
        snapshot.setDeviceOnline(online);
        snapshot.setDeviceOffline(offline);
        return snapshot;
    }

    private static IotDevice device(long id, String deviceName) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setDeviceName(deviceName);
        return device;
    }

    private static IotProduct product(long id, String productName) {
        IotProduct product = new IotProduct();
        product.setId(id);
        product.setProductName(productName);
        return product;
    }

    private static IotAlertRule rule(long id, String ruleName) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(id);
        rule.setRuleName(ruleName);
        return rule;
    }

    private static IotAlertInstance alert(long id, Long deviceId, Long ruleId, String severity, String state,
                                          LocalDateTime startTs, LocalDateTime createTime) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(id);
        instance.setDeviceId(deviceId);
        instance.setRuleId(ruleId);
        instance.setSeverity(severity);
        instance.setState(state);
        instance.setStartTs(startTs);
        instance.setCreateTime(createTime);
        return instance;
    }
}
