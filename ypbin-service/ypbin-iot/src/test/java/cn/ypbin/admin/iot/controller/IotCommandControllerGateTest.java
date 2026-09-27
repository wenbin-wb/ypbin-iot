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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.command.CommandReplyReq;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.starter.log.annotation.Log;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 在线调试端点的**权限码与例外范围**门禁。
 *
 * <p>两件事必须被钉死：</p>
 * <ol>
 *   <li>三个浏览器端点各自的权限码（下发/重发 = {@code iot:debug:send}，查询 = {@code iot:debug:get}）——
 *       把"查看"与"下发"写成同一个码会让只读角色能给设备发命令；</li>
 *   <li>回执内部端点**不得**标注权限码（它由 {@code X-Internal-Token} 守卫、没有登录态），
 *       且**不得**返回真状态码——真状态码的例外只属于 {@code /internal/mqtt/**}（设计决策 D2），
 *       本类用「方法签名不含 HttpServletResponse」把这条约束变成可执行的断言。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-02
 */
class IotCommandControllerGateTest {

    @Test
    @DisplayName("三个端点的权限码逐一对应（查询与下发绝不能同码）")
    void permissionsMustMatchEachEndpoint() throws Exception {
        assertPermission("send", new Class<?>[] {Long.class, CommandSendReq.class},
            "iot:debug:send");
        assertPermission("page", new Class<?>[] {Long.class, CommandQuery.class},
            "iot:debug:get");
        assertPermission("resend", new Class<?>[] {Long.class, String.class}, "iot:debug:send");
    }

    @Test
    @DisplayName("回执内部端点：无权限码，且不得出现原始响应对象（真状态码例外不得扩散）")
    void internalReplyEndpointMustStayInsideTheConvention() throws Exception {
        Method reply = InternalCommandReplyController.class.getMethod("reply", CommandReplyReq.class,
            Long.class);
        assertThat(reply.getAnnotation(SaCheckPermission.class))
            .as("内部端点没有登录态，标权限码会让规则回流永远 403").isNull();
        assertThat(reply.getReturnType().getSimpleName())
            .as("回执端点也必须返回 R 信封（真状态码例外只在 /internal/mqtt/**）").isEqualTo("R");
        for (Method method : InternalCommandReplyController.class.getDeclaredMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                assertThat(type.getSimpleName())
                    .as("内部回执端点不得使用原始响应对象（真状态码破例只在 /internal/mqtt/**）")
                    .doesNotContain("HttpServletResponse");
            }
        }
    }

    @Test
    @DisplayName("下发/查询/重发都不得使用原始响应对象（浏览器端维持 HTTP 200 + R.code 惯例）")
    void browserEndpointsMustKeepTheEnvelope() {
        for (Method method : IotCommandController.class.getDeclaredMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                assertThat(type.getSimpleName())
                    .as("%s 的参数不得是 HttpResponse/HttpServletResponse", method.getName())
                    .doesNotContain("HttpServletResponse");
            }
            assertThat(method.getReturnType().getSimpleName())
                .as("%s 必须返回 R 信封", method.getName()).isEqualTo("R");
        }
    }

    @Test
    @DisplayName("下发与重发必须标注 @Log（写操作规约）")
    void writeEndpointsMustBeLogged() throws Exception {
        assertThat(IotCommandController.class
            .getMethod("send", Long.class, CommandSendReq.class)
            .getAnnotation(Log.class)).isNotNull();
        assertThat(IotCommandController.class.getMethod("resend", Long.class, String.class)
            .getAnnotation(Log.class)).isNotNull();
    }

    /**
     * 断言某方法的权限码。
     *
     * @param method         方法名
     * @param parameterTypes 参数类型
     * @param expected       期望权限码
     * @throws Exception 反射查找失败
     */
    private static void assertPermission(String method, Class<?>[] parameterTypes, String expected)
        throws Exception {
        SaCheckPermission annotation = IotCommandController.class.getMethod(method, parameterTypes)
            .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).as("%s 必须标注 @SaCheckPermission", method).isNotNull();
        assertThat(annotation.value()).as("%s 的权限码必须与菜单登记一致", method)
            .containsExactly(expected);
    }
}
