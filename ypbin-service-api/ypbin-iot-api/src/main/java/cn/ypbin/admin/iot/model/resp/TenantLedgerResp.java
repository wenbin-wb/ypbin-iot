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
import lombok.Getter;
import lombok.Setter;

/**
 * 租户台账视图（M-2：可分配来源 + 配置版本号）。
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class TenantLedgerResp {

    /** 租户 ID。 */
    private Long tenantId;

    /** 是否可被分配。 */
    private Boolean assignable;

    /** 配置版本号：台账每次变更 +1（接入侧据此「不一致才拉全量」）。 */
    private Long configEpoch;

    /**
     * 本次写操作的结果类型：{@code created} / {@code revived} / {@code updated}。
     *
     * <p>仅写入口回填；查询接口为 {@code null}（没有「本次变更」可言）。</p>
     */
    private String change;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
