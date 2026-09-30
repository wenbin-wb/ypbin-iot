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
 * 单条平台健康规则的判定结果（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §4）。
 *
 * <p><b>三态而不是布尔</b>：{@link #UNKNOWN} 是**不可省略**的一态。
 * 若把"指标取不到"归入 `HEALTHY`，采集坏掉时监控会**报平安**（最危险的失效模式）；
 * 归入 `FIRING` 又会误报。⇒ 必须显式表达"我不知道"，并**只记日志、不告警**（设计 §4）。</p>
 *
 * @param rule     规则
 * @param state    判定态
 * @param observed 观测到的实际值（用于人话文案与留痕；`null` = 没取到）
 * @param summary  面向运维的一句话（**必须含实际值**，不能只说"异常"）
 * @author wenbin
 * @since 2026-09-30
 */
public record PlatformHealthVerdict(
    PlatformHealthRule rule,
    PlatformHealthState state,
    Long observed,
    String summary) {

    /**
     * 紧凑构造器：规范 `summary` 非空。
     *
     * <p>告警文案为空等于"发了条没有内容的告警"——值班看到只会更困惑。</p>
     */
    public PlatformHealthVerdict {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空（rule=" + rule.getCode() + "）");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary 不能为空（rule=" + rule.getCode() + "）");
        }
    }

    /**
     * 便利访问器：是否处于"需要开单/保持告警"的状态。
     *
     * @return FIRING 返回 {@code true}
     */
    public boolean isFiring() {
        return state == PlatformHealthState.FIRING;
    }

    /**
     * 便利访问器：是否已恢复正常（可用于收口既有告警）。
     *
     * @return HEALTHY 返回 {@code true}
     */
    public boolean isHealthy() {
        return state == PlatformHealthState.HEALTHY;
    }
}
