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

import cn.ypbin.starter.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertReason;
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
import java.math.RoundingMode;
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
 * 唯一的例外是 {@code DURATION} 模式在「已满 T 秒待升级」时按**条件行**回查时序库——那是设计明确要求的
 * 窗口校验（§2.2.2），只在升级检查那一刻触发，不是每轮每设备。</p>
 *
 * <p><b>判定粒度：合取条件组（真 AND，2026-10-15）</b>：同一条规则落在同一台设备上的多个点位条件
 * 构成**一个条件组**，只有组内**全部**条件对同一轮最新值判定为越界才产生触发；任一条未越界即不触发。
 * 组内任一条不可判定且无「未越界」时，整组本轮不可判定（{@link AlertStateMachine} 对非 OUT/IN 的语义
 * 是「计数与状态都不动」）——**绝不把「读不到」当成「未越界」**。去重键因此由
 * {@code ruleId:deviceId:propertyId} 收紧为 {@code R:ruleId:deviceId}（见 {@link AlertRules#groupDedupKey}），
 * 一条规则对一台设备**至多一条**活动实例；组内逐条件的取值落在实例的 {@code condition_snapshot} 列里。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertTenantEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertTenantEvaluator.class);

    private final AlertCandidateResolver candidateResolver;
    private final AlertLatestValueReader latestValueReader;
    private final AlertNotifyScheduler notifyScheduler;
    private final AlertMetrics metrics;
    private final AlertProperties properties;
    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertNotificationMapper notificationMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final AlertSilencePolicy silencePolicy;
    private final TimeSeriesQueryService timeSeriesQueryService;

    public AlertTenantEvaluator(AlertCandidateResolver candidateResolver,
                                AlertLatestValueReader latestValueReader,
                                AlertNotifyScheduler notifyScheduler, AlertMetrics metrics,
                                AlertProperties properties, IotAlertInstanceMapper instanceMapper,
                                IotAlertNotificationMapper notificationMapper,
                                DeviceLivenessMapper livenessMapper,
                                AlertSilencePolicy silencePolicy,
                                TimeSeriesQueryService timeSeriesQueryService) {
        this.candidateResolver = candidateResolver;
        this.latestValueReader = latestValueReader;
        this.notifyScheduler = notifyScheduler;
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
        List<AlertNotifyTask> notifyTasks = new ArrayList<>();
        int fired = 0;
        int resolvedCount = 0;
        int candidates = 0;

        for (Map.Entry<Long, List<AlertCandidateResolver.Candidate>> entry
            : candidatesByDevice.entrySet()) {
            Long deviceId = entry.getKey();
            IotDevice device = resolved.devices().get(deviceId);
            Map<String, AlertLatestValueReader.AlertSample> deviceValues =
                latest.getOrDefault(deviceId, Map.of());
            Integer pollIntervalMs = pollIntervals.get(deviceId);
            long stalenessTtlMs = stalenessTtlMs(pollIntervalMs);
            // 真 AND（2026-10-15）：同一条规则在本设备上的**多个点位条件构成一个合取条件组**，
            // 组内先一起判定（组判定见 evaluateGroup），再只走一次状态机 ⇒ 任一条不越界即不触发。
            for (List<AlertCandidateResolver.Candidate> group : groupByRule(entry.getValue())) {
                candidates += group.size();
                IotAlertRule rule = group.get(0).rule();
                // 去重键 =（规则 × 设备）：propertyId 不再进键（理由见 AlertRules#groupDedupKey）
                IotAlertInstance active = activeByKey.get(AlertRules.groupDedupKey(rule.getId(), deviceId));
                Outcome outcome = evaluateGroup(rule, group, device, deviceValues, active, activeByKey,
                    now, nowMs, stalenessTtlMs, pollIntervalMs, toInsert, toUpdate, toDeletePending,
                    notifyTasks, activeWindows);
                fired += outcome.fired;
                resolvedCount += outcome.resolved;
            }
        }

        int queued = flush(toInsert, toUpdate, toDeletePending, notifyTasks);
        return new TenantOutcome(candidates, fired, resolvedCount, queued, resolved.truncated(), false);
    }

    /** 候选按规则分组（保持候选原有顺序 ⇒ 同一规则的条件行顺序 == mapper 的 rule_id,id 升序）。 */
    private static List<List<AlertCandidateResolver.Candidate>> groupByRule(
        List<AlertCandidateResolver.Candidate> candidates) {
        Map<Long, List<AlertCandidateResolver.Candidate>> grouped = new LinkedHashMap<>();
        for (AlertCandidateResolver.Candidate candidate : candidates) {
            grouped.computeIfAbsent(candidate.rule().getId(), key -> new ArrayList<>()).add(candidate);
        }
        return new ArrayList<>(grouped.values());
    }

    /** 组内一个条件的判定结果（条件行 + 样本 + 判定，供组判定、快照与最严重排序复用）。 */
    private record ConditionVerdict(IotAlertRulePoint point, AlertLatestValueReader.AlertSample sample,
                                    AlertValueVerdict verdict) {
    }

    /**
     * 处理**一个条件组**：组判定 → 状态机 → 收集变更与通知任务（真 AND，2026-10-15）。
     *
     * <p><b>组判定（合取）</b>：</p>
     * <ol>
     *   <li>任一条 {@code IN_RANGE}（可判定且不越界）⇒ 组判定 {@code IN_RANGE}——PENDING 期据此吸收抖动，
     *       活动期据此进入恢复判定；</li>
     *   <li>否则任一条 {@code OUT_OF_RANGE} ⇒ 组判定 {@code OUT_OF_RANGE}（**全部条件都越界**才走到这里）；</li>
     *   <li>否则（全是「不可判定」）⇒ 组判定取第一个不可判定结果。{@link AlertStateMachine} 对非 OUT/IN
     *       的语义正是「计数与状态都不动」⇒ 整组本轮不可判定即不触发、也不恢复，
     *       **绝不把「读不到」伪装成「未越界」或「越界」**（设计 §2.2.3 口径在组维度的延续）。</li>
     * </ol>
     *
     * <p><b>恢复（可证伪即收口）</b>：只要有一条可判定条件越过自己的恢复门槛（含回差）即成立——
     * 合取已被证伪。不可判定条件既不充当恢复证据（不伪装成恢复），也不阻挡恢复（不制造僵尸）；
     * 详见 {@link #recoverySatisfied(List)}。</p>
     *
     * <p><b>本方法内没有 DB 写入</b>（时序窗口校验是一次只读查询，且只在持续模式待升级时触发）。</p>
     *
     * @param rule         规则（模式/阈值/静默窗口等配置的唯一来源）
     * @param group        该规则在该设备上的全部点位条件候选（非空）
     * @param device       设备
     * @param deviceValues 该设备的最新值（规范点位标识 → 样本）
     * @param active       当前活动实例（按 {@code R:ruleId:deviceId} 命中；可空）
     * @param activeByKey  本租户全部活动实例（键 = active_dedup_key；用于收口「旧键残留的孤儿实例」）
     * @return 计数结果
     */
    private Outcome evaluateGroup(IotAlertRule rule, List<AlertCandidateResolver.Candidate> group,
                                  IotDevice device,
                                  Map<String, AlertLatestValueReader.AlertSample> deviceValues,
                                  IotAlertInstance active,
                                  Map<String, IotAlertInstance> activeByKey,
                                  LocalDateTime now, long nowMs,
                                  long stalenessTtlMs, Integer pollIntervalMs,
                                  List<IotAlertInstance> toInsert,
                                  List<IotAlertInstance> toUpdate, List<Long> toDeletePending,
                                  List<AlertNotifyTask> notifyTasks, List<MaintenanceWindow> activeWindows) {
        AlertTriggerMode mode = AlertTriggerMode.of(rule.getTriggerMode());
        if (mode == null) {
            // 模式非法（脏数据）：按「不可判定」计数并跳过——不猜一个模式出来，也不静默无声
            metrics.verdictSkipped(AlertValueVerdict.INVALID_CONDITION);
            return new Outcome(0, 0);
        }
        List<ConditionVerdict> verdicts = new ArrayList<>(group.size());
        boolean anyInRange = false;
        boolean anyOutOfRange = false;
        for (AlertCandidateResolver.Candidate candidate : group) {
            IotAlertRulePoint item = candidate.point();
            AlertLatestValueReader.AlertSample sample = findSample(deviceValues, item.getPropertyId());
            AlertValueVerdict itemVerdict = sample == null
                ? AlertValueVerdict.MISSING
                : AlertRules.verdict(item, sample.value(), sample.quality(), sample.ts(), nowMs,
                    stalenessTtlMs);
            if (itemVerdict.isSkipped()) {
                // 每个不可判定条件各自计数：值班仍能分辨是哪个点位、哪一类原因（与旧实现同口径）
                metrics.verdictSkipped(itemVerdict);
            }
            verdicts.add(new ConditionVerdict(item, sample, itemVerdict));
            anyInRange |= itemVerdict == AlertValueVerdict.IN_RANGE;
            anyOutOfRange |= itemVerdict.isOutOfRange();
        }
        AlertValueVerdict verdict = groupVerdict(verdicts, anyInRange, anyOutOfRange);
        boolean recoverySatisfied = recoverySatisfied(verdicts);
        // 「最严重」的越界条件：其 property_id / trigger_value / threshold_snapshot 落到实例上
        // （排序规则见 mostSevereIndex：越界幅度 → 相对阈值比 → 点位标识字典序，完全确定性可复现）
        int severeIndex = mostSevereIndex(verdicts);
        AlertCandidateResolver.Candidate severeCandidate = group.get(Math.max(severeIndex, 0));
        IotAlertRulePoint point = severeCandidate.point();
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
            // 持续窗口按**整组**校验：窗口内每个采样点都必须「全部条件越界」才成立
            windowSatisfied = verifyDurationWindow(device.getId(), group, active.getStartTs(), now, nowMs,
                pollIntervalMs);
        }
        AlertStateMachine.Decision decision = AlertStateMachine.decide(currentState,
            active == null || active.getConsecutiveCount() == null ? 0 : active.getConsecutiveCount(),
            active == null ? null : active.getStartTs(), verdict, recoverySatisfied, windowSatisfied, mode,
            threshold, pendingTtlSec, now);

        String rawValue = rawValueOf(verdicts, severeIndex);
        String thresholdSnapshot = AlertRules.thresholdSnapshot(point);
        String conditionSnapshot = AlertConditionSnapshot.encode(conditionSnapshots(verdicts));
        // 去重键 =（规则 × 设备）：一次触发只建一条实例，组内各条件的取值落在 condition_snapshot 里
        String dedupKey = AlertRules.groupDedupKey(rule.getId(), device.getId());
        // 不变式守卫（fail-loud）：需要活动实例的四种决策只在 currentState 非空时产生，
        // 而 currentState 非空 ⟺ active 非空 —— 若状态机将来改出其它组合，
        // 下面的分支会 NPE。在此先 loud 失败，不给静默 NPE 机会。
        // （CodeQL 看不出 kind↔active 的关联而报 dereferenced-value；单测锁定状态机侧的不变式。）
        if (active == null && switch (decision.kind()) {
            case DROP_PENDING, PROMOTE, PROGRESS, RESOLVE -> true;
            default -> false;
        }) {
            throw new IllegalStateException("告警决策需要活动实例但 active 为 null：kind=" + decision.kind());
        }
        switch (decision.kind()) {
            case NONE -> {
                return new Outcome(0, 0);
            }
            case CREATE_PENDING, CREATE_FIRING -> {
                IotAlertInstance created = newInstance(rule, device, point, dedupKey, now, decision,
                    rawValue, thresholdSnapshot, conditionSnapshot);
                toInsert.add(created);
                // 僵尸防护（**编辑规则后必须收敛**）：去重键由 (规则,设备,点位) 收紧为 (规则,设备) 后，
                // 老数据里同 (规则,设备) 的旧键活动实例不会再被任何候选命中（新键与旧键不同）⇒
                // 它们会永远停在活动态。此处在本规则本设备**本轮确实命中**新键时，把这些同 (规则,设备)
                // 的旧键活动实例显式收口为 RESOLVED（原因码区分于「条件已恢复」）。
                toUpdate.addAll(orphanActives(activeByKey, rule.getId(), device.getId(), dedupKey, now));
                if (decision.isFiring()) {
                    metrics.triggered();
                    notifyScheduler.schedule(created, rule, device.getId(), AlertNotifyEvent.FIRING, now,
                        activeWindows, notifyTasks);
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
                if (!currentState.canTransitionTo(AlertState.FIRING)) {
                    // 状态转换表在生产路径生效：非法转换**不执行**，但必须可见（数据被人工改动时会走到这里）
                    metrics.verdictSkipped(AlertValueVerdict.INVALID_CONDITION);
                    log.error("[iot] 告警评估：拒绝非法状态转换 {} -> FIRING（instanceId={}）",
                        currentState, active.getId());
                    return new Outcome(0, 0);
                }
                active.setState(AlertState.FIRING.getCode());
                active.setFiringTs(decision.firingTs());
                active.setConsecutiveCount(decision.consecutiveCount());
                active.setTriggerValue(rawValue);
                // 升级为 FIRING 时刷新快照：用户可能在 PENDING 期间改过阈值/条件行，快照必须跟上
                active.setThresholdSnapshot(thresholdSnapshot);
                active.setConditionSnapshot(conditionSnapshot);
                toUpdate.add(active);
                metrics.triggered();
                notifyScheduler.schedule(active, rule, device.getId(), AlertNotifyEvent.FIRING, now,
                    activeWindows, notifyTasks);
                return new Outcome(1, 0);
            }
            case PROGRESS -> {
                active.setConsecutiveCount(decision.consecutiveCount());
                active.setTriggerValue(rawValue);
                // 活动期每轮刷新快照：页面/通知里「当前读到多少」必须是最新一轮的值
                active.setConditionSnapshot(conditionSnapshot);
                toUpdate.add(active);
                notifyScheduler.scheduleRepeat(active, rule, device.getId(), now, activeWindows,
                    notifyTasks);
            }
            case RESOLVE -> {
                if (!currentState.canTransitionTo(AlertState.RESOLVED)) {
                    metrics.verdictSkipped(AlertValueVerdict.INVALID_CONDITION);
                    log.error("[iot] 告警评估：拒绝非法状态转换 {} -> RESOLVED（instanceId={}）",
                        currentState, active.getId());
                    return new Outcome(0, 0);
                }
                active.setState(AlertState.RESOLVED.getCode());
                active.setResolvedTs(now);
                active.setReason(decision.reason() == null ? null : decision.reason().getCode());
                // 恢复时把活动去重键置 NULL：唯一约束随之释放，同一键可以再开一条（「清除 ≠ 删除」）
                active.setActiveDedupKey(null);
                active.setTriggerValue(rawValue);
                active.setConditionSnapshot(conditionSnapshot);
                toUpdate.add(active);
                metrics.resolved();
                notifyScheduler.schedule(active, rule, device.getId(), AlertNotifyEvent.RESOLVED, now,
                    activeWindows, notifyTasks);
                return new Outcome(0, 1);
            }
            default -> throw new IllegalStateException("未处理的状态机动作：" + decision.kind());
        }
        return new Outcome(0, 0);
    }

    /**
     * 合取条件组的**组判定**（AND 语义的唯一入口）。
     *
     * @param verdicts      组内逐条件判定（非空）
     * @param anyInRange    组内是否存在 {@code IN_RANGE}
     * @param anyOutOfRange 组内是否存在 {@code OUT_OF_RANGE}
     * @return 组判定结果
     */
    private static AlertValueVerdict groupVerdict(List<ConditionVerdict> verdicts, boolean anyInRange,
                                                  boolean anyOutOfRange) {
        if (anyInRange) {
            // 合取：任一条「合格的未越界」即整组不成立（不得因其它条件越界而触发）
            return AlertValueVerdict.IN_RANGE;
        }
        if (anyOutOfRange) {
            // 走到这里说明组内没有 IN_RANGE ⇒ 全部条件都是越界或不可判定；只要有一条越界且其余也越界，
            // 才是真 AND 成立。因此必须显式排除「部分越界 + 部分不可判定」——那属于不可判定，不是越界。
            for (ConditionVerdict item : verdicts) {
                if (!item.verdict().isDecidable()) {
                    return item.verdict();
                }
            }
            return AlertValueVerdict.OUT_OF_RANGE;
        }
        // 全是不可判定：取第一条的原因码（状态机对非 OUT/IN 一律「计数与状态都不动」）
        return verdicts.get(0).verdict();
    }

    /**
     * 组恢复是否成立：**存在任一条可判定条件已确定越过自己的恢复门槛（含回差）**。
     *
     * <p><b>为什么是「存在」而不是「全部」</b>：告警条件是组内越界的**合取**（AND）。只要有一条条件
     * 被合格读数证明「已经越过恢复门槛」，合取就**可证伪**——此时无论其余条件取什么值（含不可判定），
     * 整条规则都不再成立，告警必须能收口。反过来要求「全部条件都越过恢复门槛」会让残留在越界区的某一条
     * 条件把告警永久钉住，等价于把 AND 又退化成 OR（那正是本次改造要修掉的形态）。</p>
     *
     * <p><b>不可判定条件的位置</b>：它在两个方向上都**不参与**——既不能充当「已恢复」的证据
     * （{@code MISSING/STALE/…} 不伪装成恢复，与设计 §2.2.4 及既有 T6 口径一致），
     * 也不能阻挡其它条件提供的恢复证据（否则一个坏点位就是永不消解的僵尸告警）。
     * 组内没有任何可判定条件时返回 {@code false}（不敢声称恢复；此时组判定也不可能是 IN_RANGE）。</p>
     *
     * <p><b>回差在这里生效</b>：条件「未越界但仍在回差带内」不算恢复 ⇒ 本条返回 {@code false}，
     * 告警保持（阈值附近的抖动不会产生「触发/恢复」通知风暴）。</p>
     */
    private static boolean recoverySatisfied(List<ConditionVerdict> verdicts) {
        for (ConditionVerdict item : verdicts) {
            if (!item.verdict().isDecidable()) {
                continue;
            }
            AlertValueType valueType = AlertValueType.of(item.point().getValueType());
            BigDecimal parsed = item.sample() == null || valueType == null
                ? null : AlertRules.parseValue(valueType, item.sample().value());
            if (parsed != null && AlertRules.recovered(item.point(), parsed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「最严重」越界条件的下标（写入实例的 property_id / trigger_value / threshold_snapshot）。
     *
     * <p>排序规则（**完全确定性**，不依赖遍历顺序，保证同一份数据每轮得到同一条）：</p>
     * <ol>
     *   <li>候选集 = 组内全部 {@code OUT_OF_RANGE} 条件；一个都没有（组判定不是越界）时取**组内第一条**，
     *       保证实例上的点位列非空且每轮稳定；</li>
     *   <li>过界**幅度**大者胜：上界类（GT/GTE/EQ）为 {@code value − threshold}，下界类（LT/LTE/NE）为
     *       {@code threshold − value}（NE 无方向，取 0）；</li>
     *   <li>幅度相同（如都是恰好卡在阈值上的 EQ）⇒ 相对阈值比 {@code 幅度 / |threshold|} 大者胜
     *       （阈值 100 越 1 与阈值 10 越 1，前者更接近边界、后者更严重）；阈值 0 时跳过本档；</li>
     *   <li>仍相同 ⇒ {@code propertyId} **字典序小者**胜，保证确定性可复现。</li>
     * </ol>
     *
     * @param verdicts 组内逐条件判定（非空）
     * @return 下标（越界集非空时必为越界项；否则 0）
     */
    private static int mostSevereIndex(List<ConditionVerdict> verdicts) {
        int winner = -1;
        BigDecimal winnerMagnitude = null;
        BigDecimal winnerRatio = null;
        for (int index = 0; index < verdicts.size(); index++) {
            ConditionVerdict item = verdicts.get(index);
            if (!item.verdict().isOutOfRange()) {
                continue;
            }
            BigDecimal magnitude = overshoot(item);
            BigDecimal ratio = ratio(item, magnitude);
            if (winner < 0 || isMoreSevere(item, magnitude, ratio, verdicts.get(winner),
                winnerMagnitude, winnerRatio)) {
                winner = index;
                winnerMagnitude = magnitude;
                winnerRatio = ratio;
            }
        }
        return winner < 0 ? 0 : winner;
    }

    /** 过界幅度（无方向或无法计算时返回 0，让下一档比较接手）。 */
    private static BigDecimal overshoot(ConditionVerdict item) {
        AlertOperator operator = AlertOperator.of(item.point().getOperator());
        BigDecimal threshold = item.point().getThreshold();
        AlertValueType valueType = AlertValueType.of(item.point().getValueType());
        BigDecimal value = item.sample() == null || valueType == null
            ? null : AlertRules.parseValue(valueType, item.sample().value());
        if (operator == null || threshold == null || value == null) {
            return BigDecimal.ZERO;
        }
        return switch (operator) {
            case GT, GTE, EQ -> value.subtract(threshold);
            case LT, LTE -> threshold.subtract(value);
            case NE -> BigDecimal.ZERO;
        };
    }

    /** 相对阈值比（阈值 0 时返回 0：本档不启用）。 */
    private static BigDecimal ratio(ConditionVerdict item, BigDecimal magnitude) {
        BigDecimal threshold = item.point().getThreshold();
        if (threshold == null || threshold.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return magnitude.divide(threshold.abs(), 12, RoundingMode.HALF_UP);
    }

    /** 候选是否比当前胜者更严重（幅度 → 相对比 → propertyId 字典序）。 */
    private static boolean isMoreSevere(ConditionVerdict candidate, BigDecimal candidateMagnitude,
                                        BigDecimal candidateRatio, ConditionVerdict current,
                                        BigDecimal currentMagnitude, BigDecimal currentRatio) {
        int byMagnitude = candidateMagnitude.compareTo(currentMagnitude);
        if (byMagnitude != 0) {
            return byMagnitude > 0;
        }
        int byRatio = candidateRatio.compareTo(currentRatio);
        if (byRatio != 0) {
            return byRatio > 0;
        }
        return comparePropertyId(candidate.point().getPropertyId(), current.point().getPropertyId()) < 0;
    }

    /** 点位标识字典序比较（空值排最后；两边都空视为相等）。 */
    private static int comparePropertyId(String left, String right) {
        boolean leftBlank = left == null || left.isBlank();
        boolean rightBlank = right == null || right.isBlank();
        if (leftBlank && rightBlank) {
            return 0;
        }
        if (leftBlank) {
            return 1;
        }
        if (rightBlank) {
            return -1;
        }
        return left.compareTo(right);
    }

    /** 实例的 {@code trigger_value}：取最严重条件的读数（无样本时为空）。 */
    private static String rawValueOf(List<ConditionVerdict> verdicts, int severeIndex) {
        if (verdicts.isEmpty()) {
            return null;
        }
        ConditionVerdict severe = verdicts.get(Math.max(severeIndex, 0));
        return severe.sample() == null ? null : severe.sample().value();
    }

    /** 组内逐条件快照（保序，与候选顺序一致）。 */
    private static List<AlertConditionSnapshot.ConditionReading> conditionSnapshots(
        List<ConditionVerdict> verdicts) {
        List<AlertConditionSnapshot.ConditionReading> readings = new ArrayList<>(verdicts.size());
        for (ConditionVerdict item : verdicts) {
            readings.add(new AlertConditionSnapshot.ConditionReading(item.point().getPropertyId(),
                item.point().getOperator(), item.point().getThreshold(), item.verdict(),
                item.sample() == null ? null : item.sample().value()));
        }
        return readings;
    }

    /**
     * 找出「旧去重键残留」的同 (规则, 设备) 活动实例并收口（僵尸防护；2026-10-15 真 AND 改造配套）。
     *
     * <p><b>为什么必须有</b>：去重键从 {@code rule:device:property} 收紧为 {@code R:rule:device} 之后，
     * 存量活动实例在新代码下**永远不会被任何候选命中**（键不同）⇒ 若只靠「命中才推进」的状态机，
     * 它们会永远停在 {@code FIRING}，页面上是一条永远无法恢复的僵尸告警。
     * 迁移脚本已经把存量键改写掉了，这条路径是**第二道防线**：迁移没跑到（或迁移后才产生的旧键行）
     * 也能收敛。收口而不是删除——「清除 ≠ 删除」。</p>
     *
     * <p>只在本规则本设备**本轮确实命中新键**（正在建新实例 / 正在推进活动实例）时收口，且**跳过**新区
     * 实例自身（{@code activeDedupKey} 与新键相同的绝不在此处理）。原因码用
     * {@link AlertReason#CONDITION_GROUP_MERGED}（不是 {@code RECOVERED}：它并没有恢复）。</p>
     *
     * @param activeByKey 本租户全部活动实例（键 = active_dedup_key）
     * @param ruleId      规则 ID
     * @param deviceId    设备 ID
     * @param newKey      新的条件组去重键
     * @param now         本轮时刻
     * @return 需要批量更新为 RESOLVED 的实例（可能为空）
     */
    private static List<IotAlertInstance> orphanActives(Map<String, IotAlertInstance> activeByKey,
                                                        Long ruleId, Long deviceId, String newKey,
                                                        LocalDateTime now) {
        List<IotAlertInstance> orphans = new ArrayList<>();
        for (IotAlertInstance candidate : activeByKey.values()) {
            if (candidate.getActiveDedupKey() == null || newKey.equals(candidate.getActiveDedupKey())
                || candidate.getRuleId() == null || !candidate.getRuleId().equals(ruleId)
                || candidate.getDeviceId() == null || !candidate.getDeviceId().equals(deviceId)) {
                continue;
            }
            candidate.setState(AlertState.RESOLVED.getCode());
            candidate.setResolvedTs(now);
            candidate.setReason(AlertReason.CONDITION_GROUP_MERGED.getCode());
            candidate.setActiveDedupKey(null);
            orphans.add(candidate);
        }
        return orphans;
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
     * <p><b>除了「点是否越界」，还必须确认窗口**被数据覆盖**</b>（独立复核 2026-10-03 指出原实现只检查
     * 「窗口内**存在的**点都越界」，因此「中途停发数据」也会被判成「持续越界」并误触发）：</p>
     * <ul>
     *   <li>查询结果达到 {@code limit} ⇒ 可能被截断 ⇒ **不可求值**（计数 {@code window_uncovered}）；</li>
     *   <li>首点距窗口起点、末点距 now、以及相邻点之间的间隔超过容忍度（{@code max(2 × 采集周期, 15s)}）
     *       ⇒ 窗口存在缺口 ⇒ **不可求值**；</li>
     *   <li>窗口内的点必须**（对组内每个条件都）全部可判定且全部越界**才算满足
     *       （任一点的任一条件不可判定 ⇒ 不可求值；任一点的任一条件未越界 ⇒ 不成立）。</li>
     * </ul>
     *
     * <p><b>真 AND 的窗口口径（2026-10-15）</b>：DURATION 模式下「持续 T 秒越界」的语义现在是
     * <b>整组持续成立</b>——窗口内每一个采样点都必须满足「组内全部条件都越界」。因此这里对**每个点**
     * 都要把组内每个条件判一遍；任一点有一条条件不可判定即整窗不可求值（宁可不触发，也不误触发）。</p>
     *
     * <p>窗口内的点**天然是历史点**，故这里不做「相对 now 的陈旧判定」：那个判据回答的是
     * 「我当作当前值的点是否新鲜」，而本方法的时间范围已由窗口本身（startTs ~ now）界定
     * （陈旧判定只用于「当前值」路径，见调用方）。</p>
     *
     * @param group 该规则在该设备上的全部点位条件（非空）
     * @return {@code TRUE} = 窗口被完整覆盖且每个点的全部条件都越界；{@code FALSE} = 存在未越界的点；
     *         {@code null} = **不可求值**（库读不到 / 无点 / 有不可判定点 / 窗口未被覆盖）
     */
    private Boolean verifyDurationWindow(Long deviceId, List<AlertCandidateResolver.Candidate> group,
                                         LocalDateTime startTs, LocalDateTime now, long nowMs,
                                         Integer pollIntervalMs) {
        TimeSeriesQueryReq req = new TimeSeriesQueryReq();
        // 时序接口一次只查一个点位 ⇒ 逐条件查（条件行数有上限 max-points-per-rule），
        // 且只在「PENDING 已满 T 秒待升级」这一刻触发，不是每轮每设备。
        List<TimeSeriesPointResp>[] series = new List[group.size()];
        for (int index = 0; index < group.size(); index++) {
            String propertyId = group.get(index).point().getPropertyId();
            req.setPropertyId(propertyId);
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
                    + "deviceId={} property={}", deviceId, LogSanitizer.sanitize(propertyId));
                log.debug("[iot] 持续窗口校验失败明细", ex);
                return null;
            }
            if (points.isEmpty()) {
                // 窗口内一个点都没有 ⇒ 无法证明「持续越界」（**不是**「没有越界」）
                metrics.timeseriesFailed();
                return null;
            }
            if (points.size() >= TimeSeriesQueryReq.MAX_LIMIT) {
                // 达到返回上限 ⇒ 无法确认窗口被覆盖（可能截断掉了后半段），宁可不触发
                metrics.windowUncovered();
                log.warn("[iot] 告警评估：持续窗口点数达到查询上限 {}，无法确认覆盖，本轮不改状态："
                    + "deviceId={} property={}", TimeSeriesQueryReq.MAX_LIMIT, deviceId,
                    LogSanitizer.sanitize(propertyId));
                return null;
            }
            if (!isWindowCovered(points, toEpochMillis(startTs), nowMs, pollIntervalMs)) {
                metrics.windowUncovered();
                log.warn("[iot] 告警评估：持续窗口未被数据覆盖（首尾或中间有缺口），本轮不改状态："
                    + "deviceId={} property={}", deviceId, LogSanitizer.sanitize(propertyId));
                return null;
            }
            series[index] = points;
        }
        // 组内每个条件的采样点数不一定相同（上报频率可能不同）⇒ 逐点按**采样时刻**对齐：
        // 只有「同一时刻全部条件都越界」才继续；时刻对不齐一律不可求值（不做「就近取点」的近似）。
        List<TimeSeriesPointResp> reference = series[0];
        for (TimeSeriesPointResp referencePoint : reference) {
            for (int index = 0; index < series.length; index++) {
                TimeSeriesPointResp item = pointAt(series[index], referencePoint.ts());
                if (item == null) {
                    // 该时刻没有这一路条件的采样 ⇒ 无法证明「同一时刻整组越界」
                    metrics.windowUncovered();
                    log.warn("[iot] 告警评估：持续窗口组内采样时刻不对齐（无法证明整组持续越界），"
                        + "本轮不改状态：deviceId={} ts={}", deviceId, referencePoint.ts());
                    return null;
                }
                // 窗口内的点是**历史点**（最早的在 startTs，可能已超过陈旧 TTL）⇒ 传 0 关闭陈旧判定，
                // 只判「可判定 / 越界」；覆盖完整性由 isWindowCovered 单独负责
                AlertValueVerdict verdict = AlertRules.verdict(group.get(index).point(), item.value(),
                    item.quality(), item.ts(), nowMs, 0L);
                if (!verdict.isDecidable()) {
                    // 严格口径：窗口里只要有一个不可判定的点，就不声称「持续越界」（宁可不触发，也不误触发）
                    metrics.verdictSkipped(verdict);
                    return null;
                }
                if (!verdict.isOutOfRange()) {
                    // 合取：任一点上任一条件未越界 ⇒ 整窗不成立
                    return Boolean.FALSE;
                }
            }
        }
        return Boolean.TRUE;
    }

    /** 取指定时刻的采样点（无则 {@code null}；不做就近近似）。 */
    private static TimeSeriesPointResp pointAt(List<TimeSeriesPointResp> points, Long ts) {
        if (ts == null) {
            return null;
        }
        for (TimeSeriesPointResp item : points) {
            if (ts.equals(item.ts())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 窗口是否被数据完整覆盖：首点贴近起点、末点贴近 now、相邻点间隔不超过容忍度。
     *
     * <p>容忍度 = {@code max(2 × 生效采集周期, ypbin.alert.window-gap-floor-ms)}；
     * 生效采集周期优先取设备上报值，取不到时用窗口内实测间距的中位数（见 {@link #medianGapMs}）。</p>
     */
    private boolean isWindowCovered(List<TimeSeriesPointResp> points, long startMs, long nowMs,
                                    Integer pollIntervalMs) {
        // 生效采集周期：优先设备上报值；取不到则用**窗口内实测间距的中位数**（自适应）。
        // 固定小容忍度会让「周期未知但实际每 60s 上报」的设备每轮都判「未覆盖」⇒ 持续模式永不触发
        // （独立复核 2026-10-03 M1）。自适应之后，60s 周期的设备容忍度自然放宽到 120s，
        // 而「中途停发数据」仍会被首尾/中间缺口检测抓住（缺口远大于中位间距）。
        long effectiveIntervalMs = pollIntervalMs == null || pollIntervalMs <= 0
            ? medianGapMs(points) : pollIntervalMs;
        long toleranceMs = Math.max(2L * effectiveIntervalMs, properties.getWindowGapFloorMs());
        Long first = points.get(0).ts();
        Long last = points.get(points.size() - 1).ts();
        if (first == null || last == null) {
            return false;
        }
        if (first - startMs > toleranceMs || nowMs - last > toleranceMs) {
            return false;
        }
        long previous = first;
        for (TimeSeriesPointResp item : points) {
            Long current = item.ts();
            if (current == null || current - previous > toleranceMs) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    /**
     * 窗口内相邻点间距的**中位数**（毫秒）。
     *
     * <p>用中位数而不是平均值/最大值：中途停发数据会让「最大值」被缺口本身抬高（自证清白），
     * 而中位数对单个大缺口不敏感，能代表该设备**正常的采集节奏**。</p>
     */
    private static long medianGapMs(List<TimeSeriesPointResp> points) {
        List<Long> gaps = new ArrayList<>(points.size());
        Long previous = null;
        for (TimeSeriesPointResp item : points) {
            Long current = item.ts();
            if (current == null) {
                continue;
            }
            if (previous != null && current - previous > 0) {
                gaps.add(current - previous);
            }
            previous = current;
        }
        if (gaps.isEmpty()) {
            return 0L;
        }
        gaps.sort(Long::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    /** 建实例（PENDING 或 FIRING；id 预生成，便于同事务内写通知行）。 */
    private IotAlertInstance newInstance(IotAlertRule rule, IotDevice device, IotAlertRulePoint point,
                                         String dedupKey, LocalDateTime now,
                                         AlertStateMachine.Decision decision, String rawValue,
                                         String thresholdSnapshot, String conditionSnapshot) {
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
        instance.setThresholdSnapshot(thresholdSnapshot);
        instance.setConditionSnapshot(conditionSnapshot);
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

    /** 批量落库 + 排队通知（写入只在有变化时发生；稳态下几乎为零）。 */
    private int flush(List<IotAlertInstance> toInsert, List<IotAlertInstance> toUpdate,
                      List<Long> toDeletePending, List<AlertNotifyTask> notifyTasks) {
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
        List<IotAlertNotification> rows = notifyScheduler.planRows(notifyTasks);
        if (rows.isEmpty()) {
            return 0;
        }
        int inserted = notificationMapper.insertBatchIdempotent(rows);
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

    private static int intOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long toEpochMillis(LocalDateTime time) {
        return time.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
    }
}
