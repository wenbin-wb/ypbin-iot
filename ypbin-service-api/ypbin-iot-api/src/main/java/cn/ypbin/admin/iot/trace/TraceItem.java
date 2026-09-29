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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息时间线上的**一条目**（设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.2/§3.3.3）。
 *
 * <p><b>🔴 "一行 = 一条目"</b>：三个 `DOWN_*` 阶段是**同一条 `iot_command_instance` 记录的
 * 三个时刻**（受理 `create_time` → 投递 `sent_at` → 回执 `finished_at`），**不是三条记录**。
 * 因此本对象**一条命令实例只产生一个条目**，三个时刻都放在
 * {@link #enqueuedAt}/{@link #publishedAt}/{@link #ackedAt} 里。</p>
 *
 * <p>为什么必须这样：若把一行展开成三个条目，时间线会因同一件事重复三次而**失真**，
 * 且"阶段筛选"的语义会变得无法解释（同一条消息同时出现在多个筛选结果里）。</p>
 *
 * @param occurredAt 该条目在时间线上的**排序时刻**（取已达的最远阶段时刻，见 §3.3.3 的回落顺序）
 * @param stage      已达的**最远**阶段
 * @param direction  方向
 * @param outcome    结果
 * @param title      人话标题（如「下发：temperature 设为 25」）
 * @param source     来源表名（`detailRef` 的一半）
 * @param sourceId   来源主键（`detailRef` 的另一半）
 * @param retryCount 重发次数（`null` 表示该来源无此概念）
 * @param enqueuedAt 受理时刻（仅命令类有）
 * @param publishedAt 投递时刻（仅命令类有）
 * @param ackedAt    回执时刻（仅命令类有）
 * @param errorCode  归因码（成功为 `null`）
 * @param errorMsg   原始错误文案（成功为 `null`）
 * @param advice     定位建议（{@link TraceOutcome#OK} 时为 `null`）
 * @author wenbin
 * @since 2026-09-30
 */
public record TraceItem(
    LocalDateTime occurredAt,
    TraceStage stage,
    TraceDirection direction,
    TraceOutcome outcome,
    String title,
    String source,
    Object sourceId,
    Integer retryCount,
    LocalDateTime enqueuedAt,
    LocalDateTime publishedAt,
    LocalDateTime ackedAt,
    String errorCode,
    String errorMsg,
    TraceAdvice advice) {

    /**
     * 是否需要向用户展示"已重发 N 次（历史时刻已被覆盖）"的提示。
     *
     * <p>重发**就地覆写**同一行的 `sent_at`/`finished_at`（见设计 §3.3.2c）⇒ 时间线上这条
     * "消息"的时刻会**向前跳**。不告知用户，他会以为"10:03 那次"还是原来那次 ⇒ 排障方向错误。</p>
     *
     * @return 需要提示返回 {@code true}
     */
    public boolean hasOverwrittenHistory() {
        return retryCount != null && retryCount > 0;
    }

    /**
     * 便利访问器：建议的动作清单（无建议时返回空集合，**不返回 null**）。
     *
     * @return 动作清单
     */
    public List<String> adviceActions() {
        return advice == null ? List.of() : advice.actions();
    }
}
