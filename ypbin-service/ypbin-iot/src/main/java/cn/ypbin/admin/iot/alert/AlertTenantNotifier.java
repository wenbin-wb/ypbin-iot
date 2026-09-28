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

import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyStatus;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * **单租户**的通知投递（调用方必须已进入该租户上下文）。
 *
 * <p><b>为什么不放在一个事务里</b>（与设计 §2.4 投递模型第 1 条一致的落地方式）：投递是 HTTP/SMTP
 * 慢调用，把数据库连接占在事务里等它会把连接池拖垮，而「通知失败」也不该回滚任何东西——
 * 告警状态是**事实**，通知是**尽力而为**。因此这里不做事务包裹：每次 Mapper 调用各自落库，
 * 「同一轮内所有状态变更一次批量提交」由单条 {@code batchUpdate} 保证。</p>
 *
 * <p><b>防重复投递</b>：先 {@code claim}（CAS 把 {@code next_retry_ts} 推到租约之后）再发送，
 * 因此滚动重启期间新旧实例不会同时发同一条。租约很短（{@value #CLAIM_LEASE_MINUTES} 分钟），
 * 它只覆盖「发送 + 落库」这段窗口，不会推迟正常的重试节奏。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertTenantNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertTenantNotifier.class);

    /** 认领租约（分钟）：只覆盖「发送 + 落库」窗口，过期后其它轮次可再取。 */
    private static final int CLAIM_LEASE_MINUTES = 2;

    private final IotAlertNotificationMapper notificationMapper;
    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertRuleMapper ruleMapper;
    private final IotDeviceMapper deviceMapper;
    private final SystemAlertNotificationSender sender;
    private final AlertNotifyThrottle throttle;
    private final AlertMetrics metrics;
    private final AlertProperties properties;

    public AlertTenantNotifier(IotAlertNotificationMapper notificationMapper,
                               IotAlertInstanceMapper instanceMapper, IotAlertRuleMapper ruleMapper,
                               IotDeviceMapper deviceMapper, SystemAlertNotificationSender sender,
                               AlertNotifyThrottle throttle, AlertMetrics metrics,
                               AlertProperties properties) {
        this.notificationMapper = notificationMapper;
        this.instanceMapper = instanceMapper;
        this.ruleMapper = ruleMapper;
        this.deviceMapper = deviceMapper;
        this.sender = sender;
        this.throttle = throttle;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * 单租户投递结果。
     *
     * @param sent      成功条数
     * @param failed    失败可重试条数
     * @param givenUp   放弃条数
     * @param throttled 延后条数
     */
    public record TenantDispatchOutcome(int sent, int failed, int givenUp, int throttled) {
    }

    /**
     * 投递一批到期通知（**调用方已进入租户上下文**）。
     *
     * @param due 到期通知（同一租户）
     * @param now 本轮时刻（整轮一致）
     * @return 结果
     */
    public TenantDispatchOutcome dispatchTenant(List<IotAlertNotification> due, LocalDateTime now) {
        if (due.isEmpty()) {
            return new TenantDispatchOutcome(0, 0, 0, 0);
        }
        // ① 一次性把实例、设备、规则批量取回（三张表各一次查询；严禁逐条查）
        Map<Long, IotAlertInstance> instances = new LinkedHashMap<>();
        Set<Long> instanceIds = new LinkedHashSet<>();
        for (IotAlertNotification item : due) {
            instanceIds.add(item.getInstanceId());
        }
        for (IotAlertInstance instance : instanceMapper.selectByIds(new ArrayList<>(instanceIds))) {
            instances.put(instance.getId(), instance);
        }
        Map<Long, IotDevice> devices = new LinkedHashMap<>();
        Set<Long> deviceIds = new LinkedHashSet<>();
        for (IotAlertInstance instance : instances.values()) {
            if (instance.getDeviceId() != null) {
                deviceIds.add(instance.getDeviceId());
            }
        }
        if (!deviceIds.isEmpty()) {
            for (IotDevice device : deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .select(IotDevice::getId, IotDevice::getDeviceName, IotDevice::getDeviceCode)
                .in(IotDevice::getId, new ArrayList<>(deviceIds)))) {
                devices.put(device.getId(), device);
            }
        }
        Map<Long, IotAlertRule> rules = new LinkedHashMap<>();
        Set<Long> ruleIds = new LinkedHashSet<>();
        for (IotAlertInstance instance : instances.values()) {
            if (instance.getRuleId() != null && instance.getRuleId() != AlertRules.RULE_ID_OUTAGE) {
                ruleIds.add(instance.getRuleId());
            }
        }
        if (!ruleIds.isEmpty()) {
            for (IotAlertRule rule : ruleMapper.selectByIds(new ArrayList<>(ruleIds))) {
                rules.put(rule.getId(), rule);
            }
        }

        long nowMs = now.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
        List<IotAlertNotification> changed = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        int givenUp = 0;
        int throttled = 0;
        for (IotAlertNotification item : due) {
            DeliveryOutcome outcome = deliverOne(item, instances, devices, rules, now, nowMs);
            switch (outcome) {
                case SENT -> sent++;
                case FAILED -> failed++;
                case GIVEN_UP -> givenUp++;
                case THROTTLED -> throttled++;
                case SKIPPED -> {
                    // 认领失败（另一轮已投递）：既不算成功也不算失败，等下一轮
                }
            }
            if (outcome != DeliveryOutcome.SKIPPED) {
                changed.add(item);
            }
        }
        if (!changed.isEmpty()) {
            // ④ 一轮一次批量更新（单条语句；不在循环里逐条 update）
            notificationMapper.batchUpdate(changed);
        }
        return new TenantDispatchOutcome(sent, failed, givenUp, throttled);
    }

    /** 单条投递的终局（供循环计数）。 */
    private enum DeliveryOutcome {
        SENT,
        FAILED,
        GIVEN_UP,
        THROTTLED,
        SKIPPED
    }

    /**
     * 投递**一条**通知（认领 → 发送 → 决定终局）。
     *
     * <p>抽成独立方法有两个理由：① 循环体里不再出现 Mapper 调用（仓内禁止「循环内 DB/RPC」，
     * 架构门禁 {@code SourceConventionTest} 会拦）；② 「限流延后 / 认领失败 / 成功 / 失败 / 放弃」
     * 五种终局的判定集中在一处，便于逐条写用例。</p>
     *
     * @param item      投递记录（**会被就地修改**：状态/尝试次数/下次重试/错误信息）
     * @param instances 实例索引
     * @param devices   设备索引
     * @param rules     规则索引
     * @param now       本轮时刻
     * @param nowMs     本轮时刻（epoch 毫秒，用于限流窗口）
     * @return 终局
     */
    private DeliveryOutcome deliverOne(IotAlertNotification item, Map<Long, IotAlertInstance> instances,
                                       Map<Long, IotDevice> devices, Map<Long, IotAlertRule> rules,
                                       LocalDateTime now, long nowMs) {
        IotAlertInstance instance = instances.get(item.getInstanceId());
        if (instance == null) {
            // 实例没了（与保留清理并发）：把投递记录也收口，避免永远到期的孤儿记录
            item.setNotifyStatus(AlertNotifyStatus.GIVEN_UP.getCode());
            item.setNextRetryTs(null);
            item.setLastError("告警实例已不存在（可能已被保留清理删除），投递放弃");
            metrics.notifyGivenUp();
            return DeliveryOutcome.GIVEN_UP;
        }
        // 全局限流：**先判限流再认领**——认领会消耗一次尝试次数，被限流不该算作一次失败
        if (!throttle.tryAcquire(item.getChannel(), nowMs)) {
            item.setNextRetryTs(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(AlertNotifyThrottle.nextWindowStartMs(nowMs)),
                AvailabilityRules.PLATFORM_ZONE));
            metrics.notifyThrottled(1);
            return DeliveryOutcome.THROTTLED;
        }
        // 认领（CAS）：只有把 next_retry_ts 推到租约之后的那一次成功
        if (notificationMapper.claim(item.getId(), now, now.plusMinutes(CLAIM_LEASE_MINUTES)) != 1) {
            return DeliveryOutcome.SKIPPED;
        }
        int attempt = (item.getAttempt() == null ? 0 : item.getAttempt()) + 1;
        item.setAttempt(attempt);
        AlertNotifyEvent event = AlertNotifyEvent.of(item.getEvent());
        if (event == null) {
            event = AlertNotifyEvent.FIRING;
        }
        SystemAlertNotificationSender.SendResult result = sender.send(item, instance,
            devices.get(instance.getDeviceId()), rules.get(instance.getRuleId()), event);
        if (result.success()) {
            item.setNotifyStatus(AlertNotifyStatus.SENT.getCode());
            item.setNextRetryTs(null);
            item.setLastError(null);
            metrics.notifySent();
            return DeliveryOutcome.SENT;
        }
        if (attempt >= properties.getNotifyMaxAttempt()) {
            item.setNotifyStatus(AlertNotifyStatus.GIVEN_UP.getCode());
            item.setNextRetryTs(null);
            item.setLastError(truncate(result.error()));
            metrics.notifyGivenUp();
            log.warn("[iot] 告警通知重试耗尽（已放弃）：notificationId={} channel={} 原因={}", item.getId(),
                item.getChannel(), result.error());
            return DeliveryOutcome.GIVEN_UP;
        }
        item.setNotifyStatus(AlertNotifyStatus.FAILED.getCode());
        item.setNextRetryTs(now.plusSeconds(backoffSeconds(attempt)));
        item.setLastError(truncate(result.error()));
        metrics.notifyFailed();
        return DeliveryOutcome.FAILED;
    }

    /** 第 N 次失败后的退避秒数（超出退避步数时复用最后一个值，并在启动自检里已提示过配置不对齐）。 */
    private long backoffSeconds(int attempt) {
        List<Long> backoff = properties.getNotifyBackoffSeconds();
        if (backoff.isEmpty()) {
            return 30L;
        }
        int index = Math.min(Math.max(attempt, 1), backoff.size()) - 1;
        Long value = backoff.get(index);
        return value == null || value <= 0 ? 30L : value;
    }

    /** 错误信息截断到列宽以内（不美化内容，只截长度）。 */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        int max = properties.getNotifyMaxErrorLength();
        return error.length() <= max ? error : error.substring(0, max);
    }
}
