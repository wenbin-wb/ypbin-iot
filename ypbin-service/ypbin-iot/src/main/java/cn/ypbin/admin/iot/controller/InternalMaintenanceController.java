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

import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import cn.ypbin.admin.iot.service.MaintenanceWindowService;
import cn.ypbin.starter.core.model.R;
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
 * 维护窗口内部端点（M-2 / spec §12.5）：声明/关闭/查询「可用率统计要排除的计划停机」。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （只有 {@code X-Internal-Token}，没有身份/权限头）；租户从**上下文**取（网关注入），不来自请求体。</p>
 *
 * <p><b>为什么先做内部端点而不是管理台</b>：口径与数据模型是先行件（可用率报表要立刻能用），
 * 运维声明窗口可以由平台侧调用本端点完成；管理台页面与权限码属后续增量（ROADMAP 已登记）。</p>
 *
 * @author wenbin
 * @since 2026-09-23
 */
@RestController
@RequestMapping("/internal/maintenance/windows")
@RequiredArgsConstructor
public class InternalMaintenanceController {

    private final MaintenanceWindowService maintenanceWindowService;

    /**
     * 声明一个维护窗口。
     *
     * @param req 请求
     * @return 新建窗口 ID
     */
    @PostMapping
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
    public R<Integer> close(@PathVariable("id") Long id) {
        return R.ok(maintenanceWindowService.close(id));
    }

    /**
     * 查询维护窗口。
     *
     * @param deviceId 设备 ID（可空）
     * @param from     起点（可空）
     * @param to       终点（可空）
     * @return 维护窗口列表
     */
    @GetMapping
    public R<List<MaintenanceWindowDto>> list(
            @RequestParam(value = "deviceId", required = false) Long deviceId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return R.ok(maintenanceWindowService.list(deviceId, from, to));
    }
}
