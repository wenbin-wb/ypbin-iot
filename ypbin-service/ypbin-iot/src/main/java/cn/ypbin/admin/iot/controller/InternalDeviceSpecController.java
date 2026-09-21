/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.service.DeviceSpecService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备采集规格内部端点（仅供 access 单元调用，不对外）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （凭证未配置一律拒绝）；网关不对外暴露该前缀。调用方必须显式给出 tenantId —— 该链路没有租户身份，
 * 服务侧据此显式绑定租户上下文后再查（见 {@code DeviceSpecServiceImpl}）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@RestController
@RequestMapping("/internal/device-specs")
@RequiredArgsConstructor
public class InternalDeviceSpecController {

    private final DeviceSpecService deviceSpecService;

    /**
     * 取某租户的采集规格（设备 + 点位映射）。
     *
     * @param tenantId 租户 ID
     * @return 设备规格列表
     */
    @GetMapping("/tenant")
    public R<List<AccessDeviceSpecResp>> listByTenant(
            @RequestParam("tenantId") @NotNull Long tenantId) {
        return R.ok(deviceSpecService.listByTenant(tenantId));
    }
}
