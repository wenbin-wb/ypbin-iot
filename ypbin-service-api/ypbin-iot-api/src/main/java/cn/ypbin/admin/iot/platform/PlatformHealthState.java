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
 * 平台健康规则的**判定态**（注意：这是"判定结果"，不是告警实例的生命周期状态）。
 *
 * <p>告警实例的生命周期（`PENDING`/`FIRING`/`RESOLVED`）见 `PlatformAlertState`；
 * 两者刻意分开：判定态是**每轮重算**的瞬时结论，生命周期是**跨轮次持续**的实体状态。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum PlatformHealthState {

    /** 健康（指标可取且未超阈值）。 */
    HEALTHY("HEALTHY", "健康"),

    /** 触发（指标可取且超阈值）。 */
    FIRING("FIRING", "触发"),

    /** 不可判定（指标取不到）。**只记日志、不告警**——理由见 {@link PlatformHealthVerdict}。 */
    UNKNOWN("UNKNOWN", "不可判定");

    private final String code;

    private final String desc;

    PlatformHealthState(String code, String desc) {
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
