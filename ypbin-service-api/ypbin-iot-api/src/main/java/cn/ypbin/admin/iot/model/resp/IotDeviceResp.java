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
 * IoT 设备响应模型。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class IotDeviceResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 设备编码。 */
    private String deviceCode;

    /** 设备名称。 */
    private String deviceName;

    /** 接入协议。 */
    private String protocol;

    /** 端点 URI。 */
    private String endpoint;

    /** 绑定产品 ID。 */
    private Long productId;

    /** 绑定物模型版本。 */
    private String productVersion;

    /** 在线状态：online | offline | unknown。 */
    private String onlineStatus;

    /** 最后心跳/上报时刻。 */
    private LocalDateTime lastSeenAt;

    /**
     * 启停位：{@code EntityStatus} 的 code（1 启用 / 0 停用）。
     *
     * <p>与 {@link #onlineStatus} **不是一回事**：那是「设备现在连没连上」的观测值，本字段是运维意图，
     * 停用后设备不再进入采集规格下发（见 {@code DeviceSpecServiceImpl}）。</p>
     */
    private Integer status;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
