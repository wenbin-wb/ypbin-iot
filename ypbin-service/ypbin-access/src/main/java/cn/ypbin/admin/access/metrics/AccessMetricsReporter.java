/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.metrics;

import cn.ypbin.admin.iot.access.metrics.AccessMetricsSnapshotResp;
import cn.ypbin.admin.iot.access.metrics.IAccessMetricsClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把 access 侧关键指标**定期推送**给 iot 的平台自告警（R8-2 / R8-8 / 存活性）。
 *
 * <p>为什么由 access 推：① iot 的 `/internal/**` 有内部凭证守卫，access 没有 ⇒ 由 access 推不会新增暴露面；
 * ② access 挂了 ⇒ iot 收不到 ⇒ 平台告警判为「不可判定/上报中断」而不是误报健康。</p>
 *
 * <p>读什么：只读**本进程自己的** `MeterRegistry`（计数器当前值、Timer 的 max、Gauge 当前值）。
 * 指标名沿用各产出处的既有常量/字面量（单一来源不变）——这里只做「取数后搬运」，不重新定义名字。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
@Component
public class AccessMetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(AccessMetricsReporter.class);

    /** 对账被时间预算推迟的次数（R8-2 方案 A）。 */
    private static final String RECONCILE_DEFERRED = "iot.access.config.reconcile.deferred";

    /** 一轮 tick 的总耗时（Timer）。 */
    private static final String TICK_DURATION = "iot.access.lease.tick.duration";

    /** 对账阶段耗时（Timer）。 */
    private static final String RECONCILE_DURATION = "iot.access.config.reconcile.duration";

    /** 单租户规格取数耗时（Timer）。 */
    private static final String SPEC_LOAD_DURATION = "iot.access.spec.load.duration";

    /** 本地自我停采次数。 */
    private static final String SELF_FENCED = "iot.access.lease.self_fenced";

    /** 节点级失效停采次数。 */
    private static final String NODE_FENCED = "iot.access.lease.node_fenced";

    /** 续约失败次数。 */
    private static final String RENEW_FAILURE = "iot.access.lease.renew.failure";

    /** 待上报读数条数（Gauge）。 */
    private static final String EGRESS_PENDING = "iot.access.egress.pending";

    /** 本推送器自身的失败计数（推送链路断了必须可观测）。 */
    private final Counter reportFailureCounter;

    private final IAccessMetricsClient client;
    private final MeterRegistry meterRegistry;

    public AccessMetricsReporter(IAccessMetricsClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
        this.reportFailureCounter = Counter.builder("iot.access.metrics.report.failure")
            .description("指标推送给 iot 失败的次数（推送链路断 ⇒ 平台侧告警会不可判定）")
            .register(meterRegistry);
    }

    /** 周期推送（默认 15s，与平台告警评估周期同量级）。 */
    @Scheduled(fixedDelayString = "${ypbin.access.report-interval-ms:15000}")
    public void report() {
        AccessMetricsSnapshotResp snapshot = new AccessMetricsSnapshotResp(
            readCounter(RECONCILE_DEFERRED),
            readTimerMax(TICK_DURATION),
            readTimerMax(RECONCILE_DURATION),
            readTimerMax(SPEC_LOAD_DURATION),
            readCounter(SELF_FENCED),
            readCounter(NODE_FENCED),
            readCounter(RENEW_FAILURE),
            readGauge(EGRESS_PENDING),
            Instant.now());
        try {
            client.report(snapshot);
        } catch (RuntimeException ex) {
            // 不静默：推送失败必须计数 + 告警（否则「平台看不到 access」本身也变成不可观测）
            reportFailureCounter.increment();
            log.warn("[access] 指标推送给 iot 失败（平台侧将对相关规则判为不可判定）：{}", ex.getMessage(), ex);
        }
    }

    /** 读计数器当前值；指标不存在（尚未注册）返回 `null`——`0` 与「没这个指标」不是一回事。 */
    private Long readCounter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? null : (long) counter.count();
    }

    /**
     * 读 Timer 的最大耗时（毫秒）；不存在返回 `null`。
     *
     * <p>⚠️ `Timer.max(TimeUnit)` 返回 **double**（Micrometer 1.x 签名）——显式取整并挡掉
     * NaN/无穷，否则「没有样本」会被当成 0（把「没数据」读成「很快」）。</p>
     */
    private Long readTimerMax(String name) {
        Timer timer = meterRegistry.find(name).timer();
        if (timer == null) {
            return null;
        }
        double max = timer.max(TimeUnit.MILLISECONDS);
        if (Double.isNaN(max) || Double.isInfinite(max)) {
            return null;
        }
        return (long) max;
    }

    /** 读 Gauge 当前值；不存在返回 `null`。 */
    private Long readGauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        // gauge.value() 是 double：取整（待上报条数是整数语义）；NaN/无穷视为「没取到」
        if (gauge == null) {
            return null;
        }
        double value = gauge.value();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return (long) value;
    }
}
