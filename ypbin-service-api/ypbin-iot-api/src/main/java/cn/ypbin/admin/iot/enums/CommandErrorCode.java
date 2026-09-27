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
 * 命令实例的可区分失败原因码（**不要**用一句"超时(未在线)"兜底——那正是设计 §7.2 要消灭的不可区分错误）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
public enum CommandErrorCode {

    /** 设备未连接（EMQX publish 返回 202 No matched subscribers）。 */
    NO_SUBSCRIBER("NO_SUBSCRIBER", "设备未连接（无订阅者）"),

    /** 设备离线（预留：将来若启用"离线不排队"策略，由策略判出；本轮不产生）。 */
    DEVICE_OFFLINE("DEVICE_OFFLINE", "设备离线"),

    /** EMQX 调用失败（不可达/被拒/鉴权失败）——可重试（人工重发）。 */
    EMQX_ERROR("EMQX_ERROR", "EMQX 调用失败"),

    /** 设备回执判失败（保留设备给的 code/message）。 */
    DEVICE_REJECTED("DEVICE_REJECTED", "设备回执失败"),

    /** 等待回执超时（周期扫描判定）。 */
    TIMEOUT("TIMEOUT", "等待回执超时");

    private final String code;

    private final String desc;

    CommandErrorCode(String code, String desc) {
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
