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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.alert.AlertGate;
import cn.ypbin.admin.iot.alert.AlertProperties;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRulePointMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.req.AlertRulePointReq;
import cn.ypbin.admin.iot.model.req.AlertRuleSaveReq;
import cn.ypbin.admin.iot.model.resp.AlertPresetResp;
import cn.ypbin.admin.iot.model.resp.AlertRuleResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.starter.core.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则保存校验的用例（设计 §3.5-N3：「作用域组合非法时明确报错，不静默按最宽处理」+ 用户口径 ⑤「人话报错」）。
 *
 * <p>这些用例的价值在于把「非法配置」挡在产生告警之前：作用域组合写错（例如设备级却填了产品）若被静默
 * 按最宽处理，会变成「一条本该只管一台设备的规则，作用到整个产品下的全部设备」——那是**误伤面扩大**，
 * 而且不会有人发现。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertRuleServiceImplTest {

    private IotAlertRuleMapper ruleMapper;

    private IotAlertRulePointMapper pointMapper;

    private IotAlertInstanceMapper instanceMapper;

    private IotDeviceMapper deviceMapper;

    private AlertInstanceService alertInstanceService;

    private IotProductMapper productMapper;

    private final AtomicReference<IotAlertRule> stored = new AtomicReference<>();

    private AlertRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(IotAlertRuleMapper.class);
        pointMapper = mock(IotAlertRulePointMapper.class);
        instanceMapper = mock(IotAlertInstanceMapper.class);
        deviceMapper = mock(IotDeviceMapper.class);
        alertInstanceService = mock(AlertInstanceService.class);
        productMapper = mock(IotProductMapper.class);
        AlertProperties properties = new AlertProperties();
        // 默认：设备 9 属于产品 77 且都存在（各用例按需覆盖成「不存在 / 产品不匹配」）
        when(deviceMapper.selectById(9L)).thenReturn(device(9L, 77L));
        when(productMapper.selectById(77L)).thenReturn(product(77L));
        when(ruleMapper.insert(any(IotAlertRule.class))).thenAnswer(invocation -> {
            IotAlertRule rule = invocation.getArgument(0);
            rule.setId(123L);
            rule.setTenantId(1L);
            stored.set(rule);
            return 1;
        });
        when(ruleMapper.selectById(123L)).thenAnswer(invocation -> stored.get());
        when(pointMapper.selectByRuleIds(anyList())).thenReturn(List.of());
        when(instanceMapper.countActiveByRuleIds(anyList())).thenReturn(List.of());
        service = new AlertRuleServiceImpl(ruleMapper, pointMapper, instanceMapper, deviceMapper,
            productMapper, alertInstanceService, new AlertGate(properties), properties);
    }

    private static AlertRuleSaveReq request(String scopeType) {
        AlertRuleSaveReq req = new AlertRuleSaveReq();
        req.setRuleName("演示规则");
        req.setScopeType(scopeType);
        return req;
    }

    private static AlertRulePointReq pointReq() {
        AlertRulePointReq point = new AlertRulePointReq();
        point.setPropertyId("temperature");
        point.setOperator("GT");
        point.setThreshold("80");
        return point;
    }

    private static IotProduct product(Long id) {
        IotProduct product = new IotProduct();
        product.setId(id);
        product.setTenantId(1L);
        product.setProductName("演示产品");
        return product;
    }

    private static IotDevice device(Long id, Long productId) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(1L);
        device.setProductId(productId);
        device.setDeviceName("演示设备");
        return device;
    }

    @Test
    @DisplayName("N3 设备级规则填了产品 ⇒ 明确报错（不静默按最宽处理）")
    void deviceScopeMustNotCarryProduct() {
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        req.setScopeProductId(77L);
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备级规则不能指定产品");
    }

    @Test
    @DisplayName("N3 租户级规则填了设备 ⇒ 明确报错（那会把作用域悄悄缩小）")
    void tenantScopeMustNotCarryDevice() {
        AlertRuleSaveReq req = request("TENANT");
        req.setScopeDeviceId(9L);
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("租户级规则不能指定设备");
    }

    @Test
    @DisplayName("N3 产品级缺产品 / 点位级缺设备 ⇒ 明确报错")
    void missingRequiredScopeFieldFails() {
        assertThatThrownBy(() -> service.create(request("PRODUCT")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("产品级规则必须选择产品");
        AlertRuleSaveReq pointScope = request("POINT");
        pointScope.setScopeProductId(77L);
        assertThatThrownBy(() -> service.create(pointScope))
            .isInstanceOf(BusinessException.class).hasMessageContaining("点位级规则必须选择设备");
    }

    @Test
    @DisplayName("点位级规则必须有点位条件（否则应改用设备级模板）")
    void pointScopeRequiresPointCondition() {
        AlertRuleSaveReq req = request("POINT");
        req.setScopeProductId(77L);
        req.setScopeDeviceId(9L);
        req.setPoints(List.of());
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("点位级规则必须至少配置一个点位条件");
    }

    @Test
    @DisplayName("阈值不是数字 ⇒ 人话报错（不弹原始异常码）")
    void thresholdMustBeNumeric() {
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        AlertRulePointReq point = pointReq();
        point.setThreshold("八十");
        req.setPoints(List.of(point));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("阈值必须是数字")
            .hasMessageContaining("八十");
    }

    @Test
    @DisplayName("布尔点位配大小比较 ⇒ 明确报错（不做 true 当 1 的隐式转换）")
    void booleanPointRejectsComparisonOperators() {
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        AlertRulePointReq point = pointReq();
        point.setValueType("BOOLEAN");
        point.setOperator("GT");
        point.setThreshold("1");
        req.setPoints(List.of(point));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("布尔点位只支持「等于 / 不等于」");

        point.setOperator("EQ");
        point.setThreshold("2");
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("布尔点位的阈值只能是 1（真）或 0（假）");
    }

    @Test
    @DisplayName("比较符非法 / 作用域非法 / 渠道非法 ⇒ 各自的人话报错")
    void invalidEnumsFailWithHumanMessages() {
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        req.setScopeType("SOMETHING");
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("作用域只能是");

        AlertRuleSaveReq operatorReq = request("DEVICE");
        operatorReq.setScopeDeviceId(9L);
        AlertRulePointReq point = pointReq();
        point.setOperator("===");
        operatorReq.setPoints(List.of(point));
        assertThatThrownBy(() -> service.create(operatorReq)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("比较符只能是");

        AlertRuleSaveReq channelReq = request("DEVICE");
        channelReq.setScopeDeviceId(9L);
        channelReq.setNotifyChannels("INBOX,WEBHOOK");
        assertThatThrownBy(() -> service.create(channelReq)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("Webhook 本期不做");
    }

    @Test
    @DisplayName("N4 设备不存在或不属于当前租户 ⇒ 明确拒绝（租户插件已过滤，跨租户设备表现为「不存在」）")
    void crossTenantDeviceIsRejected() {
        when(deviceMapper.selectById(9L)).thenReturn(null);
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在或不属于当前租户");
    }

    @Test
    @DisplayName("设备与产品不匹配 ⇒ 明确报错（避免「选了 A 产品的设备却配 B 产品的点位」）")
    void deviceProductMismatchFails() {
        when(deviceMapper.selectById(9L)).thenReturn(device(9L, 88L));
        AlertRuleSaveReq req = request("POINT");
        req.setScopeDeviceId(9L);
        req.setScopeProductId(77L);
        req.setPoints(List.of(pointReq()));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("所选设备不属于所选产品");
    }

    @Test
    @DisplayName("静默窗口起止颠倒 ⇒ 明确报错")
    void invalidSilenceWindowFails() {
        when(deviceMapper.selectById(9L)).thenReturn(device(9L, 77L));
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        req.setSilenceStart(java.time.LocalDateTime.of(2026, 10, 3, 12, 0));
        req.setSilenceEnd(java.time.LocalDateTime.of(2026, 10, 3, 10, 0));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("静默结束时间不能早于开始时间");
    }

    @Test
    @DisplayName("合法规则：默认值补齐（级别警告/渠道站内信+邮件/连续 3 次）并批量写条件行")
    void createFillsReasonableDefaults() {
        when(deviceMapper.selectById(9L)).thenReturn(device(9L, 77L));
        AlertRuleSaveReq req = request("DEVICE");
        req.setScopeDeviceId(9L);
        req.setPoints(List.of(pointReq()));

        AlertRuleResp resp = service.create(req);

        assertThat(stored.get().getSeverity()).isEqualTo("WARNING");
        assertThat(stored.get().getNotifyChannels()).isEqualTo("INBOX,EMAIL");
        assertThat(stored.get().getTriggerMode()).isEqualTo("CONSECUTIVE_COUNT");
        assertThat(stored.get().getTriggerThreshold()).isEqualTo(3);
        // 用户口径「静默 10 分钟」⇒ 默认重复通知 600s（设计 §2.4 的 1800s 被用户口径覆盖，见文档 §7.5）
        assertThat(stored.get().getRepeatIntervalSec()).isEqualTo(600);
        assertThat(stored.get().getPendingTtlSec()).isEqualTo(300);
        assertThat(stored.get().getEnabled()).isTrue();
        // 条件行**一次批量插入**（不在循环里逐条写）
        verify(pointMapper, times(1)).insertBatch(anyList());
        assertThat(resp.getId()).isEqualTo(123L);
        assertThat(resp.getScopeType()).isEqualTo("DEVICE");
    }

    @Test
    @DisplayName("停用规则会收口其活动实例（否则留下永不消解的幽灵告警）")
    void disableResolvesActiveInstances() {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(123L);
        rule.setEnabled(true);
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(rule));
        when(ruleMapper.batchSetEnabled(anyList(), anyBoolean(), any())).thenReturn(1);
        when(alertInstanceService.resolveByRuleIds(anyList())).thenReturn(2);

        int changed = service.setEnabled(List.of(123L), false);

        assertThat(changed).isEqualTo(1);
        // 批量更新是**一条语句**（不是循环里逐条 updateById），且只带「真正需要变更」的 id
        verify(ruleMapper, times(1)).batchSetEnabled(List.of(123L), false, null);
        verify(alertInstanceService, times(1)).resolveByRuleIds(List.of(123L));
    }

    @Test
    @DisplayName("启用规则不会触发收口；空 ID 列表直接返回 0（禁止 IN () 与「无参数即全量」）")
    void enableDoesNotResolveAndEmptyIdsIsSafe() {
        assertThat(service.setEnabled(List.of(), false)).isZero();
        IotAlertRule rule = new IotAlertRule();
        rule.setId(123L);
        rule.setEnabled(false);
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(rule));
        when(ruleMapper.batchSetEnabled(anyList(), anyBoolean(), any())).thenReturn(1);
        assertThat(service.setEnabled(List.of(123L), true)).isEqualTo(1);
        verify(alertInstanceService, times(0)).resolveByRuleIds(anyList());

        // 已经是目标状态 ⇒ 不做任何写入（幂等，避免无意义的 update_time 抖动）
        IotAlertRule already = new IotAlertRule();
        already.setId(123L);
        already.setEnabled(true);
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of(already));
        assertThat(service.setEnabled(List.of(123L), true)).isZero();
        verify(ruleMapper, times(1)).batchSetEnabled(anyList(), anyBoolean(), any());
    }

    @Test
    @DisplayName("一键预设模板：至少四类，且默认值开箱可用（连续 3 次、警告、站内信+邮件）")
    void presetsCoverFourKinds() {
        List<AlertPresetResp> presets = service.presets();
        assertThat(presets).extracting(AlertPresetResp::getCode)
            .containsExactly("POINT_ABOVE_UPPER", "POINT_BELOW_LOWER", "DEVICE_OFFLINE", "DATA_INTERRUPT");
        AlertPresetResp above = presets.get(0);
        assertThat(above.getNeedsPointCondition()).isTrue();
        assertThat(above.getDefaultOperator()).isEqualTo("GT");
        assertThat(above.getDefaultScopeType()).isEqualTo("POINT");
        assertThat(above.getDefaultTriggerThreshold()).isEqualTo(3);
        assertThat(above.getDefaultSeverity()).isEqualTo("WARNING");
        assertThat(above.getDefaultNotifyChannels()).isEqualTo("INBOX,EMAIL");
        AlertPresetResp offline = presets.get(2);
        assertThat(offline.getNeedsPointCondition()).isFalse();
        assertThat(offline.getDefaultOperator()).isNull();
        assertThat(offline.getDefaultScopeType()).isEqualTo("DEVICE");
        assertThat(offline.getDefaultSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("总开关关闭时规则接口也返回失败（不能返回空列表）")
    void disabledGateFailsRuleEndpoints() {
        AlertProperties disabled = new AlertProperties();
        disabled.setEnabled(false);
        AlertRuleServiceImpl gated = new AlertRuleServiceImpl(ruleMapper, pointMapper, instanceMapper,
            deviceMapper, productMapper, alertInstanceService, new AlertGate(disabled), disabled);
        assertThatThrownBy(gated::presets).isInstanceOf(BusinessException.class)
            .hasMessageContaining("告警能力未启用");
    }
}
