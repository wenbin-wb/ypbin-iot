/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.command;

import lombok.Getter;
import lombok.Setter;

/**
 * 回执受理结果（内部端点响应；**沿用 HTTP 200 + {@code R.code} 惯例**——破例只属于 {@code /internal/mqtt/**}）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
public class CommandReplyResult {

    /** 本次真正更新了实例（false = 重复回执/未知请求/设备不匹配）。 */
    private boolean accepted;

    /** 命中重复回执（实例已是终态或不是待回执态）。 */
    private boolean duplicated;

    /** 丢弃（设备不存在 / 设备与实例不匹配 / requestId 形态非法）。 */
    private boolean discarded;

    /** 结果说明（面向排障，不含凭据）。 */
    private String reason;

    /**
     * 构造。
     *
     * @param accepted   是否更新
     * @param duplicated 是否重复
     * @param discarded  是否丢弃
     * @param reason     说明
     * @return 结果
     */
    public static CommandReplyResult of(boolean accepted, boolean duplicated, boolean discarded,
                                        String reason) {
        CommandReplyResult result = new CommandReplyResult();
        result.accepted = accepted;
        result.duplicated = duplicated;
        result.discarded = discarded;
        result.reason = reason;
        return result;
    }
}
