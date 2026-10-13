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

import cn.ypbin.admin.iot.retention.RetentionCleanupRunResp;
import cn.ypbin.admin.iot.retention.RetentionCleanupService;
import cn.ypbin.starter.core.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据保留清理的**内部端点**（#227）：运维手工触发**一轮**清理，同步返回真实结果。
 *
 * <p><b>谁能访问</b>：路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 挂在
 * 该前缀上的 {@code InternalTokenGuardInterceptor} 统一保护，只认请求头 {@code X-Internal-Token}
 * （凭证未配置即 fail-closed 拒绝、常量时间比较；见 {@code InternalTokenConstants.TOKEN_HEADER}）。
 * 因此**不加 {@code @SaCheckPermission}**——这是内部面，没有登录态与权限头（与
 * {@code InternalMaintenanceController}/{@code InternalLeaseController} 等既有内部端点同一约定）。</p>
 *
 * <p><b>语义</b>：只跑一轮、同步执行，返回本次真实结果（各表删除行数 / 耗时 / 是否被开关拦下）。
 * 不是 fire-and-forget 排队——调用方拿到响应时这一轮已经结束。为防重入（定时任务与手工触发
 * 共用同一服务方法），并发触发时后到者返回 {@code executed=false, skipReason=already-running}，
 * **不会**再删一轮。响应恒为 HTTP 200 + {@code R.code}（内部端点统一惯例，`/internal/mqtt/**`
 * 是唯一例外）；判断是否执行看 {@code data.executed}，不看 HTTP 状态码。</p>
 *
 * <p><b>失败语义</b>：删除阶段异常**直接上抛**（不吞），由全局异常处理器转
 * HTTP 200 + {@code R.code=500}；同时服务层的防重入闸门在 {@code finally} 释放，下一次可正常重试。</p>
 *
 * <p><b>风险</b>：保留清理是**不可逆**删除（删断档事件/维护窗口历史行），且删除**不即时**
 * （按数据库时钟算截止、跨界行保守留到下一次）。开关关闭时本端点不会执行（如实返回
 * {@code skipReason=disabled}）；详细口径、curl 示例与风险见 {@code docs/DEPLOY-BACKEND.md}
 * 「保留策略运维」一节。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@RestController
@RequestMapping("/internal/retention")
@RequiredArgsConstructor
public class InternalRetentionController {

    private final RetentionCleanupService retentionCleanupService;

    /**
     * 手工触发一轮保留清理。
     *
     * @return 本轮结果（{@code executed} 才是「跑没跑」的权威字段）
     */
    @PostMapping("/cleanup")
    public R<RetentionCleanupRunResp> cleanupOnce() {
        return R.ok(RetentionCleanupRunResp.from(retentionCleanupService.cleanupOnce()));
    }
}
