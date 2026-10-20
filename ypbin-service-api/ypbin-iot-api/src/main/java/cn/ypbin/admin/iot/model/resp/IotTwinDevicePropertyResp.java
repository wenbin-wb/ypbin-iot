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

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备可绑定的物模型属性（配置页用）。
 *
 * <p><b>{@code mapped} 这个字段是本响应存在的核心理由</b>：它表示「该属性在本设备上是否已建立
 * 采集点位映射」。为 {@code false} 时属性**永远拿不到实时值**——配置页必须给出
 * 「该属性未配置采集点位，绑定后不会有数据」的提示。没有这个字段，用户会绑一堆没有数据的属性
 * 然后认为孪生功能坏了。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinDevicePropertyResp {

    /** 服务主键。 */
    private Long serviceId;

    /** 服务标识。 */
    private String serviceIdentifier;

    /** 服务名称。 */
    private String serviceName;

    /** 属性主键。 */
    private Long propertyId;

    /** 属性标识。 */
    private String propertyIdentifier;

    /** 属性名称。 */
    private String propertyName;

    /** 物模型数据类型。 */
    private String dataType;

    /** 读写权限：R | W | RW。 */
    private String accessMode;

    /** 单位。 */
    private String unit;

    /** 最小值。 */
    private BigDecimal minValue;

    /** 最大值。 */
    private BigDecimal maxValue;

    /** 该属性在本设备上是否已建立采集点位映射。 */
    private Boolean mapped;
}
