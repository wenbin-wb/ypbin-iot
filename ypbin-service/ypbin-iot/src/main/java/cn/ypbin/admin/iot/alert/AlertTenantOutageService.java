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
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * **单租户**的断档 → 告警映射（设计 §2.6 的「设备离线类告警」+ 用户已批准口径 4）。
 *
 * <p><b>只做映射，不新造判定</b>：断档的判定完全由平台既有链路承担
 * （{@code outage_event} + {@code device_ liveness} + {@code OutageScanner}）。本类只回答两个问题：
 * 「这个进行中的断档要不要变成一条告警」（由**覆盖该设备的启用离线规则**决定——没有规则就不告警，
 * 这是刻意的 opt-in：默认不产生告警，避免一次部署就让全量设备开始刷告警），以及
 * 「这条告警对应的事件闭合了没有」（闭合即同步为 {@code RESOLVED}）。</p>
 *
 * <p><b>同一事件不重复建实例</b>：去重键是 {@code OUTAGE:{deviceId}:{outageEventId}}
 * （见 {@link AlertRules#outageDedupKey}），因此同一个 {@code outage_event} 在活动期内只可能有一条实例；
 * 该事件闭合后再次断档是**新事件**，会新建一条实例（这与「清除 ≠ 删除」不冲突）。</p>
 *
 * <p><b>一批一次查</b>：设备解析（至多 3 次）、进行中断档（1 次）、活动实例（1 次）、
 * 事件回查（1 次）、维护窗口（1 次）；写入按变化批量提交。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertTenantOutageService {

    private static final Logger log = LoggerFactory.getLogger(AlertTenantOutageService.class);

    /**
     * 孤立实例的**连续**轮次计数（instanceId → 轮数）。
     *
     * <p>只在「事件行确实不存在」时递增；一旦事件重新出现即清零。宽限期满后以
     * {@code OUTAGE_EVENT_MISSING} 收口并通知——否则会留下**永久幽灵告警**（保留清理只删 RESOLVED、
     * 决策 5 又取消了人工关闭）。计数是进程内的有界提示，**不落库**：重启后重新计宽限期，代价是多等
     * 一个宽限期，收益是不引入新表列。见独立复核 2026-10-03 M3。</p>
     */
    private final Map<Long, Integer> orphanRounds = new ConcurrentHashMap<>();

    private final AlertCandidateResolver candidateResolver;
    private final AlertNotifyScheduler notifyScheduler;
    private final AlertSilencePolicy silencePolicy;
    private final AlertMetrics metrics;
    private final AlertProperties properties;
    private final OutageEventMapper outageEventMapper;
    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertNotificationMapper notificationMapper;
    private final DeviceLivenessMapper livenessMapper;

    public AlertTenantOutageService(AlertCandidateResolver candidateResolver,
                                    AlertNotifyScheduler notifyScheduler, AlertSilencePolicy silencePolicy,
                                    AlertMetrics metrics, AlertProperties properties,
                                    OutageEventMapper outageEventMapper,
                                    IotAlertInstanceMapper instanceMapper,
                                    IotAlertNotificationMapper notificationMapper,
                                    DeviceLivenessMapper livenessMapper) {
        this.candidateResolver = candidateResolver;
        this.notifyScheduler = notifyScheduler;
        this.silencePolicy = silencePolicy;
        this.metrics = metrics;
        this.properties = properties;
        this.outageEventMapper = outageEventMapper;
        this.instanceMapper = instanceMapper;
        this.notificationMapper = notificationMapper;
        this.livenessMapper = livenessMapper;
    }

    /**
     * 单租户映射结果。
     *
     * @param devices  被离线规则覆盖的设备数
     * @param open     进行中的断档数
     * @param created  新建的告警实例数
     * @param resolved 同步恢复的告警实例数
     * @param queued   生成的待投递通知条数
     * @param orphan   找不到对应事件的孤立实例数（**需要人工关注**，不静默）
     */
    public record TenantOutageOutcome(int devices, int open, int created, int resolved, int queued,
                                      int orphan) {
    }

    /**
     * 执行一个租户的断档映射（**调用方必须已进入该租户上下文**）。
     *
     * @param outageRules 该租户的启用**离线规则**（无点位条件）
     * @return 结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantOutageOutcome mapTenant(List<IotAlertRule> outageRules) {
        LocalDateTime now = livenessMapper.selectNow();
        AlertCandidateResolver.DeviceScope scope = candidateResolver.resolveDeviceScope(outageRules,
            properties.getEvaluateBatchSize());
        if (scope.truncated()) {
            metrics.truncated();
        }
        if (scope.ruleByDevice().isEmpty()) {
            // 没有任何设备被离线规则覆盖 ⇒ 不产生离线告警（opt-in；不是错误）
            return new TenantOutageOutcome(0, 0, 0, 0, 0, 0);
        }
        Set<Long> deviceIds = scope.ruleByDevice().keySet();
        List<OutageEvent> openOutages = outageEventMapper.selectOpenOutages(new ArrayList<>(deviceIds),
            properties.getOutageBatchSize());
        Map<String, IotAlertInstance> activeByKey = new LinkedHashMap<>();
        Map<Long, IotAlertInstance> activeById = new LinkedHashMap<>();
        for (IotAlertInstance active : instanceMapper.selectActiveOutageAlerts()) {
            activeById.put(active.getId(), active);
            if (active.getActiveDedupKey() != null) {
                activeByKey.put(active.getActiveDedupKey(), active);
            }
        }
        List<MaintenanceWindow> windows = silencePolicy.loadActiveWindows(now);
        List<IotAlertInstance> toInsert = new ArrayList<>();
        List<IotAlertInstance> toUpdate = new ArrayList<>();
        List<AlertNotifyTask> notifyTasks = new ArrayList<>();
        Set<Long> insertedIds = new LinkedHashSet<>();
        int created = 0;
        int resolvedCount = 0;

        // ① 进行中的断档 → 没有实例就新建（并立即通知）
        for (OutageEvent event : openOutages) {
            IotAlertRule rule = scope.ruleByDevice().get(event.getDeviceId());
            if (rule == null) {
                continue;
            }
            String dedupKey = AlertRules.outageDedupKey(event.getDeviceId(), event.getId());
            IotAlertInstance active = activeByKey.get(dedupKey);
            if (active == null) {
                IotAlertInstance created_ = newOutageInstance(rule, event, now);
                toInsert.add(created_);
                insertedIds.add(created_.getId());
                created++;
                metrics.outageMapped(1);
                notifyScheduler.schedule(created_, rule, event.getDeviceId(), AlertNotifyEvent.FIRING, now,
                    windows, notifyTasks);
            } else if (notifyScheduler.scheduleRepeat(active, rule, event.getDeviceId(), now, windows,
                notifyTasks)) {
                toUpdate.add(active);
            }
        }
        // ② 活动实例对应的事件已闭合 → 同步恢复
        Map<Long, Long> eventIdByInstanceId = new LinkedHashMap<>();
        Set<Long> eventIds = new LinkedHashSet<>();
        for (IotAlertInstance active : activeById.values()) {
            Long eventId = parseOutageEventId(active.getDedupKey());
            if (eventId != null) {
                eventIdByInstanceId.put(active.getId(), eventId);
                eventIds.add(eventId);
            }
        }
        int orphan = 0;
        if (!eventIds.isEmpty()) {
            Map<Long, OutageEvent> events = new LinkedHashMap<>();
            for (OutageEvent event : outageEventMapper.selectByIds(new ArrayList<>(eventIds))) {
                events.put(event.getId(), event);
            }
            for (Map.Entry<Long, Long> entry : eventIdByInstanceId.entrySet()) {
                IotAlertInstance active = activeById.get(entry.getKey());
                if (active == null) {
                    continue;
                }
                OutageEvent event = events.get(entry.getValue());
                if (event == null) {
                    // 事件行已不存在（人工清理/数据异常）：**不伪造恢复**。
                    // 宽限期内保持活动（给人处理窗口），超过宽限轮次后以可区分的原因码收口——
                    // 既不留永久幽灵告警，也不谎称「设备已恢复」（独立复核 2026-10-03 M3）。
                    int rounds = orphanRounds.merge(active.getId(), 1, Integer::sum);
                    if (rounds < properties.getOutageOrphanGraceRounds()) {
                        orphan++;
                        if (rounds == 1 || rounds % 10 == 0) {
                            log.error("[iot] 断档告警找不到对应事件（第 {} 轮，宽限 {} 轮后收口）："
                                    + "instanceId={} dedupKey={}", rounds,
                                properties.getOutageOrphanGraceRounds(), active.getId(),
                                active.getDedupKey());
                        }
                        continue;
                    }
                    orphanRounds.remove(active.getId());
                    markOutageResolved(active, now, AlertReason.OUTAGE_EVENT_MISSING, insertedIds,
                        toUpdate, notifyTasks, windows, scope);
                    resolvedCount++;
                    metrics.outageResolved(1);
                    log.warn("[iot] 断档告警对应事件持续缺失（{} 轮），已按 {} 收口：instanceId={}",
                        rounds, AlertReason.OUTAGE_EVENT_MISSING.getCode(), active.getId());
                    continue;
                }
                orphanRounds.remove(active.getId());
                if (event.getEndTs() == null) {
                    continue;
                }
                markOutageResolved(active, event.getEndTs(), AlertReason.OUTAGE_RECOVERED, insertedIds,
                    toUpdate, notifyTasks, windows, scope);
                resolvedCount++;
                metrics.outageResolved(1);
            }
        }
        int queued = flush(toInsert, toUpdate, notifyTasks);
        if (created > 0 || resolvedCount > 0 || orphan > 0) {
            log.info("[iot] 断档告警映射：覆盖设备 {}、进行中断档 {}、新建 {}、恢复 {}、通知 {}、孤立 {}",
                scope.ruleByDevice().size(), openOutages.size(), created, resolvedCount, queued, orphan);
        }
        return new TenantOutageOutcome(scope.ruleByDevice().size(), openOutages.size(), created,
            resolvedCount, queued, orphan);
    }

    /** 收口一条断档类告警（置 RESOLVED、释放去重键、排恢复通知；新插入的行不必再更新）。 */
    private void markOutageResolved(IotAlertInstance active, LocalDateTime resolvedTs, AlertReason reason,
                                    Set<Long> insertedIds, List<IotAlertInstance> toUpdate,
                                    List<AlertNotifyTask> notifyTasks, List<MaintenanceWindow> windows,
                                    AlertCandidateResolver.DeviceScope scope) {
        active.setState(AlertState.RESOLVED.getCode());
        active.setResolvedTs(resolvedTs == null ? LocalDateTime.now() : resolvedTs);
        active.setReason(reason.getCode());
        // 释放去重键：同一设备下一次断档是**新事件**，会新建实例（「清除 ≠ 删除」）
        active.setActiveDedupKey(null);
        if (!insertedIds.contains(active.getId())) {
            toUpdate.add(active);
        }
        notifyScheduler.schedule(active, scope.ruleByDevice().get(active.getDeviceId()),
            active.getDeviceId(), AlertNotifyEvent.RESOLVED, active.getResolvedTs(), windows, notifyTasks);
    }

    /** 从去重键反解断档事件 ID（{@code OUTAGE:deviceId:eventId}）。 */
    private static Long parseOutageEventId(String dedupKey) {
        if (dedupKey == null) {
            return null;
        }
        String[] parts = dedupKey.split(AlertRules.DEDUP_SEPARATOR);
        if (parts.length != 3 || !AlertRules.OUTAGE_DEDUP_PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            return Long.valueOf(parts[2]);
        } catch (NumberFormatException ex) {
            log.error("[iot] 断档告警去重键形态异常（应为 OUTAGE:deviceId:eventId）：{}", dedupKey);
            return null;
        }
    }

    /** 建一条断档类实例（{@code rule_id = 0}、无点位、无阈值快照）。 */
    private IotAlertInstance newOutageInstance(IotAlertRule rule, OutageEvent event, LocalDateTime now) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(IdWorker.getId());
        instance.setTenantId(event.getTenantId());
        instance.setRuleId(AlertRules.RULE_ID_OUTAGE);
        instance.setDeviceId(event.getDeviceId());
        instance.setPropertyId(null);
        String dedupKey = AlertRules.outageDedupKey(event.getDeviceId(), event.getId());
        instance.setDedupKey(dedupKey);
        instance.setActiveDedupKey(dedupKey);
        instance.setSeverity(severityOf(rule));
        instance.setState(AlertState.FIRING.getCode());
        instance.setConsecutiveCount(0);
        // 触发值存**断档原因码**（原样）：它是这条告警「为什么响」的唯一证据，页面上按原因码翻译
        instance.setTriggerValue(event.getReason());
        instance.setThresholdSnapshot(null);
        instance.setStartTs(event.getStartTs());
        instance.setFiringTs(now);
        instance.setNotifyCount(0);
        instance.setCreateUser(rule.getCreateUser());
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        instance.setStatus(EntityStatus.ENABLED.getCode());
        instance.setIsDeleted(0);
        return instance;
    }

    /** 生效级别（规则缺省 → 平台默认）。 */
    private String severityOf(IotAlertRule rule) {
        AlertSeverity severity = rule == null ? null : AlertSeverity.of(rule.getSeverity());
        return severity == null ? properties.getDefaultSeverity() : severity.getCode();
    }

    /** 批量落库 + 排队通知（写入只在有变化时发生）。 */
    private int flush(List<IotAlertInstance> toInsert, List<IotAlertInstance> toUpdate,
                      List<AlertNotifyTask> notifyTasks) {
        if (!toInsert.isEmpty()) {
            insertWithRetry(toInsert);
        }
        if (!toUpdate.isEmpty()) {
            instanceMapper.batchUpdate(toUpdate);
        }
        List<IotAlertNotification> rows = notifyScheduler.planRows(notifyTasks);
        if (rows.isEmpty()) {
            return 0;
        }
        int inserted = notificationMapper.insertBatchIdempotent(rows);
        metrics.notifyQueued(inserted);
        return inserted;
    }

    /** 批量插入，撞唯一键时重同步后重试剩余部分（与点评估器同一处理，避免逐行插入）。 */
    private void insertWithRetry(List<IotAlertInstance> toInsert) {
        try {
            instanceMapper.insertBatch(toInsert);
        } catch (DuplicateKeyException ex) {
            List<String> keys = new ArrayList<>(toInsert.size());
            for (IotAlertInstance instance : toInsert) {
                keys.add(instance.getActiveDedupKey());
            }
            Set<String> existing = new LinkedHashSet<>();
            for (IotAlertInstance hit : instanceMapper.selectByActiveDedupKeys(keys)) {
                existing.add(hit.getActiveDedupKey());
            }
            List<IotAlertInstance> remaining = new ArrayList<>();
            for (IotAlertInstance instance : toInsert) {
                if (!existing.contains(instance.getActiveDedupKey())) {
                    remaining.add(instance);
                }
            }
            metrics.dedupConflict(toInsert.size() - remaining.size());
            log.warn("[iot] 断档告警批量插入撞唯一键（并发去重生效）：{} 条，重试剩余 {} 条",
                toInsert.size(), remaining.size());
            if (!remaining.isEmpty()) {
                instanceMapper.insertBatch(remaining);
            }
            log.debug("[iot] 断档去重冲突重试明细", ex);
        }
    }
}
