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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备批量打标签请求（同一键值打到多台设备）。
 *
 * <p>有界：单次最多 {@link #MAX_BATCH_SIZE} 台；服务层还会对去重后的清单与键值长度再校验一次
 * （接口校验是第二道闸，不是唯一一道）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class IotDeviceBatchTagReq {

    /** 单批设备数上限。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 设备主键清单（非空，去重后最多 {@link #MAX_BATCH_SIZE} 个）。 */
    @NotEmpty(message = "设备 ID 列表不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单次批量操作设备数不能超过 " + MAX_BATCH_SIZE)
    private List<Long> deviceIds;

    /** 标签键（非空白，最长 64）。 */
    @NotBlank(message = "标签键不能为空")
    @Size(max = 64, message = "标签键长度不能超过 64")
    private String tagKey;

    /** 标签值（非空白，最长 255）。 */
    @NotBlank(message = "标签值不能为空")
    @Size(max = 255, message = "标签值长度不能超过 255")
    private String tagValue;
}
