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

import cn.ypbin.admin.iot.access.metrics.AccessMetricsSnapshotResp;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * access 指标上报的**最近一次快照 + 心跳**（平台自告警的 access 域数据源）。
 *
 * <p>为什么放在内存而不是库：① 心跳/判据只需要「最近一次」；② 平台自告警必须**不依赖 DB**
 * （设计 §6 R2：DB 挂了告警也得能发）——把心跳写库会让「库故障」连带「心跳不可见」。</p>
 *
 * <p>「从未收到过」用 {@code -1} 表示（与 {@code AlertMetrics} 的 lag 约定一致），
 * 不用 0：0 会被读成「刚刚收到」。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
@Component
public class AccessMetricsReportStore {

    /** 心跳指标名（毫秒；`-1` = 从未收到上报）。 */
    public static final String METRIC_REPORT_LAG = "iot.platform_alert.access.report.lag";

    private final AtomicReference<Report> latest = new AtomicReference<>();
    private final Clock clock;

    /** 本 store 的创建时刻（用以回答「等了多久还没收到第一条上报」）。 */
    private final Instant createdAt;

    public AccessMetricsReportStore(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;
        this.createdAt = clock.instant();
        Gauge.builder(METRIC_REPORT_LAG, this, store -> store.lagMs())
            .description("距最近一次 access 指标上报的毫秒数（-1 = 从未收到）")
            .register(meterRegistry);
    }

    /** 记一次上报（幂等：后来者覆盖）。 */
    public void store(AccessMetricsSnapshotResp snapshot) {
        if (snapshot == null) {
            return;
        }
        latest.set(new Report(snapshot, clock.instant()));
    }

    /**
     * 最近一次收到的快照（从未收到返回 `null`）。
     *
     * @return 快照
     */
    public AccessMetricsSnapshotResp latest() {
        Report report = latest.get();
        return report == null ? null : report.snapshot();
    }

    /**
     * 距最近一次收到的毫秒数；**从未收到返回 `-1`**。
     *
     * @return 心跳值
     */
    public long lagMs() {
        Report report = latest.get();
        if (report == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(report.receivedAt(), clock.instant()).toMillis());
    }

    /**
     * 自本 store 创建起经过的毫秒数（回答「等了多久还没收到第一条上报」）。
     *
     * <p>为什么需要它：进程刚启动时 store 必然是空的，若立刻按「从未收到 ⇒ CRITICAL」告警，
     * 每次 iot 重启都会误报一条平台告警。⇒ 先等满一个中断阈值再下结论（期间判 UNKNOWN）。</p>
     *
     * @return 毫秒数
     */
    public long waitedMs() {
        return Math.max(0L, Duration.between(createdAt, clock.instant()).toMillis());
    }

    /** 一条上报记录（快照 + 收到时刻）。 */
    private record Report(AccessMetricsSnapshotResp snapshot, Instant receivedAt) {
    }
}
