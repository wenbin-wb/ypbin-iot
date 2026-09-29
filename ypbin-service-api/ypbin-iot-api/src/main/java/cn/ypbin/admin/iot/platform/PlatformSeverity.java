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
 * 平台告警的严重度（与设备告警的 `AlertSeverity` **刻意分开**：两者受众与处置流程不同，
 * 混用枚举会让"按严重度排序"在两个语义不同的集合上做比较）。
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum PlatformSeverity {

    /** 警告：需要关注，但不影响主链路可用性。 */
    WARNING("WARNING", "警告"),

    /** 严重：平台关键能力已受损（评估停摆、告警发不出去）。 */
    CRITICAL("CRITICAL", "严重");

    private final String code;

    private final String desc;

    PlatformSeverity(String code, String desc) {
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
}
