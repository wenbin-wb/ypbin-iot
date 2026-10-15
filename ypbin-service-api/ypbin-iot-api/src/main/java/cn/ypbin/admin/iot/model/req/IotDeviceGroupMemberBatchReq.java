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
 * IoT 设备分组批量加入设备请求。
 *
 * <p>有界：单次最多 {@link #MAX_BATCH_SIZE} 台，超出由调用方分批——避免一个超大请求把
 * IN 列表与事务拖垮。服务层还会对去重后的清单再校验一次（接口校验是第二道闸，不是唯一一道）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class IotDeviceGroupMemberBatchReq {

    /** 单批设备数上限。 */
    public static final int MAX_BATCH_SIZE = 200;

    /** 设备主键清单（非空，去重后最多 {@link #MAX_BATCH_SIZE} 个）。 */
    @NotEmpty(message = "设备 ID 列表不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单次批量加入设备数不能超过 " + MAX_BATCH_SIZE)
    private List<Long> deviceIds;
}
