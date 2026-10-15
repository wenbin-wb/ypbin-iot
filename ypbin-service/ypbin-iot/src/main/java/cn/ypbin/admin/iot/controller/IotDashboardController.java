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

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.admin.iot.model.resp.DashboardBoardResp;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp;
import cn.ypbin.admin.iot.service.IotDashboardBoardService;
import cn.ypbin.admin.iot.service.IotDashboardService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 运行总览与组态看板接口。
 *
 * <p><b>为什么总览与看板都不挂 {@code iot:xxx} 细粒度权限码，只用 {@link SaCheckLogin}</b>：
 * 两者都是「本租户运行态势 / 本租户看板配置」的页面级能力，不承载单资源操作（没有
 * 「某一台设备 / 某一条规则」的越权面）。数据由 MyBatis-Plus 租户插件按当前租户隔离，服务内不手写
 * {@code tenant_id} 过滤。看板的写接口虽改变状态，但改的是**本租户自己的展示配置**（不是设备、
 * 规则、凭据这类受控资源），且看板页本身是平台仅见页面（{@code sys_menu.platform_only=1}）。
 * 若后续出现「不同角色看不同看板」的需求，再引入权限码与菜单登记，不在本增量里把页面偏好升级成受控资源。</p>
 *
 * <p>仓库门禁核对（2026-10-15）：{@code ypbin-architecture-tests} 里**没有**「写接口必须带权限码」
 * 类断言（{@code CodingRulesTest} 只覆盖 printStackTrace/System.out、{@code @Transactional}
 * rollbackFor、字段注入、service/impl 依赖 provider；{@code SourceConventionTest} 覆盖源码铁律；
 * {@code ModulePublishingTest}/{@code IotSeamConventionTest}/{@code IotMapperAnnotationXmlGateTest}
 * 与权限无关）。唯一与权限码有关的是 {@code IotPermissionCodeGateTest}：它要求「凡声明了
 * {@code @SaCheckPermission} 的码必须已在 007 与 migration 注册」——本控制器不声明权限码，
 * 因此该门禁不受影响，不存在「声明了却没登记」的半同步风险。</p>
 *
 * <p>路径由网关 {@code Path=/iot/**} + {@code StripPrefix=1} 转发：客户端调
 * {@code GET/PUT /iot/dashboard/board}，服务内看到 {@code /dashboard/board}。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class IotDashboardController {

    private final IotDashboardService iotDashboardService;

    private final IotDashboardBoardService iotDashboardBoardService;

    /**
     * 获取运行总览（设备、消息、告警、分布与趋势一次拉齐）。
     *
     * @return 运行总览
     */
    @GetMapping("/overview")
    @SaCheckLogin
    public R<DashboardOverviewResp> overview() {
        return R.ok(iotDashboardService.overview());
    }

    /**
     * 读取本租户的组态看板配置。
     *
     * <p>从未配置时返回 {@code { "widgets": [] }}（**不是 null**）：前端
     * {@code sanitizeBoardConfig} 把 {@code widgets:null} 判为结构不兼容并重置看板，
     * 返回 null 会让新租户每次打开看板都被视为「配置损坏」。</p>
     *
     * @return 看板配置（字段与前端 {@code BoardWidget} 同名）
     */
    @GetMapping("/board")
    @SaCheckLogin
    public R<DashboardBoardResp> board() {
        return R.ok(iotDashboardBoardService.getBoard());
    }

    /**
     * 整体替换本租户的组态看板配置。
     *
     * <p>覆盖式语义：请求里的组件即最终配置，未出现的组件视为已删除（不做字段级合并）。
     * {@code @Idempotent} 防重复提交（前端抽屉保存按钮连点/网络重试会把同一份配置写两次），
     * {@code @Log} 留审计（谁在什么时候改了本租户看板）。</p>
     *
     * @param req 看板配置（{@code widgets} 必填，可为空数组表示清空看板）
     * @return 空响应
     */
    @PutMapping("/board")
    @SaCheckLogin
    @Idempotent
    @Log("更新 IoT 组态看板配置")
    public R<Void> saveBoard(@Valid @RequestBody DashboardBoardSaveReq req) {
        iotDashboardBoardService.saveBoard(req);
        return R.ok();
    }
}
