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

import lombok.Getter;
import lombok.Setter;

/**
 * 单个租户的台账版本号。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class TenantEpochItem {

    /** 租户 ID。 */
    private Long tenantId;

    /** 台账版本号。 */
    private Long epoch;
}
