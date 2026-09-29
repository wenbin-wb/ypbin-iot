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
 * 平台告警实例的**生命周期状态**（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §3.1）。
 *
 * <p>与 {@link PlatformHealthState}（每轮重算的**判定态**）刻意分开：
 * 判定态是瞬间结论，生命周期是跨轮次持续的实体状态。混用会让"这条告警现在是什么状态"
 * 与"这一轮判出来什么"两个问题共用一个字段，进而无法表达"本轮判定正常但告警尚未收口"。</p>
 *
 * <p><b>一期不做 `ACKED`</b>：平台告警的"确认"是值班动作，其交互与设备告警不同（设计 §2.2），
 * 二期再谈。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum PlatformAlertState {

    /** 待确认触发（连续越界计数未达门槛；抗抖动）。 */
    PENDING("PENDING", "待确认"),

    /** 已触发（持续异常）。 */
    FIRING("FIRING", "已触发"),

    /** 已恢复。 */
    RESOLVED("RESOLVED", "已恢复");

    private final String code;

    private final String desc;

    PlatformAlertState(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 码。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 是否为活动态（`PENDING` 或 `FIRING`）。
     *
     * @return 活动返回 {@code true}
     */
    public boolean isActive() {
        return this == PENDING || this == FIRING;
    }
}
