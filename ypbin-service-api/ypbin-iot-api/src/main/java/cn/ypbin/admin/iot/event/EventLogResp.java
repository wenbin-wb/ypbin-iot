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

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行期事件视图（G6：设备详情「事件」区块的时间线行）。
 *
 * <p>{@code idempotentKey} 刻意**不下发**：它是上报去重用的内部标识，对客户端没有意义，
 * 下发了反而会诱使客户端拿它当主键用（不同租户/设备可能重复）。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
public class EventLogResp {

    /** 事件实例主键。 */
    private Long id;

    /** 设备 ID。 */
    private Long deviceId;

    /** 事件标识。 */
    private String eventCode;

    /** 事件名称（可能为空——上报方未给且物模型无定义时）。 */
    private String eventName;

    /** 事件级别码：{@code info} | {@code warn} | {@code error}。 */
    private String level;

    /** 事件参数（JSON 文本，可能为空）。 */
    private String params;

    /** 事件发生时刻。 */
    private LocalDateTime eventTs;

    /** 入库时刻。 */
    private LocalDateTime createTime;
}
