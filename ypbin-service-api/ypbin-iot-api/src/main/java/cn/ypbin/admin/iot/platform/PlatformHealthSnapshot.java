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

import java.util.Map;

/**
 * 平台健康指标的**只读快照**（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §4）。
 *
 * <p><b>为什么用"快照 + 纯函数"而不是让判定器直接读 `MeterRegistry`</b>：</p>
 * <ol>
 *   <li>纯函数才能被**穷举单测**（每条规则一正一反 + 边界 + 指标缺失），
 *       而直接读 registry 的判定器只能在 Spring 上下文里测；</li>
 *   <li>"这一轮判定看到的是什么值"是可**留痕**的（设计 §3.1 的 `metric_snapshot`），
 *       事后排障时能回答"当时到底是多少"——直接读 registry 则永远只有当下值。</li>
 * </ol>
 *
 * <p>⚠️ <b>缺失的指标用 `null` 表示，不用 0</b>：`0` 是"观测到了且为零"，
 * `null` 是"没取到"。把两者混同会在采集坏掉时**把故障读成健康**（设计 §4 明确要求）。</p>
 *
 * @param lagMs                距最近一次成功评估的毫秒数（`-1` = 从未成功过；`null` = 未取到）
 * @param roundFailedCount     评估轮次失败累计数
 * @param notifyFailedCount    通知投递失败累计数
 * @param ingestUnmappedCount  入站因点位未映射被丢弃的累计数
 * @param ingestOrphanCount    入站因孤儿映射被丢弃的累计数
 * @author wenbin
 * @since 2026-09-30
 */
public record PlatformHealthSnapshot(
    Long lagMs,
    Long roundFailedCount,
    Long notifyFailedCount,
    Long ingestUnmappedCount,
    Long ingestOrphanCount) {

    /**
     * 从"指标名 → 值"的映射构建快照（供调度侧把 `MeterRegistry` 读成快照）。
     *
     * <p>取不到的指标留 `null`（**不填 0**，理由见类注释）。</p>
     *
     * @param meters 指标名 → 值（可含 `null` 值）
     * @return 快照
     */
    public static PlatformHealthSnapshot fromMeters(Map<String, Long> meters) {
        Map<String, Long> source = meters == null ? Map.of() : meters;
        return new PlatformHealthSnapshot(
            source.get(PlatformMetrics.METRIC_EVALUATE_LAG),
            source.get(PlatformMetrics.METRIC_ROUND_FAILED),
            source.get(PlatformMetrics.METRIC_NOTIFY_FAILED),
            source.get(PlatformMetrics.METRIC_INGEST_UNMAPPED),
            source.get(PlatformMetrics.METRIC_INGEST_ORPHAN));
    }

    /**
     * 入站丢弃总数（两项相加；**任一为 `null` 则结果为 `null`**——见下方说明）。
     *
     * <p>为什么"任一缺失即整体未知"：丢弃量是"未映射 + 孤儿"的合计，
     * 只有一项可比时，合计的**增长**可能来自"另一项开始被采集"而不是"真的开始丢数据"，
     * 据此告警会给出错误归因。宁可报未知。</p>
     *
     * @return 合计；不可计算时返回 `null`
     */
    public Long ingestDroppedTotal() {
        if (ingestUnmappedCount == null || ingestOrphanCount == null) {
            return null;
        }
        return ingestUnmappedCount + ingestOrphanCount;
    }
}
