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

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户归属（对外契约模型）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseAssignmentDto {

    /** 租户 ID。 */
    private Long tenantId;

    /** 当前归属的 access 节点标识。 */
    private String accessNode;

    /** 租约到期时间（节点须在此之前续约）。 */
    private LocalDateTime leaseExpireAt;

    /** 台账版本号（归属每次变更都推进，见 LeaseEpochRules）。 */
    private Long epoch;

    /** 租约状态。 */
    private LeaseState state;
}
