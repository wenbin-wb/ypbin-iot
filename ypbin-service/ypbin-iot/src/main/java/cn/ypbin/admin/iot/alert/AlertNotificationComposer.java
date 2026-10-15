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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 通知文案的**唯一生成处**（设计 §2.4「通知内容（人读的那一句）」）。
 *
 * <p>必须包含五件事，缺一不可（设计原文）：**谁**（设备名 + 点位）、**什么**（比较符 + 阈值，读到多少）、
 * **什么时候**（首次越界时刻、正式触发时刻）、**级别**、以及**一个能点进去的入口**（设备详情的告警页签）。</p>
 *
 * <p><b>值原样展示</b>：设计明确要求「通知里的值原样展示原始值（不美化、不四舍五入到看不出差别）」——
 * 所以触发值直接取 {@code trigger_value} 原文，不做数字格式化。</p>
 *
 * <p><b>条件组（真 AND，2026-10-15）</b>：一条实例代表整个条件组，故多条件时正文**逐条件**列出
 * 「阈值 vs 读到多少 / 各自判成什么」，数据来自实例的 {@code condition_snapshot}（触发当时的留痕）。
 * 单条件规则的文案与改造前逐字一致。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertNotificationComposer {

    /** 时间展示格式（与本仓页面一致，秒级足够）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 告警中心入口（前端路由；带 deviceId 便于直接定位到该设备）。 */
    private static final String ALERT_CENTER_PATH = "/iot/alerts";

    /**
     * 一条通知的文本（标题 + 正文）。
     *
     * @param title   标题（站内信标题/邮件主题）
     * @param content 正文（纯文本多行）
     */
    public record AlertNotificationText(String title, String content) {
    }

    /**
     * 组装通知文本（**无条件行上下文**：正文只写实例上落下的最严重条件）。
     *
     * @param instance 实例
     * @param device   设备（可空：设备被删或跨租户查不到时只描述 ID）
     * @param rule     规则（可空：断档类可能只有覆盖规则）
     * @param event    通知事件
     * @return 文本（标题与正文都不为空）
     */
    public AlertNotificationText compose(IotAlertInstance instance, IotDevice device, IotAlertRule rule,
                                         AlertNotifyEvent event) {
        return compose(instance, device, rule, event, List.of());
    }

    /**
     * 组装通知文本（**带规则当前条件行**：AND 语义下逐条件列出「阈值 vs 读到多少」）。
     *
     * <p><b>为什么正文要列全部条件</b>：真 AND 之后一条实例代表整个条件组，只说「temperature &gt; 30」
     * 无法解释「为什么另一个条件也越界了」。逐条件列出的是**实例上落下的快照**
     * （{@code condition_snapshot}，含各条件当时各自判成什么），规则当前条件行只用于补齐快照缺失的
     * 重发/确认场景。两者都不改变「值原样展示」的既有口径。</p>
     *
     * @param instance 实例
     * @param device   设备
     * @param rule     规则（可空）
     * @param event    通知事件
     * @param points   规则当前的条件行（可为空：断档类/规则已删时就没有）
     * @return 文本（标题与正文都不为空）
     */
    public AlertNotificationText compose(IotAlertInstance instance, IotDevice device, IotAlertRule rule,
                                         AlertNotifyEvent event, List<IotAlertRulePoint> points) {
        boolean outage = instance.getRuleId() != null
            && instance.getRuleId() == AlertRules.RULE_ID_OUTAGE;
        String deviceLabel = deviceLabel(instance, device);
        String target = outage ? "设备上报中断" : groupTarget(instance, points);
        String severity = severityLabel(instance.getSeverity());
        String title = "【" + eventTitle(event, severity) + "】" + deviceLabel + " " + target;

        StringBuilder content = new StringBuilder(256);
        content.append("级别：").append(severity).append('\n');
        content.append("设备：").append(deviceLabel);
        if (device != null && device.getDeviceCode() != null
            && !device.getDeviceCode().isBlank()) {
            content.append("（").append(device.getDeviceCode()).append('）');
        }
        content.append('\n');
        content.append("对象：").append(target).append('\n');
        if (!outage) {
            List<String> conditions = conditionLines(instance, points);
            if (conditions.size() <= 1) {
                // 单条件：沿用既有文案形态（阈值快照优先，规则改了阈值也不影响「当时为何报警」）
                content.append("条件：").append(conditionText(instance)).append('\n');
                content.append("触发值：").append(orDash(instance.getTriggerValue())).append('\n');
            } else {
                // 多条件（真 AND）：逐条件列出，值原样展示（不美化、不四舍五入）
                content.append("条件组（全部满足才触发，共 ").append(conditions.size()).append(" 条）：\n");
                for (String line : conditions) {
                    content.append("  · ").append(line).append('\n');
                }
            }
        } else {
            content.append("原因：").append(reasonText(instance)).append('\n');
        }
        content.append("首次越界：").append(format(instance.getStartTs())).append('\n');
        if (instance.getFiringTs() != null) {
            content.append("正式触发：").append(format(instance.getFiringTs())).append('\n');
        }
        if (AlertState.RESOLVED.getCode().equals(instance.getState())) {
            content.append("恢复时间：").append(format(instance.getResolvedTs())).append('\n');
            content.append("恢复原因：").append(orDash(instance.getReason())).append('\n');
        } else {
            content.append("当前状态：").append(stateLabel(instance.getState())).append('\n');
        }
        content.append("规则：").append(rule == null ? "平台断档链路（未配规则）"
            : orDash(rule.getRuleName())).append('\n');
        content.append("事件：").append(eventLabel(event)).append('\n');
        content.append("查看入口：").append(ALERT_CENTER_PATH).append("?deviceId=")
            .append(instance.getDeviceId()).append("（设备详情 → 告警页签）");
        return new AlertNotificationText(title, content.toString());
    }

    /** 设备展示名：设备名 → 设备编码 → ID。 */
    private static String deviceLabel(IotAlertInstance instance, IotDevice device) {
        if (device != null) {
            if (device.getDeviceName() != null && !device.getDeviceName().isBlank()) {
                return device.getDeviceName();
            }
            if (device.getDeviceCode() != null && !device.getDeviceCode().isBlank()) {
                return device.getDeviceCode();
            }
        }
        return "设备 " + instance.getDeviceId();
    }

    /** 点位展示名（断档类实例没有点位）。 */
    private static String propertyLabel(IotAlertInstance instance) {
        return instance.getPropertyId() == null || instance.getPropertyId().isBlank()
            ? "设备整体" : instance.getPropertyId();
    }

    /**
     * 标题里的「对象」：条件组实例（真 AND）用「条件组（N 个条件）」表述，否则沿用点位名。
     *
     * <p>为什么不能只写最严重那条点位：一条实例现在代表整组，标题只说一个点位会让用户以为
     * 「只有这个点位越界了」——而恰恰相反，组内**全部**条件都越界了。</p>
     */
    private static String groupTarget(IotAlertInstance instance, List<IotAlertRulePoint> points) {
        int size = points == null ? 0 : points.size();
        if (size > 1) {
            return "条件组（" + size + " 个条件）";
        }
        return propertyLabel(instance);
    }

    /**
     * 逐条件文案：优先用实例上落下的 `condition_snapshot`（**触发当时**各条件读到多少、判成什么），
     * 快照缺失时回落到规则当前条件行 + 实例触发值（重发/确认等旧实例场景）。
     *
     * @return 每个条件一行；单条件或全无信息时可能只有一行
     */
    private static List<String> conditionLines(IotAlertInstance instance, List<IotAlertRulePoint> points) {
        List<AlertConditionSnapshot.ConditionReading> snapshot =
            AlertConditionSnapshot.decode(instance.getConditionSnapshot());
        if (!snapshot.isEmpty()) {
            List<String> lines = new ArrayList<>(snapshot.size());
            for (AlertConditionSnapshot.ConditionReading reading : snapshot) {
                lines.add(reading.describe());
            }
            return lines;
        }
        if (points != null && points.size() > 1) {
            List<String> lines = new ArrayList<>(points.size());
            for (IotAlertRulePoint point : points) {
                AlertOperator operator = AlertOperator.of(point.getOperator());
                String symbol = operator == null ? orDash(point.getOperator()) : operator.symbol();
                String threshold = point.getThreshold() == null
                    ? "-" : point.getThreshold().stripTrailingZeros().toPlainString();
                lines.add(orDash(point.getPropertyId()) + " " + symbol + " " + threshold + "，读到 -");
            }
            return lines;
        }
        return List.of();
    }

    /** 条件文本：优先用触发时的阈值快照（规则改了阈值也不影响「当时为何报警」）。 */
    private static String conditionText(IotAlertInstance instance) {
        return instance.getThresholdSnapshot() == null || instance.getThresholdSnapshot().isBlank()
            ? "（无阈值快照）" : instance.getThresholdSnapshot();
    }

    /** 断档原因：目前平台只有一种（无有效数据），但仍如实从事件侧描述。 */
    private static String reasonText(IotAlertInstance instance) {
        return instance.getTriggerValue() == null || instance.getTriggerValue().isBlank()
            ? "连续超过 K × 采集周期没有有效数据" : instance.getTriggerValue();
    }

    /** 级别文案。 */
    private static String severityLabel(String severity) {
        AlertSeverity value = AlertSeverity.of(severity);
        return value == null ? orDash(severity) : value.getDesc();
    }

    /** 状态文案。 */
    private static String stateLabel(String state) {
        AlertState value = AlertState.of(state);
        return value == null ? orDash(state) : value.getDesc();
    }

    /** 标题里的动作词：恢复事件说「已恢复」，触发类说级别。 */
    private static String eventTitle(AlertNotifyEvent event, String severity) {
        if (event == AlertNotifyEvent.RESOLVED) {
            return "已恢复";
        }
        if (event == AlertNotifyEvent.ACKED) {
            return "已确认";
        }
        return severity;
    }

    /** 事件文案。 */
    private static String eventLabel(AlertNotifyEvent event) {
        return event == null ? "-" : event.getDesc();
    }

    private static String format(LocalDateTime time) {
        return time == null ? "-" : time.format(TIME_FORMAT);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
