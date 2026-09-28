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
 * 告警级别**码**（设计 §2.1 表 A 的 {@code severity}，三档）。
 *
 * <p>刻意不照搬 ThingsBoard 的 5 级：本平台的使用者少、升级链本期不做（设计 §2.6），
 * 三档足够表达「提示 / 警告 / 严重」，多了只会变成没人维护的下拉框。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertSeverity {

    /** 提示（无需处理，仅供知晓）。 */
    INFO("INFO", "提示"),

    /** 警告（默认档：需要关注但不紧急）。 */
    WARNING("WARNING", "警告"),

    /** 严重（需要立即处理）。 */
    CRITICAL("CRITICAL", "严重");

    private final String code;

    private final String desc;

    AlertSeverity(String code, String desc) {
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
    public static AlertSeverity of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertSeverity value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
