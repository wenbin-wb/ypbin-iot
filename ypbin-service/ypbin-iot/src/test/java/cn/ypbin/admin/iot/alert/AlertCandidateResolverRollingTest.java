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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.enums.AlertValueType;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「单轮设备上限 + 跨轮滚动」的用例（设计 §2.2.2）。
 *
 * <p>独立复核（2026-10-03）把「固定取第 1 页」判为阻断项：设备数超过上限时，ID 较大的设备
 * **永远不会被评估**——那是静默漏报。本用例用「3 台设备 + 每轮 2 台」的最小场景证明游标真的在滚动：
 * 任意连续若干轮内，全部设备都会被覆盖到。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertCandidateResolverRollingTest {

    static {
        // LambdaQueryWrapper 的列名解析依赖 MyBatis-Plus 的实体元数据（纯单测里没有容器）
        AlertMybatisTestSupport.initMetadata(IotDevice.class);
    }

    private static final int PAGE_SIZE = 2;

    private IotDeviceMapper deviceMapper;

    private AlertDeviceCursor cursor;

    private AlertCandidateResolver resolver;

    private final List<IotDevice> allDevices = List.of(device(1L), device(2L), device(3L));

    @BeforeEach
    void setUp() {
        deviceMapper = mock(IotDeviceMapper.class);
        cursor = new AlertDeviceCursor();
        resolver = new AlertCandidateResolver(deviceMapper, cursor);
        // 按请求页号返回对应页（total = 3），模拟真实分页
        when(deviceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotDevice> request = invocation.getArgument(0);
            long pageNo = request.getCurrent();
            int from = (int) Math.min((pageNo - 1) * PAGE_SIZE, allDevices.size());
            int to = Math.min(from + PAGE_SIZE, allDevices.size());
            Page<IotDevice> page = new Page<>(pageNo, PAGE_SIZE);
            page.setRecords(allDevices.subList(from, to));
            page.setTotal(allDevices.size());
            return page;
        });
    }

    private static IotDevice device(Long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(1L);
        device.setProductId(77L);
        device.setDeviceName("设备 " + id);
        return device;
    }

    private static IotAlertRule tenantRule(Long id) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(id);
        rule.setTenantId(1L);
        rule.setRuleName("租户级规则");
        rule.setScopeType(AlertScopeType.TENANT.getCode());
        rule.setSeverity("WARNING");
        rule.setEnabled(true);
        rule.setTriggerMode("IMMEDIATE");
        rule.setTriggerThreshold(0);
        rule.setNotifyChannels("INBOX");
        return rule;
    }

    private static IotAlertRulePoint point() {
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setId(11L);
        point.setPropertyId("temperature");
        point.setOperator(AlertOperator.GT.getCode());
        point.setThreshold(new BigDecimal("30"));
        point.setValueType(AlertValueType.NUMERIC.getCode());
        return point;
    }

    private Set<Long> resolveOnce() {
        IotAlertRule rule = tenantRule(7L);
        AlertCandidateResolver.Resolved resolved = resolver.resolve(List.of(rule),
            Map.of(7L, List.of(point())), PAGE_SIZE);
        return new LinkedHashSet<>(resolved.devices().keySet());
    }

    @Test
    @DisplayName("★ 跨轮滚动：3 台设备、每轮 2 台 ⇒ 两轮即覆盖全部设备（无设备被永久漏掉）")
    void cursorCoversAllDevicesAcrossRounds() {
        assertThat(resolveOnce()).containsExactly(1L, 2L);
        assertThat(resolveOnce()).containsExactly(3L);
        // 第三轮回到第一页（取不满即回卷）
        assertThat(resolveOnce()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("滚动覆盖证明：连续 N 轮（N = 总页数）内并集 = 全部设备")
    void unionOfRoundsCoversEverything() {
        Set<Long> union = new LinkedHashSet<>();
        for (int round = 0; round < 2; round++) {
            union.addAll(resolveOnce());
        }
        assertThat(union).containsExactlyInAnyOrderElementsOf(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("截断标记语义：**本页之后还有数据**才算未覆盖（最后一页不得再报 truncated）")
    void truncatedFlagReflectsRolling() {
        IotAlertRule rule = tenantRule(7L);
        // 第 1 页（2/3）⇒ 后面还有 1 台 ⇒ truncated=true
        AlertCandidateResolver.Resolved first = resolver.resolve(List.of(rule),
            Map.of(7L, List.of(point())), PAGE_SIZE);
        assertThat(first.truncated()).isTrue();
        // 第 2 页（最后 1 台）⇒ 本页之后没有数据 ⇒ truncated=false。
        // 早期实现写成「总数 > 本页条数」，在最后一页也返回 true ⇒ 滚动成为常态时每轮都记指标 + 打 WARN
        // （独立复核 2026-10-03 M7）
        AlertCandidateResolver.Resolved second = resolver.resolve(List.of(rule),
            Map.of(7L, List.of(point())), PAGE_SIZE);
        assertThat(second.truncated()).isFalse();
    }

    @Test
    @DisplayName("数据集变小/取空 ⇒ 游标回到第一页（不会在一张不存在的页上永久空转）")
    void emptyPageFallsBackToFirstPage() {
        AlertDeviceCursor standalone = new AlertDeviceCursor();
        String key = "TENANT:POINT:";
        // 连续取满两轮 ⇒ 页号推进到 3
        standalone.advance(key, PAGE_SIZE, PAGE_SIZE);
        standalone.advance(key, PAGE_SIZE, PAGE_SIZE);
        assertThat(standalone.currentPage(key)).isEqualTo(3);
        // 取空 ⇒ 回到第一页
        standalone.advance(key, 0, PAGE_SIZE);
        assertThat(standalone.currentPage(key)).isEqualTo(1);
        // 取不满（已到末尾）⇒ 同样回到第一页
        standalone.advance(key, PAGE_SIZE, PAGE_SIZE);
        assertThat(standalone.currentPage(key)).isEqualTo(2);
        standalone.advance(key, 1, PAGE_SIZE);
        assertThat(standalone.currentPage(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("游标按「形状 + 用途 + 作用域指纹」隔离：评估与断档映射互不推走对方的页")
    void cursorsAreIsolatedPerPurpose() {
        IotAlertRule rule = tenantRule(7L);
        Map<Long, List<IotAlertRulePoint>> points = Map.of(7L, List.of(point()));
        // 评估用途取一页
        Set<Long> pointRound = new LinkedHashSet<>(resolver
            .resolve(List.of(rule), points, PAGE_SIZE).devices().keySet());
        // 断档用途独立游标：仍从第一页开始
        Set<Long> outageRound = new LinkedHashSet<>(
            resolver.resolveDeviceScope(List.of(rule), PAGE_SIZE).devices().keySet());
        assertThat(pointRound).containsExactly(1L, 2L);
        assertThat(outageRound).containsExactly(1L, 2L);
        assertThat(cursor.snapshot()).hasSize(2);
    }

    @Test
    @DisplayName("游标键数量上限触发清空后仍能继续工作（不会因为清空而漏报，只影响一轮覆盖面）")
    void cursorSurvivesClear() {
        resolveOnce();
        cursor.clear();
        assertThat(cursor.currentPage("TENANT:POINT:x")).isEqualTo(1);
        assertThat(resolveOnce()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("无规则 ⇒ 不查库也不推游标")
    void emptyRulesShortCircuit() {
        assertThat(resolver.resolve(List.of(), Map.of(), PAGE_SIZE).devices()).isEmpty();
        assertThat(resolver.resolveDeviceScope(List.of(), PAGE_SIZE).devices()).isEmpty();
        assertThat(cursor.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("断档作用域解析取最具体规则：DEVICE 优先于 TENANT（同具体时取 id 最小，保证确定性）")
    void resolveDeviceScopePrefersMostSpecific() {
        IotAlertRule tenant = tenantRule(7L);
        IotAlertRule device = tenantRule(8L);
        device.setScopeType(AlertScopeType.DEVICE.getCode());
        device.setScopeDeviceId(1L);
        AlertCandidateResolver.DeviceScope scope = resolver.resolveDeviceScope(List.of(tenant, device),
            PAGE_SIZE);
        assertThat(scope.ruleByDevice().get(1L).getId()).isEqualTo(8L);
        assertThat(scope.ruleByDevice().get(2L).getId()).isEqualTo(7L);
    }
}
