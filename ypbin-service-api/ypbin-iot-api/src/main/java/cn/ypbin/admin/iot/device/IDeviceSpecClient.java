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

import cn.ypbin.admin.iot.device.config.DeviceSpecFeignConfiguration;
import cn.ypbin.starter.core.model.R;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 设备采集规格内部客户端（access → iot 服务）。
 *
 * <p>路径在 {@code /internal/**} 下，由 {@code InternalTokenGuardInterceptor} 统一保护；网关不对外暴露。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@FeignClient(name = "ypbin-iot", contextId = "deviceSpecClient", path = "/internal/device-specs",
    configuration = DeviceSpecFeignConfiguration.class)
public interface IDeviceSpecClient {

    /**
     * 取某租户当前启用设备的采集规格（含点位映射）。
     *
     * @param tenantId 租户 ID
     * @return 设备规格列表（无设备为空列表）
     */
    @GetMapping("/tenant")
    R<List<AccessDeviceSpecResp>> listByTenant(@RequestParam("tenantId") Long tenantId);
}
