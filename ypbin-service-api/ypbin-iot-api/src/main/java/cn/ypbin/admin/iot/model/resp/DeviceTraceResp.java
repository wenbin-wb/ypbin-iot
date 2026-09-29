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

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息跟踪的整体响应（设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.2/§6）。
 *
 * <p>🔴 <b>`truncated` 必须回给前端</b>：跨源合并在内存里做、有硬上限，**静默截断会让用户
 * 以为"就这些消息"**——那是比报错更糟的误导（设计 §7.2 判据 6）。前端据此显示
 * "结果已截断，请缩小时间窗"。</p>
 *
 * <p>⚠️ <b>`upstreamTraceUnavailable` 是"能力边界"而不是"查询结果"</b>：上行受理回执
 * **只覆盖 MQTT 通道**（access 的 HTTP 出口不写回执，见设计 §1.1 澄清②），且
 * "被丢弃的上行"与"是否落库"在一期**无痕迹可查**（设计 §3.3.1/§3.3.2）。
 * 前端必须把这段说明显示出来，**不能让"没有上行条目"被理解成"设备没上报"** ✗。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceTraceResp {

    /** 设备主键。 */
    private Long deviceId;

    /** 实际生效的时间窗（前端要显示"你正在看哪一段"）。 */
    private LocalDateTime from;

    private LocalDateTime to;

    /** 时间线（按 `occurredAt` 倒序；**空集合而非 null**）。 */
    private List<DeviceTraceItemResp> items = List.of();

    /** 命中总数（截断前）。 */
    private int matched;

    /** 是否因上限被截断。 */
    private boolean truncated;

    /** 边界说明：上行链路哪些断点在**一期不可见**（永远为 true 的**能力边界**，如实告知）。 */
    private boolean upstreamTraceUnavailable = true;

    /** 边界说明文案（指向应用日志与指标，避免用户把"没有条目"误解成"没上报"）。 */
    private String upstreamTraceNote;
}
