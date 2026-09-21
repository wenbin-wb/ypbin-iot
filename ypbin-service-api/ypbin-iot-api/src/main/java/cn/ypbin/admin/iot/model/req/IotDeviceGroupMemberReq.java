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

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备分组-设备成员新增请求（§3.11）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotDeviceGroupMemberReq {

    /** 设备 ID。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;
}
