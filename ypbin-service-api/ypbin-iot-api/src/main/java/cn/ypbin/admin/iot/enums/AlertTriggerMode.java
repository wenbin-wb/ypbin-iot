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
 * 抖动抑制模式**码**（设计 §2.1 表 A 的 {@code trigger_mode}）。
 *
 * <p>「条件」与「持续/次数」分离表达是四家主流实现的共性（设计 §1.5.1）：{@link #CONSECUTIVE_COUNT}
 * 对应阿里云「持续周期」与 ThingsBoard 的 {@code Repeating}，{@link #DURATION} 对应 Prometheus 的
 * {@code for} 与 ThingsBoard 的 {@code Duration}。本平台点位是周期上报，**次数比时长更贴合**
 * （周期可不固定），故默认 {@link #CONSECUTIVE_COUNT}。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertTriggerMode {

    /** 立即（首次判定越界即触发，不做抖动抑制）。 */
    IMMEDIATE("IMMEDIATE", "立即触发"),

    /** 连续 N 次越界（默认 N=3）。 */
    CONSECUTIVE_COUNT("CONSECUTIVE_COUNT", "连续 N 次"),

    /** 持续 T 秒（需回查时序库确认窗口内每个点都越界）。 */
    DURATION("DURATION", "持续 T 秒");

    private final String code;

    private final String desc;

    AlertTriggerMode(String code, String desc) {
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
    public static AlertTriggerMode of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertTriggerMode value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
