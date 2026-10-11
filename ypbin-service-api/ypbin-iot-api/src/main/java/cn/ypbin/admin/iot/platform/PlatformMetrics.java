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

/**
 * 平台健康判定所依赖的**指标名常量**（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §2.1）。
 *
 * <p>这些名字必须与产出侧（`AlertMetrics` 等）**逐字一致**——不一致会静默取不到值，
 * 而"取不到"在判定器里表现为 `UNKNOWN`（不告警），于是**监控静默失效且无人发现**。
 * 因此：① 集中在此处单一来源；② 有用例断言它们与产出侧常量一致（见 `PlatformMetricsNameTest`）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class PlatformMetrics {

    /** 距最近一次成功评估的毫秒数（实时 gauge）。 */
    public static final String METRIC_EVALUATE_LAG = "iot.alert.evaluate.lag";

    /** 评估轮次失败计数。 */
    public static final String METRIC_ROUND_FAILED = "iot.alert.evaluate.round.failed";

    /** 通知投递失败计数。 */
    public static final String METRIC_NOTIFY_FAILED = "iot.alert.notify.failed";

    /** 入站读数因点位未映射被丢弃的计数。 */
    public static final String METRIC_INGEST_UNMAPPED = "iot.ingest.propertyid.unmapped";

    /** 入站读数因孤儿映射被丢弃的计数。 */
    public static final String METRIC_INGEST_ORPHAN = "iot.ingest.propertyid.orphan";

    /** 台账版本号推进失败次数（R8-8 方案 B：失败不回滚业务写入，但必须可观测）。 */
    public static final String METRIC_LEDGER_BUMP_FAILED = "iot.ledger.bump.failure";

    /** access 上报：因对账时间预算用尽被推迟的租户次数（源 `iot.access.config.reconcile.deferred`）。 */
    public static final String METRIC_RECONCILE_DEFERRED = "iot.access.config.reconcile.deferred";

    /** access 上报：tick 最大耗时（源 `iot.access.lease.tick.duration` 的 max）。 */
    public static final String METRIC_TICK_DURATION_MAX = "iot.access.lease.tick.duration.max";

    /** iot 侧自算：距最近一次 access 指标上报的毫秒数（`-1` = 从未收到）。 */
    public static final String METRIC_REPORT_LAG = "iot.platform_alert.access.report.lag";

    private PlatformMetrics() {
    }
}
