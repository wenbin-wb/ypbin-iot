/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.core;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.security.core.PermissionProvider;
import cn.ypbin.starter.security.identity.IdentityContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * **显式**权限校验（fail-closed），用于「影响可用率口径」的端点。
 *
 * <p>⚠️ <b>为什么需要它（平台级缺陷的临时防线，已在 ROADMAP 四点十六 登记）</b>：
 * 本仓微服务下游没有 Sa-Token 会话（身份来自网关头 → {@link IdentityContext}），
 * 因此各服务的 Nacos 配置里 `ypbin.security.interceptor=false` 关掉了 {@code SaInterceptor}
 * ——而 Sa-Token 的**注解鉴权（{@code @SaCheckPermission}）正是由该拦截器执行**，
 * 于是 {@code @SaCheckPermission} 在当前部署形态下**实际不生效**（外委复核实测：该 bean 数=0，
 * 且类路径没有 AOP 版替代）；{@code IotPermissionProvider} 这条权限数据链因此是空转的。</p>
 *
 * <p>危害不是"少个校验"：维护窗口会把断档从可用率的分子分母**同时**剔除，任何已登录的租户用户
 * 只要能调管理端点，就能声明窗口把断档"洗掉"、把可用率抬到 100%。所以在 starter 层给出正解
 * （让下游服务的注解鉴权可用的身份桥）之前，这里对**这几个端点**做一次显式的、拒绝优先的校验。</p>
 *
 * <p>与框架的关系：权限数据仍走 starter 的 {@link PermissionProvider}（本服务实现为
 * {@code IotPermissionProvider} → {@code SysCache}），不新造权限模型；查询结果缺失/异常一律**拒绝**
 * （绝不静默放行）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Component
public class IotPermissionGuard {

    private static final Logger log = LoggerFactory.getLogger(IotPermissionGuard.class);

    /** 拒绝计数（用于发现"有人天天被挡"或"配置错了"）。 */
    public static final String METRIC_DENIED = "iot.permission.denied";

    /** Sa-Token 的默认登录类型（与 {@code StpUtil} 一致）。 */
    private static final String LOGIN_TYPE = "login";

    private final PermissionProvider permissionProvider;
    private final Counter deniedCounter;

    public IotPermissionGuard(PermissionProvider permissionProvider, MeterRegistry meterRegistry) {
        this.permissionProvider = permissionProvider;
        this.deniedCounter = Counter.builder(METRIC_DENIED)
            .description("显式权限校验拒绝次数").register(meterRegistry);
    }

    /**
     * 要求当前请求身份具备该权限码；不满足直接抛业务异常（fail-closed）。
     *
     * @param code 权限码（必须已在 sys_menu.auth_code 登记，否则永远拒绝）
     */
    public void require(String code) {
        Long userId = IdentityContext.getUserId().orElse(null);
        if (userId == null) {
            deny(code, "无登录身份（缺少网关注入的身份头）");
            return;
        }
        List<String> permissions;
        try {
            permissions = permissionProvider.getPermissions(userId, LOGIN_TYPE);
        } catch (RuntimeException ex) {
            // 权限查询本身失败：拒绝（不是放行），并把堆栈暴露出来
            deniedCounter.increment();
            log.error("[iot] 权限查询失败，按拒绝处理：userId={} code={}", userId, code, ex);
            throw new BusinessException("权限校验失败，请稍后重试");
        }
        if (permissions == null || !permissions.contains(code)) {
            deny(code, "缺少权限码");
            return;
        }
    }

    private void deny(String code, String reason) {
        deniedCounter.increment();
        log.warn("[iot] 拒绝访问：code={} 原因={}", code, reason);
        throw new BusinessException("没有权限执行该操作");
    }
}
