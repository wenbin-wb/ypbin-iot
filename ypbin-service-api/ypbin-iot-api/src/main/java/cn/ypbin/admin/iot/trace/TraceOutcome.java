/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

/**
 * 消息条目的结果（设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.2）。
 *
 * <p>四态而非两态：{@link #TIMEOUT} 与 {@link #FAILED} 对用户的**下一步动作完全不同**
 * （超时看设备是否在线/订阅，失败看归因码），压成一个"失败"会让"定位建议"失去依据。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum TraceOutcome {

    /** 成功。 */
    OK("ok", "成功"),

    /** 失败（有明确归因）。 */
    FAILED("failed", "失败"),

    /** 超时（等待回执未到）。 */
    TIMEOUT("timeout", "超时"),

    /** 未完成/未知（如仍在 pending，或该来源天然无终态）。 */
    UNKNOWN("unknown", "未知");

    private final String code;

    private final String desc;

    TraceOutcome(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 结果码。
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
     * 是否属于"需要给定位建议"的结果。
     *
     * <p>判据是"用户需要知道下一步怎么办"——{@link #UNKNOWN} **也要**（"这条为什么没结果"
     * 本身就是需要解释的问题），但**成功不需要**。</p>
     *
     * @return 需要建议返回 {@code true}
     */
    public boolean needsAdvice() {
        return this != OK;
    }
}
