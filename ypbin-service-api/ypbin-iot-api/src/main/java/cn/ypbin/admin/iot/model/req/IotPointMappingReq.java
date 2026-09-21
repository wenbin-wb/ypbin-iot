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

    /** 字节序：big|little。 */
    @Pattern(regexp = "big|little", message = "字节序非法（应为 big|little）")
    private String byteOrder;

    /** 读写权限（与属性 access_mode 联动校验）。 */
    @NotBlank(message = "读写权限不能为空")
    @Pattern(regexp = "R|W|RW", message = "读写权限非法（应为 R|W|RW）")
    private String rw;

    /** 启用/停采。 */
    private Boolean enabled;
}
