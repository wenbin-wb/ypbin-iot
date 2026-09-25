/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import cn.ypbin.starter.crud.model.PageQuery;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 运行期事件分页查询条件（G6）。
 *
 * <p>时间范围是**左闭右开**（{@code event_ts >= from} 且 {@code event_ts < to}）：与断档/时序查询
 * 同一口径，避免相邻两次查询把边界那一秒的事件要么各算一次、要么都不算。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
public class EventLogQuery extends PageQuery {

    /** 事件发生时刻下界（含；可空）。 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime from;

    /** 事件发生时刻上界（不含；可空）。 */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime to;

    /** 级别码过滤（{@code info} | {@code warn} | {@code error}；可空=不过滤）。 */
    private String level;
}
