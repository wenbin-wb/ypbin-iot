/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import cn.ypbin.admin.iot.trace.TraceAdvice;
import cn.ypbin.admin.iot.trace.TraceItem;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息时间线的一条（接口视图，设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.2）。
 *
 * <p><b>为什么单独定义视图而不直接返回 {@link TraceItem}</b>：领域对象里带着
 * `source`/`sourceId` 这类"指向库内"的信息，接口只应暴露用户需要的形状；
 * 且视图是**契约**（前端对着它写），领域对象是**实现**（随时可重构）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceTraceItemResp {

    /** 排序时刻（时间线按它倒序）。 */
    private LocalDateTime occurredAt;

    /** 阶段码（`TraceStage.code`）。 */
    private String stage;

    /** 阶段说明（人话）。 */
    private String stageDesc;

    /** 方向码（`up`/`down`/`internal`）。 */
    private String direction;

    /** 结果码（`ok`/`failed`/`timeout`/`unknown`）。 */
    private String outcome;

    /** 人话标题。 */
    private String title;

    /** 来源表名（明细端点的入参之一）。 */
    private String source;

    /** 来源主键（明细端点的入参之一）。 */
    private Object sourceId;

    /** 归因码（成功为 `null`）——**即使没有建议也必须返回**，它是用户排障的原始依据。 */
    private String errorCode;

    /** 原始错误文案（成功为 `null`）。 */
    private String errorMsg;

    /** 定位建议（成功或"暂无规则"时为 `null`）。 */
    private TraceAdvice advice;

    /** 重发次数（仅命令类；`null` 表示该来源无此概念）。 */
    private Integer retryCount;

    /** 是否发生过重发（前端据此提示"历史时刻已被覆盖"，见设计 §3.3.2c）。 */
    private boolean historyOverwritten;

    /** 命令类的三个时刻（非命令类为 `null`）。 */
    private LocalDateTime enqueuedAt;

    private LocalDateTime publishedAt;

    private LocalDateTime ackedAt;
}
