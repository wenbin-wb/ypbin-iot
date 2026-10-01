/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import cn.ypbin.admin.iot.entity.IotPlatformAlert;
import cn.ypbin.admin.iot.mapper.IotPlatformAlertMapper;
import cn.ypbin.starter.core.util.LogSanitizer;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台自告警服务（看板 #10，设计 `docs/PLATFORM-ALERTING-DESIGN.md` §4/§5）。
 *
 * <p><b>一轮做什么</b>：读指标快照 → 逐规则判定（纯函数）→ 落库（开单 / 续期 / 收口）。</p>
 *
 * <p><b>🔴 一期"只落库不通知"</b>（设计 §5 与 §2.1）：阈值刚由实测确定，尚未经观察期验证；
 * 此时开通通知若阈值偏紧会**刷屏**，而"无人再看的告警等于没做"。
 * ⇒ 一期只落库 + 记日志，通知待观察期后再开（`ypbin.platform-alert.notify-enabled` 预留开关，
 * 默认 false 且**当前实现不投递**）。</p>
 *
 * <p><b>为什么读指标不做异常兜底</b>：读不到就留 `null` ⇒ 判定为 `UNKNOWN` ⇒ 不告警但记日志。
 * 刻意**不**把"读指标失败"当成告警：那样指标系统自身抖动会造成告警风暴，
 * 而真正的判断依据（`UNKNOWN` + 日志）已经足够让人发现采集出了问题。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Service
public class PlatformAlertService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAlertService.class);

    /** 连续多少轮异常才从 `PENDING` 升 `FIRING`（抗抖动；一期固定值，不做配置化以免过早抽象）。 */
    static final int FIRING_THRESHOLD_ROUNDS = 2;

    private final MeterRegistry meterRegistry;
    private final IotPlatformAlertMapper alertMapper;
    private final PlatformAlertNotifier notifier;

    /** 上一轮基线（进程内）。**刻意不用 DB**：平台自告警不应依赖 DB 才能判断
     * （见设计 §6 R2 "监控者先坏"）；进程重启后基线丢失，判定退化为首次运行分支。 */
    private volatile PlatformHealthBaseline baseline = PlatformHealthBaseline.none();

    /** 各规则连续异常轮次（内存；重启丢失可接受——重启后从 PENDING 重新计）。 */
    private final Map<PlatformHealthRule, Integer> consecutiveRounds = new ConcurrentHashMap<>();

    private final Counter roundsCounter;

    private final Counter firingCounter;

    private final Counter unknownCounter;

    public PlatformAlertService(MeterRegistry meterRegistry, IotPlatformAlertMapper alertMapper,
                                 PlatformAlertNotifier notifier) {
        this.meterRegistry = meterRegistry;
        this.alertMapper = alertMapper;
        this.notifier = notifier;
        this.roundsCounter = Counter.builder("iot.platform_alert.rounds")
            .description("平台自告警判定轮次").register(meterRegistry);
        this.firingCounter = Counter.builder("iot.platform_alert.firing")
            .description("平台自告警判定为触发的次数").register(meterRegistry);
        this.unknownCounter = Counter.builder("iot.platform_alert.unknown")
            .description("平台自告警不可判定的次数（指标取不到）").register(meterRegistry);
    }

    /**
     * 执行一轮判定并落库。
     *
     * @return 本轮各规则的判定结果（供测试与观测）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<PlatformHealthRule, PlatformHealthVerdict> evaluateOnce() {
        roundsCounter.increment();
        PlatformHealthSnapshot snapshot = readSnapshot();
        Map<PlatformHealthRule, PlatformHealthVerdict> verdicts = new LinkedHashMap<>();

        for (PlatformHealthRule rule : PlatformHealthRule.values()) {
            PlatformHealthVerdict verdict = PlatformHealthEvaluator.evaluate(rule, snapshot, baseline);
            verdicts.put(rule, verdict);
            applyVerdict(rule, verdict);
        }
        // 基线在**一轮全部判完之后**统一推进：若逐条推进，同轮不同规则看到不同基线
        baseline = PlatformHealthBaseline.from(snapshot);
        return verdicts;
    }

    /**
     * 把一条判定落到库上（开单 / 续期 / 收口）。
     *
     * @param rule    规则
     * @param verdict 判定
     */
    private void applyVerdict(PlatformHealthRule rule, PlatformHealthVerdict verdict) {
        switch (verdict.state()) {
            case UNKNOWN -> {
                // 不可判定**不动库**：既不告警也不收口。若此时收口，会把一条真实告警
                // 因"指标抖动取不到"而误标记为已恢复——那是比不告警更糟的错误信息。
                unknownCounter.increment();
                log.warn("[iot] 平台健康规则不可判定（指标取不到，不改动告警状态）：rule={}, {}",
                    rule.getCode(), LogSanitizer.sanitize(verdict.summary()));
            }
            case FIRING -> recordFiring(rule, verdict);
            case HEALTHY -> resolveIfActive(rule, verdict);
        }
    }

    /**
     * 记录一次异常（首次开单，后续续期；连续 N 轮后升 FIRING）。
     *
     * @param rule    规则
     * @param verdict 判定
     */
    private void recordFiring(PlatformHealthRule rule, PlatformHealthVerdict verdict) {
        firingCounter.increment();
        int rounds = consecutiveRounds.merge(rule, 1, Integer::sum);
        PlatformAlertState state = rounds >= FIRING_THRESHOLD_ROUNDS
            ? PlatformAlertState.FIRING : PlatformAlertState.PENDING;
        LocalDateTime now = LocalDateTime.now();

        IotPlatformAlert alert = new IotPlatformAlert();
        alert.setId(IdWorker.getId());
        alert.setRuleCode(rule.getCode());
        alert.setDedupKey(buildDedupKey(rule));
        // 活动去重键 = 去重键（恢复时被置 NULL）⇒ 同键至多一条活动告警
        alert.setActiveDedupKey(buildDedupKey(rule));
        alert.setSeverity(rule.getSeverity().getCode());
        alert.setState(state.getCode());
        alert.setSummary(verdict.summary());
        alert.setMetricSnapshot(buildSnapshotJson(verdict));
        alert.setStartTs(now);
        alert.setFiringTs(now);
        alert.setLastVerdict(verdict.state().getCode());
        alert.setObservedRounds(rounds);

        alertMapper.upsertActive(alert);
        if (state == PlatformAlertState.FIRING && rounds == FIRING_THRESHOLD_ROUNDS) {
            // 升到 FIRING 的当轮发一次（续期不再发 ⇒ 防刷屏；恢复再发一次 RESOLVED）
            notifier.notifyFiring(rule, verdict.summary(), buildSnapshotJson(verdict));
        }
        log.warn("[iot] 平台健康异常：rule={}, state={}, {}",
            rule.getCode(), state.getCode(), LogSanitizer.sanitize(verdict.summary()));
    }

    /**
     * 记录一次正常（若存在活动告警则收口）。
     *
     * @param rule    规则
     * @param verdict 判定
     */
    private void resolveIfActive(PlatformHealthRule rule, PlatformHealthVerdict verdict) {
        consecutiveRounds.put(rule, 0);
        IotPlatformAlert active = findActive(rule);
        if (active == null) {
            return;
        }
        alertMapper.resolve(active.getId(), PlatformAlertState.RESOLVED.getCode(),
            "已恢复：" + verdict.summary(), LocalDateTime.now());
        notifier.notifyResolved(rule, verdict.summary());
        log.info("[iot] 平台健康恢复正常，告警收口：rule={}, {}",
            rule.getCode(), LogSanitizer.sanitize(verdict.summary()));
    }

    /**
     * 查某规则的活动告警。
     *
     * @param rule 规则
     * @return 活动告警；没有返回 `null`
     */
    private IotPlatformAlert findActive(PlatformHealthRule rule) {
        return alertMapper.selectOne(new LambdaQueryWrapper<IotPlatformAlert>()
            .eq(IotPlatformAlert::getRuleCode, rule.getCode())
            .isNotNull(IotPlatformAlert::getActiveDedupKey)
            .last("LIMIT 1"));
    }

    /**
     * 去重键（平台级：不含设备/点位——见设计 §1.3）。
     *
     * @param rule 规则
     * @return 去重键
     */
    private String buildDedupKey(PlatformHealthRule rule) {
        return rule.getCode();
    }

    /**
     * 指标快照 JSON（事后排障依据，见实体注释）。
     *
     * @param verdict 判定
     * @return JSON（**手工拼，不引 JSON 库**：结构固定且字段都有类型约束）
     */
    private String buildSnapshotJson(PlatformHealthVerdict verdict) {
        Long observed = verdict.observed();
        return "{\"rule\":\"" + verdict.rule().getCode()
            + "\",\"state\":\"" + verdict.state().getCode()
            + "\",\"observed\":" + (observed == null ? "null" : observed)
            + ",\"summary\":\"" + verdict.summary().replace("\"", "'") + "\"}";
    }

    /**
     * 读指标快照。
     *
     * <p>只读**枚举出的固定指标**（不遍历全部 meter）：遍历会把 registry 里成百上千的
     * 无关指标也读一遍，纯属浪费（设计 §6 R3）。</p>
     *
     * @return 快照（取不到的留 `null`）
     */
    private PlatformHealthSnapshot readSnapshot() {
        Map<String, Long> meters = new LinkedHashMap<>();
        meters.put(PlatformMetrics.METRIC_EVALUATE_LAG, readGauge(PlatformMetrics.METRIC_EVALUATE_LAG));
        meters.put(PlatformMetrics.METRIC_ROUND_FAILED, readCounter(PlatformMetrics.METRIC_ROUND_FAILED));
        meters.put(PlatformMetrics.METRIC_NOTIFY_FAILED,
            readCounter(PlatformMetrics.METRIC_NOTIFY_FAILED));
        meters.put(PlatformMetrics.METRIC_INGEST_UNMAPPED,
            readCounter(PlatformMetrics.METRIC_INGEST_UNMAPPED));
        meters.put(PlatformMetrics.METRIC_INGEST_ORPHAN,
            readCounter(PlatformMetrics.METRIC_INGEST_ORPHAN));
        return PlatformHealthSnapshot.fromMeters(meters);
    }

    /**
     * 读 gauge 当前值。
     *
     * @param name 指标名
     * @return 值；取不到返回 `null`
     */
    private Long readGauge(String name) {
        Meter meter = findMeter(name);
        if (!(meter instanceof Gauge gauge)) {
            return null;
        }
        double value = gauge.value();
        return Double.isFinite(value) ? (long) value : null;
    }

    /**
     * 读 counter 当前值。
     *
     * @param name 指标名
     * @return 值；取不到返回 `null`
     */
    private Long readCounter(String name) {
        Meter meter = findMeter(name);
        if (!(meter instanceof Counter counter)) {
            return null;
        }
        return (long) counter.count();
    }

    /**
     * 按名精确找 meter（**限定无 tag** —— 本项目这些指标都不带 tag；带 tag 的同名指标须显式区分，
     * 否则会把某个 tag 维度的值当成全局值）。
     *
     * @param name 指标名
     * @return meter；找不到返回 `null`
     */
    private Meter findMeter(String name) {
        for (Meter meter : meterRegistry.getMeters()) {
            if (meter.getId().getName().equals(name) && meter.getId().getTags().isEmpty()) {
                return meter;
            }
        }
        return null;
    }
}
