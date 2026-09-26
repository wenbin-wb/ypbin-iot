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
 * IoT 设备分组-设备成员响应模型（§3.11，含设备快照便于列表展示）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotDeviceGroupMemberResp {

    /** 成员主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 分组 ID。 */
    private Long groupId;

    /** 设备 ID。 */
    private Long deviceId;

    /** 设备编码（快照）。 */
    private String deviceCode;

    /** 设备名称（快照）。 */
    private String deviceName;

    /** 所属产品 ID（设备未绑定产品时为空）。 */
    private Long productId;

    /** 所属产品名称（由产品表批量解析；设备未绑定产品或产品已不存在时为空）。 */
    private String productName;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
