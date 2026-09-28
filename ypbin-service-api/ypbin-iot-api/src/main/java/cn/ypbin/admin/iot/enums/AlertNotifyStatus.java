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
 * 通知**投递状态码**（设计 §2.1 表 D 的 {@code status}）。
 *
 * <p><b>为什么要与告警状态分开</b>：通知是唯一会出网、会失败、会重试的环节。把「判定结果」与
 * 「投递结果」分开存，才能在通知全挂时仍然看得到告警（设计 §2.1 表 D 的原话）——
 * 告警本身不能因为邮件发不出去而丢。</p>
 *
 * <p>{@link #GIVEN_UP} 是**放弃**（重试 3 次后），不是成功也不是失败的同义词：
 * 它必须带着 {@code last_error} 才能让人知道「为什么最后没送到」。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertNotifyStatus {

    /** 待投递（含被全局限流**延后**的：延后不是丢弃，设计 §2.4）。 */
    PENDING("PENDING", "待投递"),

    /** 已投递成功。 */
    SENT("SENT", "已发送"),

    /** 投递失败（仍会按退避重试）。 */
    FAILED("FAILED", "发送失败"),

    /** 重试次数耗尽，**放弃**（`last_error` 记原样原因）。 */
    GIVEN_UP("GIVEN_UP", "已放弃");

    private final String code;

    private final String desc;

    AlertNotifyStatus(String code, String desc) {
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
    public static AlertNotifyStatus of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertNotifyStatus value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 是否可再次尝试投递（待投递 / 失败）。 */
    public boolean isRetryable() {
        return this == PENDING || this == FAILED;
    }
}
