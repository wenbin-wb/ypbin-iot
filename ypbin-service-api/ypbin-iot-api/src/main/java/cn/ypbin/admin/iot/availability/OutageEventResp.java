/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 断档事件视图（M-2）。
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
public class OutageEventResp {

    /** 断档事件 ID。 */
    private Long id;

    /** 设备 ID。 */
    private Long deviceId;

    /** 断档开始时刻。 */
    private LocalDateTime startTs;

    /** 断档结束时刻；{@code null}=进行中。 */
    private LocalDateTime endTs;

    /** 断档时长（秒）；进行中为按查询时刻现算的值。 */
    private Long durationSec;

    /** 原因码。 */
    private String reason;

    /** 是否进行中。 */
    private Boolean ongoing;
}
