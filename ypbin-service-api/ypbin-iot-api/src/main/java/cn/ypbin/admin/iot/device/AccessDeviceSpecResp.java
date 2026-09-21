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

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备采集规格的内部视图（access 建链 + 订阅用）。
 *
 * <p>为什么要专门一个内部 DTO 而不是复用 {@code IotDeviceResp}：access 需要的是「协议栈能直接用的
 * 连接参数 + 该采哪些点」，与页面展示字段不同；而且 access **没有 JDBC**，只能从内部接口拿。</p>
 *
 * <p><b>connectionId 约定</b>：形如 {@code t{tenantId}-d{deviceId}}，与连接参数一一对应且自带租户信息。
 * 这样协议栈回调 {@code ConnectionSpecProvider.find(connectionId)}（那里拿不到租户）时，
 * 仍能只凭 connectionId 定位并校验租户——见访问侧实现。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class AccessDeviceSpecResp {

    /** 设备主键（字符串形式；协议栈以字符串标识设备）。 */
    private String deviceId;

    /** 设备名称。 */
    private String deviceName;

    /** 接入协议码：tcp | modbus | mqtt | opcua。 */
    private String protocol;

    /** 连接标识（{@code t{tenantId}-d{deviceId}}）。 */
    private String connectionId;

    /** 连接端点（如 tcp://127.0.0.1:15002）。 */
    private String endpoint;

    /** 凭据引用（不透明，本地解析；可空）。 */
    private String credentialRef;

    /** 设备级采集周期（毫秒；取该设备各点位周期的最小值，全空则空）。 */
    private Integer pollIntervalMs;

    /** 该设备的点位映射（未映射点位的设备为空列表）。 */
    private List<AccessPointMappingDto> points;
}
