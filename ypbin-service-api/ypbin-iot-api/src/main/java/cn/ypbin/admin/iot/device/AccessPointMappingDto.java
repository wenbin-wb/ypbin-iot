/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.device;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 点位映射的内部视图（access 采集用，§3.9）。
 *
 * <p>字段与 {@code iot_point_mapping} 对齐，另带属性标识 {@code identifier} 便于采集侧日志与质量定位
 * （避免为一条日志再回查属性表）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class AccessPointMappingDto {

    /** 属性主键（采集值的归属）。 */
    private String propertyId;

    /** 属性标识（camelCase，用于日志与排障）。 */
    private String identifier;

    /** 协议原始地址（§3.9 raw_address）。 */
    private String address;

    /** 地址类型：holding | input | coil | discrete | nodeid | topic。 */
    private String addressType;

    /** 采集周期（毫秒；空表示用设备级默认）。 */
    private Integer intervalMs;

    /** 线性缩放系数（可空）。 */
    private BigDecimal scaleFactor;

    /** 线性偏移（可空）。 */
    private BigDecimal offsetValue;

    /** 字节序：big | little（可空）。 */
    private String byteOrder;

    /** 读写权限：R | W | RW（与属性 access_mode 联动校验后的结果）。 */
    private String rw;
}
