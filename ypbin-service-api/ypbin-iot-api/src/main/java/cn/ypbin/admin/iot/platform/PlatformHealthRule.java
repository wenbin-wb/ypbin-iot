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
 * 平台健康规则（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §2.1）。
 *
 * <p><b>为什么是枚举而不是数据库里的"规则表"</b>：平台健康规则是**平台自身的事实**，
 * 不是租户可配置的业务规则——把"评估器停摆多久算异常"交给租户配，只会得到一堆
 * 互相矛盾的阈值与无人负责的告警。枚举 ⇒ 阈值**有唯一来源**、可被用例穷举、改它必过评审。</p>
 *
 * <p><b>判据全部来自既有指标</b>（生产实测已产出，见设计 §1.4）：</p>
 * <ul>
 *   <li>`iot.alert.evaluate.lag` —— 距最近一次成功评估的毫秒数（**实时 gauge**；`-1` = 从未成功过）；</li>
 *   <li>`iot.alert.evaluate.round.failed` —— 评估轮次失败计数；</li>
 *   <li>`iot.alert.notify.failed` —— 通知投递失败计数；</li>
 *   <li>`iot.ingest.propertyid.unmapped` / `orphan` —— 入站因点位未映射/孤儿映射被丢弃的计数。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum PlatformHealthRule {

    /**
     * 评估器停摆：`lag` 超过 {@link #STALLED_LAG_THRESHOLD_MS}。
     *
     * <p><b>阈值为什么是 3 × 评估周期</b>（实测依据，设计 §1.4.2）：`lag` 是**锯齿**——
     * 评估器每 15s 跑一轮，lag 自然在 `[0, 15000]` 间来回爬，**实测峰值达 15,040ms**。
     * 若按直觉取"10 秒"会**周期性假告警**；取 3×周期 = 45s（余量 3 倍）才既灵敏又不误报。</p>
     */
    EVALUATOR_STALLED("PLATFORM_EVALUATOR_STALLED", "告警评估器停摆", PlatformSeverity.CRITICAL),

    /**
     * 评估轮次出现失败：`round.failed` 增长。
     *
     * <p>该计数**实测恒 0**（4 分钟采样）⇒ 任何增长都值得看一眼，用"增长即告警"。</p>
     */
    EVALUATOR_ROUND_FAILED("PLATFORM_EVALUATOR_ROUND_FAILED", "告警评估轮次失败",
        PlatformSeverity.WARNING),

    /**
     * 通知投递失败：`notify.failed` 增长。
     *
     * <p>该计数**实测恒 0** ⇒ 增长即告警。它直接对应"用户收不到告警"这一最糟的用户可见后果。</p>
     */
    NOTIFY_FAILING("PLATFORM_NOTIFY_FAILING", "告警通知投递失败", PlatformSeverity.CRITICAL),

    /**
     * 入站丢弃：`propertyid.unmapped` + `orphan` 增长。
     *
     * <p>两者**实测恒 0**；增长说明设备上报的数据被平台丢弃（点位未映射/孤儿映射），
     * 是"数据静默丢失"这类最难被用户发现的故障。</p>
     */
    INGEST_DROPPING("PLATFORM_INGEST_DROPPING", "入站读数被丢弃", PlatformSeverity.WARNING);

    /** 评估器停摆阈值（毫秒）。`45_000 = 3 × 评估周期 15_000`，依据见 {@link #EVALUATOR_STALLED}。 */
    public static final long STALLED_LAG_THRESHOLD_MS = 45_000L;

    private final String code;

    private final String title;

    private final PlatformSeverity severity;

    PlatformHealthRule(String code, String title, PlatformSeverity severity) {
        this.code = code;
        this.title = title;
        this.severity = severity;
    }

    /**
     * 规则码（**存库与接口一律用它，绝不用 ordinal**）。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 人话标题。
     *
     * @return 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 严重度。
     *
     * @return 严重度
     */
    public PlatformSeverity getSeverity() {
        return severity;
    }

    /**
     * 按码解析（忽略大小写；未知返回 `null`）。
     *
     * @param code 码
     * @return 规则；未知返回 `null`
     */
    public static PlatformHealthRule ofCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (PlatformHealthRule rule : values()) {
            if (rule.code.equalsIgnoreCase(normalized)) {
                return rule;
            }
        }
        return null;
    }
}
