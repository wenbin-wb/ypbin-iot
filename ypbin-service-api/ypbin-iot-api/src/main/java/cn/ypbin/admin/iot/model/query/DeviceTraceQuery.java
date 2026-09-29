/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.query;

import cn.ypbin.starter.crud.model.PageQuery;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息跟踪的查询条件（设计 `docs/MESSAGE-TRACE-DESIGN.md` §5）。
 *
 * <p><b>为什么时间窗有上限且超限报错</b>：本能力把三张表在内存里合并排序
 * （跨源 `UNION` 后 `ORDER BY`+`LIMIT` 用不上任何单表索引，见设计 §6 R5），
 * 时间窗是**唯一的量纲闸门**。静默截断会让用户以为"就这些"——那是比报错更糟的结果。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceTraceQuery extends PageQuery {

    /** 时间窗起点（可空 ⇒ 由服务层回落为"当前时刻往前 defaultWindowMinutes"）。 */
    private LocalDateTime from;

    /** 时间窗终点（可空 ⇒ 当前时刻）。 */
    private LocalDateTime to;

    /** 阶段过滤（可空=全部；未知阶段由服务层显式报错，不静默当"全部"）。 */
    private String stage;

    /** 方向过滤（可空=全部；`up`/`down`/`internal`）。 */
    private String direction;

    /** 结果过滤（可空=全部；`ok`/`failed`/`timeout`/`unknown`）。 */
    private String outcome;
}
