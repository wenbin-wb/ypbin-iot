/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

/**
 * EMQX 下行发布结果（官方 {@code POST /api/v5/publish} 的响应码语义，设计 F33）。
 *
 * <p><b>{@link #NO_SUBSCRIBER}（HTTP 202）是 P0 下行的关键判据</b>：官方原文 *No matched subscribers*
 * ⇒ 平台**不必等到超时**就能判定「设备没连上」。这是「超时(未在线)」这类不可区分错误码的解法
 * （设计 §7.2）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public enum EmqxPublishResult {

    /** HTTP 200：已投递给至少一个订阅者。 */
    DELIVERED("DELIVERED", "已投递"),

    /** HTTP 202：无匹配订阅者（设备未连接）。 */
    NO_SUBSCRIBER("NO_SUBSCRIBER", "无订阅者（设备未连接）");

    private final String code;

    private final String desc;

    EmqxPublishResult(String code, String desc) {
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
