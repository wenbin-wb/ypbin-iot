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
 * 通知**事件码**（设计 §2.1 表 D 的 {@code event}）。
 *
 * <p>它回答的是「这条通知为什么被发出来」，与告警实例的**状态**（{@link AlertState}）不是一回事：
 * 一条 {@code FIRING} 状态的活动告警会先发一条 {@link #FIRING}，之后每过一个
 * {@code repeat_interval_sec} 再发一条 {@link #REPEAT}；状态变成 {@code RESOLVED} 时发
 * {@link #RESOLVED}。分开记录才能在排查时回答「为什么又发了一条」。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertNotifyEvent {

    /** 首次触发（实例进入 FIRING 时立即通知一次）。 */
    FIRING("FIRING", "首次触发"),

    /** 恢复（实例进入 RESOLVED 时通知一次）。 */
    RESOLVED("RESOLVED", "已恢复"),

    /** 重复通知（活动期内按 {@code repeat_interval_sec} 重发）。 */
    REPEAT("REPEAT", "重复提醒"),

    /** 人工确认（ACK 后通知一次，避免「我确认了但别人不知道」）。 */
    ACKED("ACKED", "已确认");

    private final String code;

    private final String desc;

    AlertNotifyEvent(String code, String desc) {
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
    public static AlertNotifyEvent of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertNotifyEvent value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
