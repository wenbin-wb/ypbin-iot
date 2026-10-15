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
 * IoT 设备批量移除标签请求（删除多台设备上的指定标签键）。
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class IotDeviceBatchTagRemoveReq {

    /** 设备主键清单（非空）。 */
    @NotEmpty(message = "设备 ID 列表不能为空")
    private List<Long> deviceIds;

    /** 待移除的标签键清单（非空，单个键非空白且不超过 64 字符）。 */
    @NotEmpty(message = "标签键列表不能为空")
    private List<String> tagKeys;
}
