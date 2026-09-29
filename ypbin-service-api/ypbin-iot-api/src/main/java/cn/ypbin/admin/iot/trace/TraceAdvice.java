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

import java.util.List;

/**
 * 一条「定位建议」（设计 `docs/MESSAGE-TRACE-DESIGN.md` §4）。
 *
 * <p><b>为什么建议要带 `ruleId`</b>：文案会随用户反馈迭代，但"哪条规则给的"是稳定的。
 * 带上它，用户报障时能直接说"是 CMD_TIMEOUT 那条建议不对"，而不必贴一整段中文——
 * 否则每次改文案都等于把历史反馈全部作废。</p>
 *
 * @param ruleId  规则标识（稳定；不随文案变化）
 * @param summary 一句话结论（"为什么会这样"）
 * @param actions 可执行动作清单（"下一步查什么"，至少一条）
 * @author wenbin
 * @since 2026-09-30
 */
public record TraceAdvice(String ruleId, String summary, List<String> actions) {

    /**
     * 紧凑构造器：**不允许"没有动作的建议"**。
     *
     * <p>一条只有"命令超时"四个字的建议等于没有建议——用户看完还是不知道干什么。
     * 与其放一条空壳建议，不如让调用方显式返回 `null`（前端会显示"暂无建议 + 原始错误码"）。</p>
     */
    public TraceAdvice {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("定位建议必须至少有一条可执行动作（ruleId=" + ruleId + "）");
        }
        actions = List.copyOf(actions);
    }
}
