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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量对账响应（一次拉取所有租户的 epoch，判据只用 epoch）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class TenantEpochBatchResp {

    /** 各租户的版本号（永不为 null）。 */
    private List<TenantEpochItem> items = new ArrayList<>();

    /** 本次读取时刻（供调用方判断数据新鲜度）。 */
    private LocalDateTime readAt;

    /**
     * 清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<TenantEpochItem> getItems() {
        return items == null ? List.of() : items;
    }
}
