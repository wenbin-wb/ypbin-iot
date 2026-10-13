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
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 点位映射（§3.9：物模型逻辑点位 ↔ 协议物理地址）。
 *
 * <p>{@code propertyId} 关联属性（或命令，按 {@code refType} 区分）；
 * 点位映射必须引用<b>已发布版本</b>的属性；协议能力不支持时显式失败，不得静默。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_point_mapping")
public class IotPointMapping extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID。 */
    private Long deviceId;

    /** 关联属性（或命令）ID。 */
    private Long propertyId;

    /** 关联类型：property | command。 */
    private String refType;

    /** 协议内地址（寄存器地址/NodeId/topic 等）。 */
    private String rawAddress;

    /** 地址类型：holding|input|coil|discrete|nodeid|topic…。 */
    private String addressType;

    /** 采集周期（毫秒，0=仅订阅不轮询）。 */
    private Integer pollIntervalMs;

    /** 缩放系数：value = raw * scale + offset。 */
    private BigDecimal scaleFactor;

    /** 缩放偏移。 */
    private BigDecimal offsetValue;

    /** 字序（word order）：big=高字在前 / little=低字在前。仅 Modbus int+word_count=2 或 float 生效。 */
    private String byteOrder;

    /** 连续寄存器个数（int/float: 1|2；bits: 1；string: 1..32；默认 1 = 旧行为）。仅 Modbus 寄存器点位生效。 */
    private Integer wordCount;

    /** 整型符号：false=无符号 / true=有符号（DB TINYINT 0/1；与 enabled 同一约定）。 */
    @TableField("`signed`")
    private Boolean signed;

    /** 寄存器值形态：int|float|bits|string（默认 int = v1 行为）。仅 Modbus 寄存器点位生效。 */
    private String registerFormat;

    /** 仅 bits：起始位（0..15）。 */
    private Integer bitOffset;

    /** 仅 bits：位宽（1..16-bit_offset）。 */
    private Integer bitLength;

    /** 读写权限（与属性 access_mode 联动校验）。 */
    private String rw;

    /** 启用/停采。 */
    private Boolean enabled;
}
