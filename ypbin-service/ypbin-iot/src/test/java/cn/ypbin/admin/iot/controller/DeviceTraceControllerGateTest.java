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
import cn.ypbin.admin.iot.model.query.DeviceTraceQuery;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 消息跟踪端点的**权限与路径门禁**（看板 #8，设计 `docs/MESSAGE-TRACE-DESIGN.md` §5.1）。
 *
 * <p>为什么单独测这个：权限码选错是本能力**最容易做错、且做错了看不出来**的地方。
 * 设计 §5.1 明确规定——本端点返回的条目含下行命令标识、归因码与"设备回执"入口，
 * 属**命令侧读能力**，必须与既有命令查询端点同码（`iot:debug:get`）；
 * 若图省事挂 `iot:device:list`（设备列表页的**菜单级**权限），就是**权限降级** ✗。</p>
 *
 * <p>本用例是**源码级断言**：把"必须用哪个码"钉死，避免后来者"顺手"改成更宽的码。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class DeviceTraceControllerGateTest {

    @Test
    @DisplayName("时间线端点必须用 iot:debug:get（不得放宽为 iot:device:list）")
    void timelineMustRequireDebugGetPermission() throws Exception {
        Method method = DeviceTraceController.class.getMethod("timeline", Long.class,
            DeviceTraceQuery.class);

        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation)
            .as("时间线端点必须有权限码，否则任何人都能读命令与回执数据")
            .isNotNull();
        assertThat(List.of(annotation.value()))
            .as("本端点含下行命令与回执入口，必须与既有命令查询端点同码；"
                + "挂 iot:device:list 是权限降级（该码是设备列表页的菜单级权限）")
            .containsExactly("iot:debug:get");
    }

    @Test
    @DisplayName("路径必须与网关 StripPrefix=1 口径一致（控制器映射不带 /iot 前缀）")
    void pathsMustMatchGatewayConvention() {
        RequestMapping classMapping = DeviceTraceController.class
            .getAnnotation(RequestMapping.class);
        assertThat(classMapping).isNotNull();
        assertThat(List.of(classMapping.value()))
            .as("控制器映射不得带 /iot 前缀——网关会 StripPrefix=1，带了会变成 /iot/iot/...")
            .containsExactly("/devices");

        GetMapping getMapping = null;
        for (Method method : DeviceTraceController.class.getDeclaredMethods()) {
            GetMapping candidate = method.getAnnotation(GetMapping.class);
            if (candidate != null) {
                getMapping = candidate;
            }
        }
        assertThat(getMapping).isNotNull();
        assertThat(List.of(getMapping.value()))
            .as("按设备维度查消息，路径应为 /{deviceId}/messages")
            .containsExactly("/{deviceId}/messages");
    }

    @Test
    @DisplayName("控制器不写任何数据（一期只读聚合：不得出现 Post/Put/Delete 映射）")
    void mustStayReadOnly() {
        for (Method method : DeviceTraceController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotations())
                .as("一期#8是只读聚合，不得引入写端点（改写入路径属二批，需单独评审）：%s",
                    method.getName())
                .noneMatch(a -> a.annotationType().getSimpleName().startsWith("Post")
                    || a.annotationType().getSimpleName().startsWith("Put")
                    || a.annotationType().getSimpleName().startsWith("Delete"));
        }
    }
}
