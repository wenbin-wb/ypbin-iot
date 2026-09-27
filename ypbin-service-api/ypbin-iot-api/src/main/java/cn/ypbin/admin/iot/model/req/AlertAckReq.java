/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量确认（一键 ACK）请求。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertAckReq {

    /** 实例 ID（单个确认传一个元素即可，与批量共用同一端点）。 */
    @NotEmpty(message = "请选择要确认的告警")
    private List<Long> ids;
}
