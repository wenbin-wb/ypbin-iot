/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.admin.iot.alert;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知文案的用例（设计 §2.4：通知必须含「谁 / 什么 / 什么时候 / 级别 / 一个能点进去的入口」，
 * 且值**原样展示**）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertNotificationComposerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private final AlertNotificationComposer composer = new AlertNotificationComposer();

    private static IotAlertInstance instance() {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(1L);
        instance.setRuleId(7L);
        instance.setDeviceId(9L);
        instance.setPropertyId("temperature");
        instance.setSeverity(AlertSeverity.CRITICAL.getCode());
        instance.setState(AlertState.FIRING.getCode());
        instance.setTriggerValue("85.5");
        instance.setThresholdSnapshot("> 80");
        instance.setStartTs(START);
        instance.setFiringTs(START.plusSeconds(30));
        return instance;
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(9L);
        device.setDeviceName("演示设备");
        device.setDeviceCode("demo-dev-curve");
        return device;
    }

    private static IotAlertRule rule() {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(7L);
        rule.setRuleName("温度超上限");
        return rule;
    }

    @Test
    @DisplayName("五要素齐备：谁 / 什么 / 什么时候 / 级别 / 入口，且触发值**原样**（85.5 不被四舍五入）")
    void containsAllRequiredElements() {
        AlertNotificationComposer.AlertNotificationText text =
            composer.compose(instance(), device(), rule(), AlertNotifyEvent.FIRING);
        assertThat(text.title()).contains("严重").contains("演示设备").contains("temperature");
        assertThat(text.content())
            .contains("级别：严重")
            .contains("设备：演示设备（demo-dev-curve）")
            .contains("对象：temperature")
            .contains("条件：> 80")
            .contains("触发值：85.5")
            .contains("首次越界：2026-10-03 10:00:00")
            .contains("正式触发：2026-10-03 10:00:30")
            .contains("当前状态：已触发")
            .contains("规则：温度超上限")
            .contains("事件：首次触发")
            .contains("/iot/alerts?deviceId=9");
    }

    @Test
    @DisplayName("恢复通知：标题说「已恢复」，并给出恢复时间与原因")
    void resolvedVariant() {
        IotAlertInstance instance = instance();
        instance.setState(AlertState.RESOLVED.getCode());
        instance.setResolvedTs(START.plusMinutes(20));
        instance.setReason(AlertReason.RECOVERED.getCode());
        AlertNotificationComposer.AlertNotificationText text =
            composer.compose(instance, device(), rule(), AlertNotifyEvent.RESOLVED);
        assertThat(text.title()).contains("已恢复");
        assertThat(text.content()).contains("恢复时间：2026-10-03 10:20:00")
            .contains("恢复原因：RECOVERED");
    }

    @Test
    @DisplayName("★ 条件组通知（真 AND）：多条件时逐条件列出「阈值 vs 读到多少」+ 各自判定，标题说条件组")
    void conditionGroupVariant() {
        IotAlertInstance instance = instance();
        instance.setPropertyId("humidity");
        instance.setTriggerValue("90");
        instance.setConditionSnapshot(AlertConditionSnapshot.encode(List.of(
            new AlertConditionSnapshot.ConditionReading("temperature", "GT", new BigDecimal("30"),
                AlertValueVerdict.OUT_OF_RANGE, "35"),
            new AlertConditionSnapshot.ConditionReading("humidity", "GT", new BigDecimal("80"),
                AlertValueVerdict.OUT_OF_RANGE, "90"))));
        IotAlertRulePoint temperature = new IotAlertRulePoint();
        temperature.setPropertyId("temperature");
        temperature.setOperator("GT");
        temperature.setThreshold(new BigDecimal("30"));
        IotAlertRulePoint humidity = new IotAlertRulePoint();
        humidity.setPropertyId("humidity");
        humidity.setOperator("GT");
        humidity.setThreshold(new BigDecimal("80"));

        AlertNotificationComposer.AlertNotificationText text = composer.compose(instance, device(),
            rule(), AlertNotifyEvent.FIRING, List.of(temperature, humidity));

        assertThat(text.title()).contains("条件组（2 个条件）");
        assertThat(text.content())
            .contains("条件组（全部满足才触发，共 2 条）：")
            .contains("temperature > 30，读到 35（越界）")
            .contains("humidity > 80，读到 90（越界）");
        // 逐条件文案里不再只写单条 trigger_value
        assertThat(text.content()).doesNotContain("触发值：90");
    }

    @Test
    @DisplayName("单条件规则的文案与真 AND 改造前**逐字一致**（向后兼容的硬要求）")
    void singleConditionKeepsLegacyText() {
        AlertNotificationComposer.AlertNotificationText text = composer.compose(instance(), device(),
            rule(), AlertNotifyEvent.FIRING, List.of());
        assertThat(text.content())
            .contains("条件：> 80")
            .contains("触发值：85.5")
            .doesNotContain("条件组");
    }

    @Test
    @DisplayName("断档类通知：没有点位与阈值，改为说明「设备上报中断」与原因")
    void outageVariant() {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(2L);
        instance.setRuleId(AlertRules.RULE_ID_OUTAGE);
        instance.setDeviceId(9L);
        instance.setSeverity(AlertSeverity.WARNING.getCode());
        instance.setState(AlertState.FIRING.getCode());
        instance.setTriggerValue("NO_GOOD_DATA");
        instance.setStartTs(START);
        AlertNotificationComposer.AlertNotificationText text =
            composer.compose(instance, device(), null, AlertNotifyEvent.FIRING);
        assertThat(text.title()).contains("设备上报中断");
        assertThat(text.content()).contains("对象：设备上报中断")
            .contains("原因：NO_GOOD_DATA")
            .contains("规则：平台断档链路（未配规则）")
            .doesNotContain("条件：");
    }

    @Test
    @DisplayName("设备查不到时退化为 ID（不抛异常、不出现 null 字样）")
    void missingDeviceFallsBackToId() {
        AlertNotificationComposer.AlertNotificationText text =
            composer.compose(instance(), null, null, AlertNotifyEvent.REPEAT);
        assertThat(text.title()).contains("设备 9");
        assertThat(text.content()).doesNotContain("null");
    }
}
