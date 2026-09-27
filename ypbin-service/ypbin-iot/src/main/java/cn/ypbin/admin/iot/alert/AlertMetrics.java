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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 告警能力的**全部指标出口**（设计 §3.5-N1/N2）。
 *
 * <p><b>为什么指标要集中在一处</b>：设计把「评估器悄悄死了」列为必须防的失效形态
 * （§2.2.4：否则就是又一次「静默零写入」，本仓已经踩过一次）。指标名散落在各实现类里时，
 * 最容易发生的退化是「某类不可判定忘了计数」——单看代码看不出来。集中在这里之后，
 * {@link AlertValueVerdict#skippedMetricNames()} 与启动自检可以逐条比对「判定结果类型 ↔ 指标是否存在」。</p>
 *
 * <p>三类指标的语义边界（值班口径）：</p>
 * <ul>
 *   <li>{@link #METRIC_LAST_SUCCESS_TS} / {@link #METRIC_LAG}：评估器**活性**。lag 持续增长 = 评估器没在跑；</li>
 *   <li>{@code ...evaluate.skipped_*}：**不可判定**的五类原因（值读到了但不能用于判定）；</li>
 *   <li>{@code ...evaluate.redis_failed / rule_load_failed / timeseries_failed}：**读不到**的三类原因
 *       （判定根本无法进行）。两者必须分开看：前者要改设备/规则，后者要修平台。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertMetrics {

    /** 评估轮次计数。 */
    public static final String METRIC_ROUNDS = "iot.alert.evaluate.rounds";

    /** 一轮评估的耗时。 */
    public static final String METRIC_ROUND_DURATION = "iot.alert.evaluate.round.duration";

    /** 最近一次成功轮次的时刻（epoch 毫秒）——评估器活性的**主判据**。 */
    public static final String METRIC_LAST_SUCCESS_TS = "iot.alert.evaluate.last_success_ts";

    /** 距最近一次成功轮次的时长（毫秒）——可直接对着评估周期看是否卡死。 */
    public static final String METRIC_LAG = "iot.alert.evaluate.lag";

    /** 轮次**不健康**计数（有租户失败或 Redis 失败 ⇒ 不刷新 last_success_ts）。 */
    public static final String METRIC_ROUND_FAILED = "iot.alert.evaluate.round.failed";

    /** 持续窗口**未被数据覆盖**（点数截断 / 首尾缺口 / 中间断档）导致的不可求值计数。 */
    public static final String METRIC_WINDOW_UNCOVERED = "iot.alert.evaluate.window_uncovered";

    /** 本轮评估的设备数。 */
    public static final String METRIC_DEVICES = "iot.alert.evaluate.devices";

    /** 新触发（进入 FIRING）计数。 */
    public static final String METRIC_TRIGGERED = "iot.alert.evaluate.triggered";

    /** 恢复（进入 RESOLVED）计数。 */
    public static final String METRIC_RESOLVED = "iot.alert.evaluate.resolved";

    /** 抖动被吸收（PENDING 期间回落而丢弃候选）计数。 */
    public static final String METRIC_FLAPPED = "iot.alert.evaluate.flapped";

    /** 候选超过 pending TTL 被放弃计数。 */
    public static final String METRIC_PENDING_EXPIRED = "iot.alert.evaluate.pending_expired";

    /** 单轮设备数超过上限被截断（跨轮滚动）计数。 */
    public static final String METRIC_TRUNCATED = "iot.alert.evaluate.truncated";

    /** 并发插入撞唯一键（去重生效的**正向证据**）计数。 */
    public static final String METRIC_DEDUP_CONFLICT = "iot.alert.evaluate.dedup_conflict";

    /** Redis 读失败（本轮跳过判定）。 */
    public static final String METRIC_REDIS_FAILED = "iot.alert.evaluate.redis_failed";

    /** 规则查询失败（本轮整体放弃）。 */
    public static final String METRIC_RULE_LOAD_FAILED = "iot.alert.evaluate.rule_load_failed";

    /** 时序库查询失败（窗口类规则本轮的窗口判定不可求值）。 */
    public static final String METRIC_TIMESERIES_FAILED = "iot.alert.evaluate.timeseries_failed";

    /** 单个租户评估失败（该租户本轮放弃，其它租户照常）。 */
    public static final String METRIC_TENANT_FAILED = "iot.alert.evaluate.tenant_failed";

    /** 生成的待投递通知条数。 */
    public static final String METRIC_NOTIFY_QUEUED = "iot.alert.notify.queued";

    /** 因静默（规则窗口 / 维护窗口 / 实例级静默）而**未**生成通知的次数。 */
    public static final String METRIC_NOTIFY_SILENCED = "iot.alert.notify.silenced";

    /** 因全局限流而延后投递的通知条数（**延后不丢弃**）。 */
    public static final String METRIC_NOTIFY_THROTTLED = "iot.alert.notify.throttled";

    /** 投递成功条数。 */
    public static final String METRIC_NOTIFY_SENT = "iot.alert.notify.sent";

    /** 投递失败条数（仍会重试）。 */
    public static final String METRIC_NOTIFY_FAILED = "iot.alert.notify.failed";

    /** 重试耗尽而放弃条数。 */
    public static final String METRIC_NOTIFY_GIVEN_UP = "iot.alert.notify.given_up";

    /** 断档事件映射为告警实例的条数。 */
    public static final String METRIC_OUTAGE_MAPPED = "iot.alert.outage.mapped";

    /** 断档恢复同步为告警恢复的条数。 */
    public static final String METRIC_OUTAGE_RESOLVED = "iot.alert.outage.resolved";

    /** 保留清理删除的告警实例行数。 */
    public static final String METRIC_RETENTION_DELETED = "iot.alert.retention.deleted";

    private final Counter rounds;
    private final Counter roundFailed;
    private final Counter windowUncovered;
    private final Timer roundDuration;
    private final Counter devices;
    private final Counter triggered;
    private final Counter resolved;
    private final Counter flapped;
    private final Counter pendingExpired;
    private final Counter truncated;
    private final Counter dedupConflict;
    private final Counter redisFailed;
    private final Counter ruleLoadFailed;
    private final Counter timeseriesFailed;
    private final Counter tenantFailed;
    private final Counter notifyQueued;
    private final Counter notifySilenced;
    private final Counter notifyThrottled;
    private final Counter notifySent;
    private final Counter notifyFailed;
    private final Counter notifyGivenUp;
    private final Counter outageMapped;
    private final Counter outageResolved;
    private final Counter retentionDeleted;

    /** 按判定结果分派的「跳过」计数（键 = {@link AlertValueVerdict}）。 */
    private final Map<AlertValueVerdict, Counter> skippedCounters = new LinkedHashMap<>();

    /** 最近一次成功轮次时刻（epoch 毫秒；0 = 尚未成功过）。 */
    private final AtomicLong lastSuccessTs = new AtomicLong();

    /**
     * 构造并注册全部指标。
     *
     * @param registry 指标注册表
     */
    public AlertMetrics(MeterRegistry registry) {
        this.rounds = Counter.builder(METRIC_ROUNDS).description("告警评估轮次").register(registry);
        this.roundFailed = Counter.builder(METRIC_ROUND_FAILED)
            .description("告警评估轮次不健康（有租户失败或 Redis 读取失败）").register(registry);
        this.windowUncovered = Counter.builder(METRIC_WINDOW_UNCOVERED)
            .description("持续窗口未被数据覆盖，无法判定「持续 N 秒」").register(registry);
        this.roundDuration = Timer.builder(METRIC_ROUND_DURATION).description("告警评估单轮耗时")
            .register(registry);
        this.devices = Counter.builder(METRIC_DEVICES).description("告警评估处理的设备数（按轮累计）")
            .register(registry);
        this.triggered = Counter.builder(METRIC_TRIGGERED).description("告警进入 FIRING 的次数")
            .register(registry);
        this.resolved = Counter.builder(METRIC_RESOLVED).description("告警进入 RESOLVED 的次数")
            .register(registry);
        this.flapped = Counter.builder(METRIC_FLAPPED)
            .description("抖动被吸收（PENDING 回落）的次数").register(registry);
        this.pendingExpired = Counter.builder(METRIC_PENDING_EXPIRED)
            .description("候选超过 pending TTL 被放弃的次数").register(registry);
        this.truncated = Counter.builder(METRIC_TRUNCATED)
            .description("单轮设备数超上限被截断（跨轮滚动）的次数").register(registry);
        this.dedupConflict = Counter.builder(METRIC_DEDUP_CONFLICT)
            .description("并发插入撞唯一键（去重生效）的次数").register(registry);
        this.redisFailed = Counter.builder(METRIC_REDIS_FAILED)
            .description("最新值读取失败，本轮跳过判定的次数").register(registry);
        this.ruleLoadFailed = Counter.builder(METRIC_RULE_LOAD_FAILED)
            .description("规则加载失败，本轮整体放弃的次数").register(registry);
        this.timeseriesFailed = Counter.builder(METRIC_TIMESERIES_FAILED)
            .description("时序库查询失败，窗口类规则本轮不可求值的次数").register(registry);
        this.tenantFailed = Counter.builder(METRIC_TENANT_FAILED)
            .description("单租户评估失败（该租户本轮放弃）的次数").register(registry);
        this.notifyQueued = Counter.builder(METRIC_NOTIFY_QUEUED)
            .description("生成的待投递通知条数").register(registry);
        this.notifySilenced = Counter.builder(METRIC_NOTIFY_SILENCED)
            .description("因静默而未生成通知的次数").register(registry);
        this.notifyThrottled = Counter.builder(METRIC_NOTIFY_THROTTLED)
            .description("因全局限流而延后投递的通知条数").register(registry);
        this.notifySent = Counter.builder(METRIC_NOTIFY_SENT).description("通知投递成功条数")
            .register(registry);
        this.notifyFailed = Counter.builder(METRIC_NOTIFY_FAILED).description("通知投递失败条数")
            .register(registry);
        this.notifyGivenUp = Counter.builder(METRIC_NOTIFY_GIVEN_UP).description("通知重试耗尽放弃条数")
            .register(registry);
        this.outageMapped = Counter.builder(METRIC_OUTAGE_MAPPED)
            .description("断档事件映射为告警实例的条数").register(registry);
        this.outageResolved = Counter.builder(METRIC_OUTAGE_RESOLVED)
            .description("断档恢复同步为告警恢复的条数").register(registry);
        this.retentionDeleted = Counter.builder(METRIC_RETENTION_DELETED)
            .description("保留清理删除的告警实例行数").register(registry);
        for (AlertValueVerdict verdict : AlertValueVerdict.values()) {
            if (verdict.getMetricName() == null) {
                continue;
            }
            skippedCounters.put(verdict, Counter.builder(verdict.getMetricName())
                .description("告警评估不可判定：" + verdict.getDesc()).register(registry));
        }
        Gauge.builder(METRIC_LAST_SUCCESS_TS, lastSuccessTs, AtomicLong::doubleValue)
            .description("最近一次成功的告警评估轮次时刻（epoch 毫秒）").register(registry);
        Gauge.builder(METRIC_LAG, this, AlertMetrics::currentLagMs)
            .description("距最近一次成功评估轮次的毫秒数（评估器活性判据）").register(registry);
    }

    /** 当前滞后毫秒；从未成功过时返回 {@code -1}（**不用 0**：0 与「刚刚跑过」读数相同）。 */
    private double currentLagMs() {
        long last = lastSuccessTs.get();
        return last == 0L ? -1d : (double) (System.currentTimeMillis() - last);
    }

    /** 记录一轮评估开始。 */
    public void roundStarted() {
        rounds.increment();
    }

    /** 记录一轮评估耗时。 */
    public void recordRoundDuration(Duration duration) {
        roundDuration.record(duration);
    }

    /**
     * 记录一轮评估成功（更新活性时刻）。
     *
     * <p><b>只有「没有任何租户失败、也没有 Redis 读取失败」才算成功</b>（独立复核 2026-10-03 指出：
     * 原实现无条件刷新，导致「所有租户都失败」时 {@code last_success_ts} 仍显示刚跑过、{@code lag} 恒小，
     * 这正是设计 §2.2.4 要防的「评估器悄悄死了」）。失败时**不刷新**活性时刻 ⇒ {@code lag} 持续增长，
     * 值班据此判「评估器不健康」。</p>
     */
    public void roundSucceeded() {
        lastSuccessTs.set(System.currentTimeMillis());
    }

    /** 记录一轮评估**不健康**（不刷新活性时刻，让 lag 增长成为可见信号）。 */
    public void roundFailed() {
        roundFailed.increment();
    }

    /** 记录持续窗口未被数据覆盖。 */
    public void windowUncovered() {
        windowUncovered.increment();
    }

    /** 记录处理设备数。 */
    public void addDevices(int count) {
        if (count > 0) {
            devices.increment(count);
        }
    }

    /** 记录新触发。 */
    public void triggered() {
        triggered.increment();
    }

    /** 记录恢复。 */
    public void resolved() {
        resolved.increment();
    }

    /** 批量记录恢复（规则停用时一次性收口多条活动实例）。 */
    public void resolvedBatch(int count) {
        if (count > 0) {
            resolved.increment(count);
        }
    }

    /** 记录抖动被吸收。 */
    public void flapped() {
        flapped.increment();
    }

    /** 记录候选超 TTL 放弃。 */
    public void pendingExpired() {
        pendingExpired.increment();
    }

    /** 记录设备数截断。 */
    public void truncated() {
        truncated.increment();
    }

    /** 记录去重键冲突（去重生效）。 */
    public void dedupConflict(int count) {
        if (count > 0) {
            dedupConflict.increment(count);
        }
    }

    /** 记录 Redis 读失败。 */
    public void redisFailed() {
        redisFailed.increment();
    }

    /** 记录规则加载失败。 */
    public void ruleLoadFailed() {
        ruleLoadFailed.increment();
    }

    /** 记录时序库查询失败。 */
    public void timeseriesFailed() {
        timeseriesFailed.increment();
    }

    /** 记录单租户评估失败。 */
    public void tenantFailed() {
        tenantFailed.increment();
    }

    /** 按判定结果记录「跳过」。 */
    public void verdictSkipped(AlertValueVerdict verdict) {
        Counter counter = skippedCounters.get(verdict);
        if (counter != null) {
            counter.increment();
        }
    }

    /** 记录生成的通知条数。 */
    public void notifyQueued(int count) {
        if (count > 0) {
            notifyQueued.increment(count);
        }
    }

    /** 记录因静默未生成通知。 */
    public void notifySilenced() {
        notifySilenced.increment();
    }

    /** 记录因限流延后投递。 */
    public void notifyThrottled(int count) {
        if (count > 0) {
            notifyThrottled.increment(count);
        }
    }

    /** 记录投递成功。 */
    public void notifySent() {
        notifySent.increment();
    }

    /** 记录投递失败。 */
    public void notifyFailed() {
        notifyFailed.increment();
    }

    /** 记录放弃投递。 */
    public void notifyGivenUp() {
        notifyGivenUp.increment();
    }

    /** 记录断档映射。 */
    public void outageMapped(int count) {
        if (count > 0) {
            outageMapped.increment(count);
        }
    }

    /** 记录断档恢复同步。 */
    public void outageResolved(int count) {
        if (count > 0) {
            outageResolved.increment(count);
        }
    }

    /** 记录保留清理删除。 */
    public void retentionDeleted(int count) {
        if (count > 0) {
            retentionDeleted.increment(count);
        }
    }

    /** 已注册的「跳过」指标名（启动自检与用例用它证明六类计数真的都在）。 */
    public Map<AlertValueVerdict, String> registeredSkipMetrics() {
        Map<AlertValueVerdict, String> result = new LinkedHashMap<>();
        skippedCounters.forEach((verdict, counter) -> result.put(verdict, counter.getId().getName()));
        return result;
    }
}
