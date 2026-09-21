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
 * IoT 设备分组响应模型（§3.11）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotDeviceGroupResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 分组名称。 */
    private String groupName;

    /** 父分组 ID（null=根）。 */
    private Long parentId;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
