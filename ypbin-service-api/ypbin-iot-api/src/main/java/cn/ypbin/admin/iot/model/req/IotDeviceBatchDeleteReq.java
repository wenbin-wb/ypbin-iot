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
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备批量删除请求。
 *
 * <p>有界：单次最多 {@link #MAX_BATCH_SIZE} 台；删除为逻辑删除（{@code is_deleted=1}），
 * 一次批量 SQL 完成，不逐台调用删除。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@Getter
@Setter
public class IotDeviceBatchDeleteReq {

    /** 单批设备数上限（与批量启停同一口径）。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 设备主键清单（非空，最多 {@link #MAX_BATCH_SIZE} 个）。 */
    @NotEmpty(message = "设备 ID 列表不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单次批量操作设备数不能超过 " + MAX_BATCH_SIZE)
    private List<Long> ids;
}
