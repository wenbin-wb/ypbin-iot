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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import cn.ypbin.admin.iot.service.MaintenanceWindowService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.ypbin.starter.log.annotation.Log;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护窗口管理端点（M-2 / spec §12.5；A3 的「可配置」）。
 *
 * <p>与 {@code /internal/maintenance/windows} 的分工：内部端点给平台侧/自动化调用（只有内部凭证），
 * 本端点给**管理台**用（网关鉴权 + 权限码 + 租户来自身份），两者共用同一个 {@link MaintenanceWindowService}。</p>
 *
 * <p>网关路由 {@code /iot/**} 且 StripPrefix=1 ⇒ 前端调用路径为 {@code /iot/maintenance/windows}。</p>
 *
 * @author wenbin
 * @since 2026-09-23
 */
@RestController
@RequestMapping("/maintenance/windows")
@RequiredArgsConstructor
public class IotMaintenanceWindowController {

    private final MaintenanceWindowService maintenanceWindowService;

    /**
     * 查询维护窗口（可用率报表用：解释「这段时间为什么不算断档」）。
     *
     * @param deviceId 设备 ID（可空=只看租户级 + 传空则不过滤设备维度）
     * @param from     起点（可空）
     * @param to       终点（可空）
     * @return 维护窗口列表
     */
    @GetMapping
    @SaCheckPermission("iot:maintenance:list")
    public R<List<MaintenanceWindowDto>> list(
            @RequestParam(value = "deviceId", required = false) Long deviceId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return R.ok(maintenanceWindowService.list(deviceId, from, to));
    }

    /**
     * 声明一个维护窗口（计划停机不计入可用率）。
     *
     * @param req 请求（设备为空=租户全部设备；开始为空=服务端当前时间）
     * @return 新建窗口 ID
     */
    @PostMapping
    @SaCheckPermission("iot:maintenance:create")
    @Idempotent
    @Log("声明维护窗口")
    public R<Long> open(@RequestBody MaintenanceWindowReq req) {
        return R.ok(maintenanceWindowService.open(req));
    }

    /**
     * 关闭一个维护窗口（只关「进行中」的）。
     *
     * @param id 窗口 ID
     * @return 受影响行数（0=不存在或已结束）
     */
    @PostMapping("/{id}/close")
    @SaCheckPermission("iot:maintenance:close")
    @Idempotent
    @Log("关闭维护窗口")
    public R<Integer> close(@PathVariable("id") Long id) {
        return R.ok(maintenanceWindowService.close(id));
    }
}
