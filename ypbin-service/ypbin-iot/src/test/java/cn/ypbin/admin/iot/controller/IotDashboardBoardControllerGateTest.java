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

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 组态看板端点的**路由、鉴权与写操作三件套门禁**。
 *
 * <p>为什么需要：看板配置由前端 {@code board-utils.ts} 解释，两仓通过 HTTP 契约耦合，
 * 路径/鉴权一旦漂移，编译、类型检查与其它门禁**全绿**，只有用户点开看板才发现 404 或 403。
 * 本用例把「客户端看到的路径」「用什么鉴权」「写操作必须幂等 + 留审计」钉死。</p>
 *
 * <p><b>为什么是 {@link SaCheckLogin} 而不是权限码</b>：看板是「本租户自己的展示偏好」，
 * 不承载单资源操作；数据由租户插件隔离。仓库里没有「写接口必须带权限码」的架构断言
 * （2026-10-15 核对 {@code ypbin-architecture-tests} 全部规则后确认），唯一的权限相关门禁
 * {@code IotPermissionCodeGateTest} 只要求「声明了的权限码必须已登记」——本控制器不声明权限码，
 * 因此不存在半同步风险。若未来引入「不同角色不同看板」，须连同权限码与菜单登记一并加。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotDashboardBoardControllerGateTest {

    @Test
    @DisplayName("路径与网关 StripPrefix=1 口径一致：类映射 /dashboard，方法映射 /board")
    void pathsMustMatchGatewayConvention() {
        RequestMapping classMapping =
            IotDashboardController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping).isNotNull();
        assertThat(classMapping.value())
            .as("控制器映射不得带 /iot 前缀——网关 StripPrefix=1，带了会变成 /iot/iot/...")
            .containsExactly("/dashboard");

        assertThat(pathsOf("board", GetMapping.class))
            .as("读取端点必须是 GET /dashboard/board")
            .containsExactly("/board");
        assertThat(pathsOf("saveBoard", PutMapping.class))
            .as("保存端点必须是 PUT /dashboard/board（整体替换）")
            .containsExactly("/board");
    }

    @Test
    @DisplayName("两个端点都必须挂 @SaCheckLogin（不得裸奔，也不得引入未登记的权限码）")
    void endpointsMustRequireLogin() throws Exception {
        for (String method : new String[] {"board", "saveBoard"}) {
            Method target = targetOf(method);
            assertThat(target.getAnnotation(SaCheckLogin.class))
                .as("%s 必须挂 @SaCheckLogin（本服务安全拦截关闭，登录态由网关+注解双重把关）",
                    method)
                .isNotNull();
            assertThat(target.getAnnotation(SaCheckPermission.class))
                .as("%s 不声明权限码：看板是租户展示偏好，引入权限码必须同步菜单登记", method)
                .isNull();
        }
    }

    @Test
    @DisplayName("保存端点必须有 @Idempotent + @Log（防重复提交 + 审计留痕）")
    void saveMustBeIdempotentAndLogged() throws Exception {
        Method save = targetOf("saveBoard");
        assertThat(save.getAnnotation(Idempotent.class))
            .as("前端抽屉保存按钮连点/网络重试会把同一份配置写两次，必须幂等")
            .isNotNull();
        Log log = save.getAnnotation(Log.class);
        assertThat(log).as("写接口必须记操作日志（谁改了本租户看板）").isNotNull();
        assertThat(log.value()).as("审计描述必须点名对象").contains("看板");
    }

    @Test
    @DisplayName("入参必须是 DashboardBoardSaveReq（widgets 必填，组件为对象列表）")
    void saveMustTakeBoardRequest() throws Exception {
        Method save = targetOf("saveBoard");
        assertThat(save.getParameterTypes()).containsExactly(DashboardBoardSaveReq.class);
        assertThat(save.getReturnType().getSimpleName())
            .as("其余端点必须维持 R 信封（真状态码例外只在内部端点与文件下载）")
            .isEqualTo("R");
    }

    @Test
    @DisplayName("上限常量与前端一致性锚点：组件上限 50（前端 IOT_BOARD_MAX_WIDGETS=24 更保守，取并集不冲突）")
    void maxWidgetsMustBeExplicit() {
        assertThat(DashboardBoardSaveReq.MAX_WIDGETS)
            .as("上限必须是具名常量而非魔法值，且为正")
            .isEqualTo(50);
    }

    /**
     * 取指定方法上的 mapping 注解路径。
     *
     * @param method    方法名
     * @param annotation 期望的 mapping 注解类型
     * @return 路径数组
     */
    private static String[] pathsOf(String method, Class<? extends java.lang.annotation.Annotation> annotation) {
        try {
            Method target = targetOf(method);
            Object mapping = target.getAnnotation(annotation);
            assertThat(mapping).as("%s 缺少 mapping 注解", method).isNotNull();
            if (mapping instanceof GetMapping get) {
                return get.value();
            }
            if (mapping instanceof PutMapping put) {
                return put.value();
            }
            throw new AssertionError("未支持的 mapping 注解：" + annotation);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 按方法名查找端点。
     *
     * @param name 方法名
     * @return 方法
     */
    private static Method targetOf(String name) {
        for (Method method : ControllerGateMethods.businessMethodsOrFail(
            IotDashboardController.class, "组态看板端点门禁")) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("找不到端点方法：" + name);
    }
}
