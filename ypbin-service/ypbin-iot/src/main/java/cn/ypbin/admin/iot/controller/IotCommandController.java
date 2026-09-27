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
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.admin.iot.service.CommandInstanceService;
import cn.ypbin.starter.security.core.UserContext;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.log.annotation.Log;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线调试（下行命令）接口：下发 / 查询 / 手动重发（设计 §7.4）。
 *
 * <p>路径与网关口径：客户端调 {@code /iot/devices/{id}/commands}，网关 {@code StripPrefix=1} 后落到本控制器
 * 的 {@code /devices/{id}/commands}（与 {@code IotDeviceController} 同构）。</p>
 *
 * <p>🔴 <b>本控制器**必须**维持「HTTP 200 + {@code R.code}」惯例</b>：全仓唯一的真状态码例外只属于
 * {@code /internal/mqtt/**}（设计决策 D2），**不得**扩散到浏览器/网关面向的接口——包括这里的下发与查询。
 * 业务性失败（类型非法/标识不在物模型/不可写/未知 requestId/不可重发的状态）一律走
 * {@code R.code} 信封，由全局异常处理器统一成 200。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class IotCommandController {

    private final CommandInstanceService commandInstanceService;

    /**
     * 下发命令/属性设置。
     *
     * @param deviceId 设备主键
     * @param req      下发请求
     * @return 实例视图（含 requestId 与初始状态）
     */
    @PostMapping("/{deviceId}/commands")
    @SaCheckPermission("iot:debug:send")
    @Log("下发 IoT 设备命令")
    public R<CommandInstanceResp> send(@PathVariable Long deviceId,
                                       @Valid @RequestBody CommandSendReq req) {
        return R.ok(commandInstanceService.send(deviceId, req, CommandSource.CONSOLE,
            UserContext.getUserId()));
    }

    /**
     * 分页查询下发/回执记录（倒序）。
     *
     * @param deviceId 设备主键
     * @param query    查询条件
     * @return 分页结果
     */
    @GetMapping("/{deviceId}/commands")
    @SaCheckPermission("iot:debug:get")
    public R<PageResult<CommandInstanceResp>> page(@PathVariable Long deviceId,
                                                   @Valid CommandQuery query) {
        return R.ok(commandInstanceService.page(deviceId, query));
    }

    /**
     * 人工重发（**同一 requestId**；仅失败/超时可重发，不自动重试）。
     *
     * @param deviceId  设备主键
     * @param requestId 请求 ID
     * @return 更新后的实例视图
     */
    @PostMapping("/{deviceId}/commands/{requestId}/resend")
    @SaCheckPermission("iot:debug:send")
    @Log("重发 IoT 设备命令")
    public R<CommandInstanceResp> resend(@PathVariable Long deviceId,
                                         @PathVariable String requestId) {
        return R.ok(commandInstanceService.resend(deviceId, requestId));
    }
}
