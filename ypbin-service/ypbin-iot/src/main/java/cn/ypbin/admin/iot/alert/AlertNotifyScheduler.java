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
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 通知**入队规则**的唯一实现（静默判定 + 重复通知抑制 + 通知计数）。
 *
 * <p>点评估器（{@link AlertTenantEvaluator}）与断档映射（{@code AlertTenantOutageMapper}）**都必须**走这里：
 * 两条路径各自实现一遍「什么时候该发、发了之后 {@code last_notified_ts} 怎么走」，
 * 迟早会出现「告警页签会重复提醒、断档告警不会」这类口径分叉——而那是最难排查的一类不一致。</p>
 *
 * <p>语义要点（全部来自设计 §2.3/§2.4/§3.4）：</p>
 * <ol>
 *   <li>状态变化（触发/恢复）**立即**通知一次；</li>
 *   <li>活动期内按 {@code repeat_interval_sec} 重发（{@code REPEAT} 事件）；</li>
 *   <li>{@code ACKED} 之后**不再**按 repeat_interval 打扰，只在恢复时通知一次；</li>
 *   <li>静默（实例级/规则窗口/维护窗口）只影响通知，**不影响判定与状态**；静默期间不更新
 *       {@code last_notified_ts}，因此静默结束后若已超 repeat_interval，本轮就会补一次（不补发历史）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertNotifyScheduler {

    private final AlertSilencePolicy silencePolicy;
    private final AlertNotifyPlanner notifyPlanner;
    private final AlertMetrics metrics;
    private final AlertProperties properties;

    public AlertNotifyScheduler(AlertSilencePolicy silencePolicy, AlertNotifyPlanner notifyPlanner,
                                AlertMetrics metrics, AlertProperties properties) {
        this.silencePolicy = silencePolicy;
        this.notifyPlanner = notifyPlanner;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * 触发/恢复通知的入队。
     *
     * @param instance  实例
     * @param rule      规则（可空）
     * @param deviceId  设备 ID（用于匹配维护窗口）
     * @param event     事件（触发或恢复）
     * @param now       本轮时刻
     * @param windows   当前生效的维护窗口（调用方批量加载后传入）
     * @param tasks     入队收集器
     * @return 是否成功入队（被静默时为 {@code false}，供调用方区分）
     */
    public boolean schedule(IotAlertInstance instance, IotAlertRule rule, Long deviceId,
                            AlertNotifyEvent event, LocalDateTime now, List<MaintenanceWindow> windows,
                            List<AlertNotifyTask> tasks) {
        if (silencePolicy.isSilenced(instance, rule, deviceId, now, windows)) {
            metrics.notifySilenced();
            return false;
        }
        instance.setLastNotifiedTs(now);
        instance.setNotifyCount(intOrDefault(instance.getNotifyCount(), 0) + 1);
        tasks.add(new AlertNotifyTask(instance, rule, event, now));
        return true;
    }

    /**
     * 重复提醒的入队（仅 {@code FIRING}；{@code ACKED} 之后不再打扰）。
     *
     * @param instance 实例
     * @param rule     规则（可空）
     * @param deviceId 设备 ID
     * @param now      本轮时刻
     * @param windows  当前生效的维护窗口
     * @param tasks    入队收集器
     * @return 是否入队
     */
    public boolean scheduleRepeat(IotAlertInstance instance, IotAlertRule rule, Long deviceId,
                                  LocalDateTime now, List<MaintenanceWindow> windows,
                                  List<AlertNotifyTask> tasks) {
        if (!AlertState.FIRING.getCode().equals(instance.getState())) {
            return false;
        }
        int repeatIntervalSec = intOrDefault(rule == null ? null : rule.getRepeatIntervalSec(),
            properties.getDefaultRepeatIntervalSec());
        LocalDateTime lastNotified = instance.getLastNotifiedTs();
        if (lastNotified != null && Duration.between(lastNotified, now).getSeconds() < repeatIntervalSec) {
            return false;
        }
        return schedule(instance, rule, deviceId, AlertNotifyEvent.REPEAT, now, windows, tasks);
    }

    /**
     * 把入队任务转成投递行（按幂等键去重；同一实例同一事件同一轮只留一行）。
     *
     * @param tasks 入队任务
     * @return 待写入的投递记录（可能为空）
     */
    public List<IotAlertNotification> planRows(List<AlertNotifyTask> tasks) {
        List<IotAlertNotification> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AlertNotifyTask task : tasks) {
            for (IotAlertNotification row : notifyPlanner.plan(task.instance(), task.rule(),
                channelsOf(task.rule()), task.rule() == null ? null : task.rule().getNotifyTargets(),
                task.event(), task.now())) {
                if (seen.add(row.getIdempotentKey())) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 生效通知渠道（规则缺省 → 平台默认；**不能传 null**，那会变成「一条通知都不排」）。 */
    private String channelsOf(IotAlertRule rule) {
        if (rule == null || rule.getNotifyChannels() == null || rule.getNotifyChannels().isBlank()) {
            return properties.getDefaultNotifyChannels();
        }
        return rule.getNotifyChannels();
    }

    private static int intOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
