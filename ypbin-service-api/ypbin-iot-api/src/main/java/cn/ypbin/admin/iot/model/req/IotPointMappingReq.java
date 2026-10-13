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

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 点位映射新增/编辑请求（§3.9）。
 *
 * <p>映射必须引用<b>已发布版本</b>的属性（或命令）；协议能力不支持时显式失败。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotPointMappingReq {

    /** 设备 ID（新增时必填；编辑时可空）。 */
    private Long deviceId;

    /** 关联属性（或命令）ID。 */
    @NotNull(message = "关联属性 ID 不能为空")
    private Long propertyId;

    /** 关联类型：property | command。 */
    @NotBlank(message = "关联类型不能为空")
    @Pattern(regexp = "property|command", message = "关联类型非法（应为 property|command）")
    private String refType;

    /** 协议内地址（寄存器地址/NodeId/topic 等）。 */
    @NotBlank(message = "协议内地址不能为空")
    @Size(max = 128, message = "协议内地址长度不能超过 128")
    private String rawAddress;

    /** 地址类型：holding|input|coil|discrete|nodeid|topic…。 */
    @NotBlank(message = "地址类型不能为空")
    @Pattern(regexp = "holding|input|coil|discrete|nodeid|topic", message = "地址类型非法")
    private String addressType;

    /** 采集周期（毫秒，0=仅订阅不轮询）。 */
    @Min(value = 0, message = "采集周期不能为负")
    private Integer pollIntervalMs;

    /** 缩放系数：value = raw * scale + offset。 */
    @Digits(integer = 14, fraction = 6, message = "缩放系数超出精度范围")
    private BigDecimal scaleFactor;

    /** 缩放偏移。 */
    @Digits(integer = 14, fraction = 6, message = "缩放偏移超出精度范围")
    private BigDecimal offsetValue;

    /** 字序（word order）：big=高字在前 / little=低字在前；仅 Modbus int+word_count=2 或 float 生效，其余场景非空即拒绝。 */
    @Pattern(regexp = "big|little", message = "字序非法（应为 big|little）")
    private String byteOrder;

    /**
     * 连续寄存器个数；默认 1 = 旧行为。仅 Modbus 寄存器点位生效。
     *
     * <p>合法范围按形态：{@code int}/{@code float} 为 1|2、{@code bits} 为 1、
     * {@code string} 为 1..32（≤64 字节）。此处只做 1..32 的粗粒度下上界，
     * 形态相关收窄由服务层校验（错误文案更具体）。</p>
     */
    @Min(value = 1, message = "寄存器个数必须在 1..32（int/float: 1|2；bits: 1；string: 1..32）")
    @Max(value = 32, message = "寄存器个数必须在 1..32（int/float: 1|2；bits: 1；string: 1..32）")
    private Integer wordCount;

    /** 整型符号：true=有符号；默认 false（无符号）。仅 Modbus 寄存器点位生效。 */
    private Boolean signed;

    /** 寄存器值形态：int|float|bits|string；默认 int（= v1 行为）。仅 Modbus 寄存器点位生效。 */
    @Pattern(regexp = "int|float|bits|string",
        message = "寄存器值形态非法（应为 int|float|bits|string）")
    private String registerFormat;

    /** 仅 bits：起始位（0..15）。 */
    @Min(value = 0, message = "bit_offset 必须在 0..15")
    @Max(value = 15, message = "bit_offset 必须在 0..15")
    private Integer bitOffset;

    /** 仅 bits：位宽（1..16；与 bit_offset 的和不得超过 16，由服务层校验）。 */
    @Min(value = 1, message = "bit_length 必须在 1..16")
    @Max(value = 16, message = "bit_length 必须在 1..16")
    private Integer bitLength;

    /** 读写权限（与属性 access_mode 联动校验）。 */
    @NotBlank(message = "读写权限不能为空")
    @Pattern(regexp = "R|W|RW", message = "读写权限非法（应为 R|W|RW）")
    private String rw;

    /** 启用/停采。 */
    private Boolean enabled;
}
