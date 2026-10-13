/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

/**
 * 字节载荷解码的点位/设备上下文：SPI 的**稳定输入契约**。
 *
 * <p>字段全部来自宿主既有事实源（协议回调 + {@code AccessPointMappingDto} + 物模型），
 * 不新增平台语义：</p>
 * <ul>
 *   <li>{@code dataType}：物模型 {@code iot_property.data_type}（{@code type} 属于宿主物模型语义）；</li>
 *   <li>{@code rawAddress}：协议原始地址（MQTT 下是实际主题；也是点位映射的 {@code raw_address}）；</li>
 *   <li>{@code byteOrder}：点位映射既有字序列（{@code big}/{@code little}；可空）。</li>
 * </ul>
 *
 * <p>上下文里的字段可空（台账允许缺失），实现必须自行判空并给出显式失败原因，不得 NPE 冒泡。</p>
 *
 * @param deviceId    设备主键（字符串形式）
 * @param protocol    接入协议码（{@code tcp}/{@code modbus}/{@code mqtt}/{@code opcua}）
 * @param rawAddress  协议原始地址（{@code iot_point_mapping.raw_address}）
 * @param addressType 地址类型（{@code holding}/{@code topic}/{@code nodeid}…；可空）
 * @param dataType    物模型数据类型 code（可空）
 * @param byteOrder   点位映射字序（{@code big}/{@code little}；可空）
 * @author wenbin
 * @since 2026-10-13
 */
public record DecodeContext(
        String deviceId,
        String protocol,
        String rawAddress,
        String addressType,
        String dataType,
        String byteOrder) {
}
