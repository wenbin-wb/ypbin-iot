/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约成功的单条回执（服务端刷新后的到期时间与版本号）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseRenewAck {

    /** 租户 ID。 */
    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;

    /** 刷新后的到期时间。 */
    @NotNull(message = "到期时间不能为空")
    private LocalDateTime leaseExpireAt;

    /** 当前台账版本号。 */
    @NotNull(message = "台账版本号不能为空")
    private Long epoch;
}
