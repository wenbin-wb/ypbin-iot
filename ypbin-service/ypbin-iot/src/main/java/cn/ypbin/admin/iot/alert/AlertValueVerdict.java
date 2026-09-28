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

import java.util.List;

/**
 * 单次判定的**结果**（设计 §2.2.3）。
 *
 * <p><b>「不可判定」是一个独立结果，必须与「未越界」区分开</b>（设计 §2.2.3 的原话）：把它并入
 * 「未越界」就会得到「平台静默漏报且无人知道」——这正是本仓在时序查询上已经坚持的纪律
 * （{@code TimeSeriesQueryService.query} 在库不可用时抛异常而不是返回空列表）。</p>
 *
 * <p>本枚举把「不可判定」按**原因**再分成四类，因为处置方式完全不同：非数值要去查设备上报格式、
 * 坏质量要去查链路、陈旧要去查设备是否掉线、条件非法要去改规则。四类都有独立的指标出口
 * （见 {@link #getMetricName()}），混成一个计数会让值班无法判断该动哪一边。</p>
 *
 * <p><b>对状态机的作用（必须严格区分）</b>：只有 {@link #OUT_OF_RANGE} 增计数、
 * {@link #IN_RANGE} 清零计数或驱动恢复；其余四类**计数保持不变、状态不变**（设计 §2.3 的抖动抑制定义）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertValueVerdict {

    /** 合格的越界值（可判定，且满足触发条件）。 */
    OUT_OF_RANGE("OUT_OF_RANGE", "越界", null),

    /** 合格的未越界值（可判定，且不满足触发条件）——**只有它能驱动恢复**。 */
    IN_RANGE("IN_RANGE", "未越界", null),

    /** 该点位**没有任何最新值**（设备从未上报，或 Redis 里没有这个 field）。 */
    MISSING("MISSING", "无最新值", "iot.alert.evaluate.skipped_missing"),

    /** 值不是合法数值（解析失败/空串）。 */
    NON_NUMERIC("NON_NUMERIC", "值不是数值", "iot.alert.evaluate.skipped_non_numeric"),

    /** 质量位不是 {@code GOOD}：**不得**当作「恢复正常」而错误地 resolve 掉告警。 */
    BAD_QUALITY("BAD_QUALITY", "质量位非 GOOD", "iot.alert.evaluate.skipped_bad_quality"),

    /** 读数时刻陈旧（超过 3 × 采集周期或全局兜底 TTL）：陈旧**不等于**恢复。 */
    STALE("STALE", "数据陈旧", "iot.alert.evaluate.skipped_stale"),

    /** 规则条件本身非法（如布尔点位配了 {@code GT}）：**必须可见**，否则规则配错后永远静默不触发。 */
    INVALID_CONDITION("INVALID_CONDITION", "规则条件非法", "iot.alert.evaluate.skipped_invalid_condition");

    private final String code;

    private final String desc;

    /** 该结果对应的指标名；可判定的两种结果没有「跳过」指标，返回 {@code null}。 */
    private final String metricName;

    AlertValueVerdict(String code, String desc, String metricName) {
        this.code = code;
        this.desc = desc;
        this.metricName = metricName;
    }

    /** 稳定码（落库/日志用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /** 该结果对应的指标名；可判定结果返回 {@code null}。 */
    public String getMetricName() {
        return metricName;
    }

    /** 是否可判定（越界或未越界）。 */
    public boolean isDecidable() {
        return this == OUT_OF_RANGE || this == IN_RANGE;
    }

    /** 是否是「不可判定」的跳过类结果。 */
    public boolean isSkipped() {
        return !isDecidable();
    }

    /** 是否是越界。 */
    public boolean isOutOfRange() {
        return this == OUT_OF_RANGE;
    }

    /** 全部「跳过」类结果的指标名（供启动自检与文档生成；保证六类计数都真的注册了）。 */
    public static List<String> skippedMetricNames() {
        return List.of(MISSING.metricName, NON_NUMERIC.metricName, BAD_QUALITY.metricName,
            STALE.metricName, INVALID_CONDITION.metricName);
    }
}
