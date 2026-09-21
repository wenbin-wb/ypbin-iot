/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import java.util.List;

/**
 * 设备采集规格服务（供 access 内部调用）。
 *
 * @author wenbin
 * @since 2026-09-21
 */
public interface DeviceSpecService {

    /**
     * 取某租户当前启用设备的采集规格（含点位映射）。
     *
     * @param tenantId 租户 ID
     * @return 设备规格列表；无设备返回空集合
     */
    List<AccessDeviceSpecResp> listByTenant(Long tenantId);
}
