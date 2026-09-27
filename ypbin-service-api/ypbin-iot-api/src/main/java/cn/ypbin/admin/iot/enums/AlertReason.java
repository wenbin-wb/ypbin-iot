/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 告警实例的**结束原因码**（设计 §2.1 表 C 的 {@code reason}）。
 *
 * <p>设计原文列了 {@code RECOVERED} / {@code RULE_DISABLED} / {@code MANUAL_CLOSE} 三个码。
 * 本实现按用户已批准口径**不做人工关闭**（口径 5：「要 ACK，不要人工关闭——关闭交给恢复条件」），
 * 因此 {@code MANUAL_CLOSE} **不实现**（登记为与设计的偏差）；断档映射另有
 * {@link #OUTAGE_RECOVERED}。</p>
 *
 * <p>每一种原因都必须在页面上可读：只写一个布尔「是否恢复」无法回答「上周那条到底是自己好了还是有人
 * 把规则停了」——那正是「清除 ≠ 删除」要保住的信息。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertReason {

    /** 读到**合格的未越界值**而恢复（设计 §2.2.4：恢复只能由合格值驱动，不能由「读不到」驱动）。 */
    RECOVERED("RECOVERED", "条件已恢复"),

    /** 规则被停用（{@code enabled=0}）：活动实例随之收口，避免停用后留下永不消解的幽灵告警。 */
    RULE_DISABLED("RULE_DISABLED", "规则已停用"),

    /** 断档事件已闭合（复用既有 {@code outage_event}，不新造判定）。 */
    OUTAGE_RECOVERED("OUTAGE_RECOVERED", "设备已恢复上报");

    private final String code;

    private final String desc;

    AlertReason(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertReason of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertReason value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
