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
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.enums.AlertTriggerMode;
import cn.ypbin.admin.iot.enums.AlertValueType;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPointResp;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryReq;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryService;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * **单租户**的告警评估（设计 §2.2.2 路线 C）。
 *
 * <p>拆出这个类有两个硬理由，都不是审美：</p>
 * <ol>
 *   <li><b>事务边界</b>：跨租户的规则加载不能与「进入某租户后的写入」共用同一段逻辑
 *       （租户上下文切换后连接上的租户条件会串），因此「进入租户 → 判定 → 落库」必须是一段独立事务；
 *       而 {@code @Transactional} 在类内自调用时不生效 ⇒ 必须是**另一个 Bean**；</li>
 *   <li><b>架构门禁</b>：仓内禁止「循环内 DB/RPC 调用」，跨租户的循环体只能调用一个方法，
 *       真正的 DB 动作必须落在被调用的方法里。</li>
 * </ol>
 *
 * <p><b>一轮评估的 DB/Redis 往返次数与设备数无关</b>（设计 §3.5-N6）：候选解析最多 3 次设备查询、
 * 1 次 Redis pipeline、1 次活动实例查询、1 次活性查询、1 次维护窗口查询；写入按「有变化才写」批量提交。
 * 唯一的例外是 {@code DURATION} 模式在「已满 T 秒待升级」时按候选回查一次时序库——那是设计明确要求的
 * 窗口校验（§2.2.2），只在升级检查那一刻触发，不是每轮每设备。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertTenantEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertTenantEvaluator.class);

    private final AlertCandidateResolver candidateResolver;
    private final AlertLatestValueReader latestValueReader;
    private final AlertNotifyPlanner notifyPlanner;
    private final AlertMetrics metrics;
    private final AlertProperties properties;
    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertNotificationMapper notificationMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final AlertSilencePolicy silencePolicy;
    private final TimeSeriesQueryService timeSeriesQueryService;

    public AlertTenantEvaluator(AlertCandidateResolver candidateResolver,
                                AlertLatestValueReader latestValueReader,
                                AlertNotifyPlanner notifyPlanner, AlertMetrics metrics,
                                AlertProperties properties, IotAlertInstanceMapper instanceMapper,
                                IotAlertNotificationMapper notificationMapper,
                                DeviceLivenessMapper livenessMapper,
                                AlertSilencePolicy silencePolicy,
                                TimeSeriesQueryService timeSeriesQueryService) {
        this.candidateResolver = candidateResolver;
        this.latestValueReader = latestValueReader;
        this.notifyPlanner = notifyPlanner;
        this.metrics = metrics;
        this.properties = properties;
        this.instanceMapper = instanceMapper;
        this.notificationMapper = notificationMapper;
        this.livenessMapper = livenessMapper;
        this.silencePolicy = silencePolicy;
        this.timeSeriesQueryService = timeSeriesQueryService;
    }

    /**
     * 单租户评估结果（供跨租户聚合与用例断言）。
     *
     * @param candidates  候选（规则 × 点位条件 × 设备）数
     * @param fired       本轮新触发数
     * @param resolved    本轮恢复数
     * @param queued      本轮生成的待投递通知条数
     * @param truncated   是否因单轮上限被截断
     * @param redisFailed 是否因 Redis 读取失败而跳过判定
     */
    public record TenantOutcome(int candidates, int fired, int resolved, int queued, boolean truncated,
                                boolean redisFailed) {

        /** 什么都不做的结果。 */
        public static TenantOutcome skipped(boolean redisFailed) {
            return new TenantOutcome(0, 0, 0, 0, false, redisFailed);
        }
    }

    /** 待入队的通知任务（{@code now} 必须整轮一致：通知的 next_retry_ts 与实例的 last_notified_ts 要同源）。 */
    private record NotifyTask(IotAlertInstance instance, IotAlertRule rule, AlertNotifyEvent event,
                              LocalDateTime now) {
    }

    /** 单候选的处理结果（只用于累加计数）。 */
    private record Outcome(int fired, int resolved) {
    }

    /**
     * 评估一个租户（**调用方必须已经进入该租户的上下文**）。
     *
     * @param tenantId       租户 ID（用于拼最新值 Redis key；取设备行的租户而不是上下文，
     *                       与 {@code LatestValueQueryService} 同口径）
     * @param rules          该租户的启用规则
     * @param pointsByRuleId 规则 ID → 点位条件
     * @return 评估结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantOutcome evaluateTenant(long tenantId, List<IotAlertRule> rules,
                                        Map<Long, List<IotAlertRulePoint>> pointsByRuleId) {
        LocalDateTime now = livenessMapper.selectNow();
        long nowMs = toEpochMillis(now);
        AlertCandidateResolver.Resolved resolved = candidateResolver.resolve(rules, pointsByRuleId,
            properties.getEvaluateBatchSize());
        if (resolved.truncated()) {
            metrics.truncated();
            log.warn("[iot] 告警评估：单轮设备数超过上限 {}，本轮已截断（跨轮滚动，未丢弃规则）",
                properties.getEvaluateBatchSize());
        }
        Map<Long, List<AlertCandidateResolver.Candidate>> candidatesByDevice =
            resolved.candidatesByDevice();
        if (candidatesByDevice.isEmpty()) {
            return TenantOutcome.skipped(false);
        }
        List<Long> deviceIds = new ArrayList<>(candidatesByDevice.keySet());
        metrics.addDevices(deviceIds.size());

        // ① 批量读最新值（一次 pipeline；失败 ⇒ 本轮跳过判定，**绝不**当成「都没越界」）
        Map<Long, Map<String, AlertLatestValueReader.AlertSample>> latest;
        try {
            latest = latestValueReader.readLatest(tenantId, deviceIds);
        } catch (AlertEvaluationException ex) {
            metrics.redisFailed();
            log.error("[iot] 告警评估：最新值读取失败，本轮跳过判定（不产生触发、也不产生恢复）："
                + "deviceIds={}", deviceIds.size(), ex);
            return TenantOutcome.skipped(true);
        }
        // ② 活动实例（一次查询；按下标内存比对，避免逐键查）
        Map<String, IotAlertInstance> activeByKey = new LinkedHashMap<>();
        for (IotAlertInstance active : instanceMapper.selectActiveInTenant()) {
            if (active.getActiveDedupKey() != null) {
                activeByKey.put(active.getActiveDedupKey(), active);
            }
        }
        // ③ 采集周期（一次批量查询；用于「倍数 × 采集周期」的陈旧判定）
        Map<Long, Integer> pollIntervals = loadPollIntervals(deviceIds);
        // ④ 维护窗口（一次查询；静默复用既有语义，只读不写）
        List<MaintenanceWindow> activeWindows = silencePolicy.loadActiveWindows(now);

        List<IotAlertInstance> toInsert = new ArrayList<>();
        List<IotAlertInstance> toUpdate = new ArrayList<>();
        List<Long> toDeletePending = new ArrayList<>();
        List<NotifyTask> notifyTasks = new ArrayList<>();
        int fired = 0;
        int resolvedCount = 0;
        int candidates = 0;

        for (Map.Entry<Long, List<AlertCandidateResolver.Candidate>> entry
            : candidatesByDevice.entrySet()) {
            Long deviceId = entry.getKey();
            IotDevice device = resolved.devices().get(deviceId);
            Map<String, AlertLatestValueReader.AlertSample> deviceValues =
                latest.getOrDefault(deviceId, Map.of());
            long stalenessTtlMs = stalenessTtlMs(pollIntervals.get(deviceId));
            for (AlertCandidateResolver.Candidate candidate : entry.getValue()) {
                candidates++;
                IotAlertInstance active = activeByKey.get(AlertRules.dedupKey(candidate.rule().getId(),
                    deviceId, candidate.point().getPropertyId()));
                Outcome outcome = applyCandidate(candidate, device, deviceValues, active, now, nowMs,
                    stalenessTtlMs, toInsert, toUpdate, toDeletePending, notifyTasks, activeWindows);
                fired += outcome.fired;
                resolvedCount += outcome.resolved;
            }
        }

        int queued = flush(toInsert, toUpdate, toDeletePending, notifyTasks);
        return new TenantOutcome(candidates, fired, resolvedCount, queued, resolved.truncated(), false);
    }

    /**
     * 处理一个候选：判定 → 状态机 → 收集变更与通知任务。
     *
     * <p>本方法内**没有 DB 写入**（时序窗口校验是一次只读查询，且只在持续模式待升级时触发）。</p>
     */
    private Outcome applyCandidate(AlertCandidateResolver.Candidate candidate, IotDevice device,
                                   Map<String, AlertLatestValueReader.AlertSample> deviceValues,
                                   IotAlertInstance active, LocalDateTime now, long nowMs,
                                   long stalenessTtlMs, List<IotAlertInstance> toInsert,
                                   List<IotAlertInstance> toUpdate, List<Long> toDeletePending,
                                   List<NotifyTask> notifyTasks, List<MaintenanceWindow> activeWindows) {
        IotAlertRule rule = candidate.rule();
        IotAlertRulePoint point = candidate.point();
        AlertTriggerMode mode = AlertTriggerMode.of(rule.getTriggerMode());
        if (mode == null) {
            // 模式非法（脏数据）：按「不可判定」计数并跳过——不猜一个模式出来，也不静默无声
            metrics.verdictSkipped(AlertValueVerdict.INVALID_CONDITION);
            return new Outcome(0, 0);
        }
        AlertLatestValueReader.AlertSample sample = findSample(deviceValues, point.getPropertyId());
        AlertValueVerdict verdict = sample == null
            ? AlertValueVerdict.MISSING
            : AlertRules.verdict(point, sample.value(), sample.quality(), sample.ts(), nowMs,
                stalenessTtlMs);
        if (verdict.isSkipped()) {
            metrics.verdictSkipped(verdict);
        }
        AlertValueType valueType = AlertValueType.of(point.getValueType());
        BigDecimal parsed = sample == null || valueType == null
            ? null : AlertRules.parseValue(valueType, sample.value());
        boolean recoverySatisfied = parsed != null && AlertRules.recovered(point, parsed);
        AlertState currentState = active == null ? null : AlertState.of(active.getState());
        if (active != null && currentState == null) {
            // 状态码非法（脏数据）：不推进状态机，但必须可见
            metrics.verdictSkipped(AlertValueVerdict.INVALID_CONDITION);
            return new Outcome(0, 0);
        }
        int threshold = thresholdOf(rule, mode);
        int pendingTtlSec = intOrDefault(rule.getPendingTtlSec(), properties.getDefaultPendingTtlSec());
        Boolean windowSatisfied = null;
        if (mode == AlertTriggerMode.DURATION && currentState == AlertState.PENDING
            && active.getStartTs() != null
            && Duration.between(active.getStartTs(), now).getSeconds() >= threshold) {
            windowSatisfied = verifyDurationWindow(device.getId(), point, active.getStartTs(), now, nowMs,
                stalenessTtlMs);
        }
        AlertStateMachine.Decision decision = AlertStateMachine.decide(currentState,
            active == null || active.getConsecutiveCount() == null ? 0 : active.getConsecutiveCount(),
            active == null ? null : active.getStartTs(), verdict, recoverySatisfied, windowSatisfied, mode,
            threshold, pendingTtlSec, now);

        String rawValue = sample == null ? null : sample.value();
        String dedupKey = AlertRules.dedupKey(rule.getId(), device.getId(), point.getPropertyId());
        switch (decision.kind()) {
            case NONE -> {
                return new Outcome(0, 0);
            }
            case CREATE_PENDING, CREATE_FIRING -> {
                IotAlertInstance created = newInstance(rule, device, point, dedupKey, now, decision,
                    rawValue);
                toInsert.add(created);
                if (decision.isFiring()) {
                    metrics.triggered();
                    scheduleNotify(created, rule, device, AlertNotifyEvent.FIRING, now, activeWindows,
                        notifyTasks);
                    return new Outcome(1, 0);
                }
            }
            case DROP_PENDING -> {
                if (active.getStartTs() != null && pendingTtlSec > 0
                    && Duration.between(active.getStartTs(), now).getSeconds() > pendingTtlSec) {
                    metrics.pendingExpired();
                } else {
                    metrics.flapped();
                }
                toDeletePending.add(active.getId());
            }
            case PROMOTE -> {
                active.setState(AlertState.FIRING.getCode());
                active.setFiringTs(decision.firingTs());
                active.setConsecutiveCount(decision.consecutiveCount());
                active.setTriggerValue(rawValue);
                toUpdate.add(active);
                metrics.triggered();
                scheduleNotify(active, rule, device, AlertNotifyEvent.FIRING, now, activeWindows,
                    notifyTasks);
                return new Outcome(1, 0);
            }
            case PROGRESS -> {
                active.setConsecutiveCount(decision.consecutiveCount());
                active.setTriggerValue(rawValue);
                toUpdate.add(active);
                scheduleRepeat(active, rule, device, now, activeWindows, notifyTasks);
            }
            case RESOLVE -> {
                active.setState(AlertState.RESOLVED.getCode());
                active.setResolvedTs(now);
                active.setReason(decision.reason() == null ? null : decision.reason().getCode());
                // 恢复时把活动去重键置 NULL：唯一约束随之释放，同一键可以再开一条（「清除 ≠ 删除」）
                active.setActiveDedupKey(null);
                active.setTriggerValue(rawValue);
                toUpdate.add(active);
                metrics.resolved();
                scheduleNotify(active, rule, device, AlertNotifyEvent.RESOLVED, now, activeWindows,
                    notifyTasks);
                return new Outcome(0, 1);
            }
            default -> throw new IllegalStateException("未处理的状态机动作：" + decision.kind());
        }
        return new Outcome(0, 0);
    }

    /** 取样本：先按**归一后的规范标识**，再按原始标识兜一次（坐标映射缺失时 field 就是原始标识）。 */
    private static AlertLatestValueReader.AlertSample findSample(
        Map<String, AlertLatestValueReader.AlertSample> deviceValues, String propertyId) {
        if (propertyId == null) {
            return null;
        }
        AlertLatestValueReader.AlertSample sample =
            deviceValues.get(AlertRules.normalizePropertyId(propertyId));
        return sample == null ? deviceValues.get(propertyId) : sample;
    }

    /**
     * 持续 T 秒的窗口校验（**只在这一刻查时序库**，设计 §2.2.2）。
     *
     * @return {@code TRUE} = 窗口内每个可判定点都越界；{@code FALSE} = 存在未越界点；
     *         {@code null} = **不可求值**（库读不到 / 窗口内无点 / 存在不可判定点）
     */
    private Boolean verifyDurationWindow(Long deviceId, IotAlertRulePoint point, LocalDateTime startTs,
                                         LocalDateTime now, long nowMs, long stalenessTtlMs) {
        TimeSeriesQueryReq req = new TimeSeriesQueryReq();
        req.setPropertyId(point.getPropertyId());
        req.setFrom(toEpochMillis(startTs));
        req.setTo(toEpochMillis(now));
        req.setLimit(TimeSeriesQueryReq.MAX_LIMIT);
        List<TimeSeriesPointResp> points;
        try {
            points = timeSeriesQueryService.query(deviceId, req);
        } catch (RuntimeException ex) {
            // 时序库读不到 ⇒ 窗口**不可求值**：不改状态、不发通知（设计 §2.2.4 表中那一行）
            metrics.timeseriesFailed();
            log.warn("[iot] 告警评估：持续窗口校验不可求值（时序库查询失败），本轮不改状态："
                + "deviceId={} property={}", deviceId, LogSanitizer.sanitize(point.getPropertyId()));
            log.debug("[iot] 持续窗口校验失败明细", ex);
            return null;
        }
        if (points.isEmpty()) {
            // 窗口内一个点都没有 ⇒ 无法证明「持续越界」（**不是**「没有越界」）
            metrics.timeseriesFailed();
            return null;
        }
        for (TimeSeriesPointResp item : points) {
            // 窗口内的点**天然是历史点**（最早的点在 startTs，可能已超过陈旧 TTL）⇒ 这里**不做时效判定**
            // （传 0 = 关闭陈旧检查），只判「可判定 / 越界」。否则「持续 T 秒」在 T > 陈旧 TTL 时
            // 会因为窗口早期点被判陈旧而永远不可求值（D7 会变成永远不触发）。
            // 时效判定的语义边界不变：它回答的是「我当作当前值的那个点是否新鲜」，而这里的时间范围由
            // 窗口本身（startTs ~ now）界定。
            AlertValueVerdict verdict = AlertRules.verdict(point, item.value(), item.quality(), item.ts(),
                nowMs, 0L);
            if (!verdict.isDecidable()) {
                // 严格口径：窗口里只要有一个不可判定的点，就不声称「持续越界」（宁可不触发，也不误触发）
                metrics.verdictSkipped(verdict);
                return null;
            }
            if (!verdict.isOutOfRange()) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    /** 建实例（PENDING 或 FIRING；id 预生成，便于同事务内写通知行）。 */
    private IotAlertInstance newInstance(IotAlertRule rule, IotDevice device, IotAlertRulePoint point,
                                         String dedupKey, LocalDateTime now,
                                         AlertStateMachine.Decision decision, String rawValue) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(IdWorker.getId());
        instance.setTenantId(device.getTenantId());
        instance.setRuleId(rule.getId());
        instance.setDeviceId(device.getId());
        instance.setPropertyId(point.getPropertyId());
        instance.setDedupKey(dedupKey);
        instance.setActiveDedupKey(dedupKey);
        instance.setSeverity(severityOf(rule));
        instance.setState(decision.targetState().getCode());
        instance.setConsecutiveCount(decision.consecutiveCount());
        instance.setTriggerValue(rawValue);
        instance.setThresholdSnapshot(AlertRules.thresholdSnapshot(point));
        instance.setStartTs(now);
        instance.setFiringTs(decision.firingTs());
        instance.setNotifyCount(0);
        // create_user 取规则创建者：评估线程没有登录态，用「谁配的规则」作为可追溯的责任人
        instance.setCreateUser(rule.getCreateUser());
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        instance.setStatus(EntityStatus.ENABLED.getCode());
        instance.setIsDeleted(0);
        return instance;
    }

    /**
     * 触发/恢复通知的入队（含静默判定：**静默只影响通知，不影响判定与状态**，设计 §3.4-S1）。
     */
    private void scheduleNotify(IotAlertInstance instance, IotAlertRule rule, IotDevice device,
                                AlertNotifyEvent event, LocalDateTime now,
                                List<MaintenanceWindow> activeWindows, List<NotifyTask> notifyTasks) {
        if (silencePolicy.isSilenced(instance, rule, device.getId(), now, activeWindows)) {
            metrics.notifySilenced();
            return;
        }
        instance.setLastNotifiedTs(now);
        instance.setNotifyCount(intOrDefault(instance.getNotifyCount(), 0) + 1);
        notifyTasks.add(new NotifyTask(instance, rule, event, now));
    }

    /**
     * 重复提醒的入队（仅 {@code FIRING}；{@code ACKED} 之后不再按 repeat_interval 打扰，设计 §2.3/§3.4-S5）。
     */
    private void scheduleRepeat(IotAlertInstance instance, IotAlertRule rule, IotDevice device,
                                LocalDateTime now, List<MaintenanceWindow> activeWindows,
                                List<NotifyTask> notifyTasks) {
        if (!AlertState.FIRING.getCode().equals(instance.getState())) {
            return;
        }
        int repeatIntervalSec = intOrDefault(rule.getRepeatIntervalSec(),
            properties.getDefaultRepeatIntervalSec());
        LocalDateTime lastNotified = instance.getLastNotifiedTs();
        if (lastNotified != null
            && Duration.between(lastNotified, now).getSeconds() < repeatIntervalSec) {
            return;
        }
        if (silencePolicy.isSilenced(instance, rule, device.getId(), now, activeWindows)) {
            metrics.notifySilenced();
            return;
        }
        instance.setLastNotifiedTs(now);
        instance.setNotifyCount(intOrDefault(instance.getNotifyCount(), 0) + 1);
        notifyTasks.add(new NotifyTask(instance, rule, AlertNotifyEvent.REPEAT, now));
    }

    /** 批量落库 + 排队通知（写入只在有变化时发生；稳态下几乎为零）。 */
    private int flush(List<IotAlertInstance> toInsert, List<IotAlertInstance> toUpdate,
                      List<Long> toDeletePending, List<NotifyTask> notifyTasks) {
        if (!toInsert.isEmpty()) {
            insertWithRetry(toInsert);
        }
        if (!toUpdate.isEmpty()) {
            instanceMapper.batchUpdate(toUpdate);
        }
        if (!toDeletePending.isEmpty()) {
            instanceMapper.deletePendingByIds(toDeletePending);
        }
        if (notifyTasks.isEmpty()) {
            return 0;
        }
        List<IotAlertNotification> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NotifyTask task : notifyTasks) {
            for (IotAlertNotification row : notifyPlanner.plan(task.instance(), task.rule(),
                channelsOf(task.rule()), task.rule().getNotifyTargets(), task.event(), task.now())) {
                if (seen.add(row.getIdempotentKey())) {
                    rows.add(row);
                }
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        int inserted = notificationMapper.insertBatchIgnore(rows);
        metrics.notifyQueued(inserted);
        if (inserted < rows.size()) {
            log.info("[iot] 告警通知入队：{} 条中 {} 条被幂等键跳过（同一实例同一事件同一轮已入过队）",
                rows.size(), rows.size() - inserted);
        }
        return inserted;
    }

    /**
     * 批量插入，撞唯一键时**重同步后重试一次**（并发去重生效的正向路径）。
     *
     * <p>为什么不是「逐行插入兜底」：那既违反仓内「循环内不做 DB 调用」的铁律，也会把一次冲突放大成
     * N 次往返。正确做法是查出「已经被别的轮次创建的键」，剔除后把**剩余的**作为**一条**语句重试。</p>
     */
    private void insertWithRetry(List<IotAlertInstance> toInsert) {
        try {
            instanceMapper.insertBatch(toInsert);
        } catch (DuplicateKeyException ex) {
            log.warn("[iot] 告警实例批量插入撞唯一键（并发去重生效）：{} 条，重同步后重试剩余部分",
                toInsert.size());
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
            if (!remaining.isEmpty()) {
                instanceMapper.insertBatch(remaining);
            }
            log.debug("[iot] 去重冲突重试明细", ex);
        }
    }

    /** 采集周期批量加载（一次查询）。 */
    private Map<Long, Integer> loadPollIntervals(List<Long> deviceIds) {
        Map<Long, Integer> intervals = new LinkedHashMap<>();
        List<DeviceLiveness> rows = livenessMapper.selectList(new LambdaQueryWrapper<DeviceLiveness>()
            .select(DeviceLiveness::getDeviceId, DeviceLiveness::getPollIntervalMs)
            .in(DeviceLiveness::getDeviceId, deviceIds));
        for (DeviceLiveness row : rows) {
            if (row.getPollIntervalMs() != null && row.getPollIntervalMs() > 0) {
                intervals.put(row.getDeviceId(), row.getPollIntervalMs());
            }
        }
        return intervals;
    }

    /** 陈旧 TTL：{@code 倍数 × 采集周期}，取不到周期用兜底值，再夹到上限。 */
    private long stalenessTtlMs(Integer pollIntervalMs) {
        long base = pollIntervalMs == null || pollIntervalMs <= 0
            ? properties.getStalenessFallbackTtlMs()
            : pollIntervalMs * Math.max(1L, properties.getStalenessIntervalMultiplier());
        return Math.min(base, properties.getStalenessMaxTtlMs());
    }

    /** 生效的连续次数 / 持续秒数（规则缺省时用平台默认）。 */
    private int thresholdOf(IotAlertRule rule, AlertTriggerMode mode) {
        int configured = intOrDefault(rule.getTriggerThreshold(), 0);
        if (configured > 0) {
            return configured;
        }
        return mode == AlertTriggerMode.CONSECUTIVE_COUNT ? properties.getDefaultTriggerThreshold() : 0;
    }

    /** 生效级别（规则缺省 → 平台默认）。 */
    private String severityOf(IotAlertRule rule) {
        AlertSeverity severity = AlertSeverity.of(rule.getSeverity());
        return severity == null ? properties.getDefaultSeverity() : severity.getCode();
    }

    /** 生效通知渠道（规则缺省 → 平台默认）。 */
    private String channelsOf(IotAlertRule rule) {
        String channels = rule.getNotifyChannels();
        return channels == null || channels.isBlank() ? properties.getDefaultNotifyChannels() : channels;
    }

    private static int intOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long toEpochMillis(LocalDateTime time) {
        return time.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
    }
}
