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
 * <p>字段与 {@code iot_point_mapping} 对齐，另带属性标识 {@code identifier}。
 * <b>{@code identifier} 才是上报坐标（规范坐标）</b>：2026-09-26 坐标统一后，采集侧上报的是它而不是
 * {@code propertyId}（主键字符串形态）；{@code propertyId} 保留作定位与日志用。
 * 理由与统一前的读侧后果见 {@code docs/IOT-ROADMAP.md} 四点十七补充段。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class AccessPointMappingDto {

    /** 属性主键（关联 {@code iot_property.id}；定位/排障用，**不是**上报坐标）。 */
    private String propertyId;

    /** 属性标识（camelCase，规范坐标：采集读数时上报的就是它）。 */
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
