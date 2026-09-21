/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 产品（物模型，§3.2）。
 *
 * <p>继承 {@link TenantBaseEntity} ⇒ 落库自动带 {@code tenant_id}，查询由租户插件自动加条件。
 * {@code productCode} 租户内唯一；{@code modelStatus} 为物模型状态（draft/published），
 * 与基类 {@code status} 启停位分离。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_product")
public class IotProduct extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 产品编码（租户内唯一，小写字母数字连字符）。 */
    private String productCode;

    /** 产品名称。 */
    private String productName;

    /** 接入协议码（tcp | modbus | mqtt | opcua，与 iot-starter 一致）。 */
    private String protocol;

    /** 数据格式：json（默认）| binary（预留编解码插件）。 */
    private String dataFormat;

    /** 设备类型描述（IoTDA deviceType）。 */
    private String deviceType;

    /** 厂商 ID（可选）。 */
    private String manufacturerId;

    /** 厂商名称（可选）。 */
    private String manufacturerName;

    /** 物模型状态：draft | published。 */
    private String modelStatus;

    /** 备注。 */
    private String remark;
}
