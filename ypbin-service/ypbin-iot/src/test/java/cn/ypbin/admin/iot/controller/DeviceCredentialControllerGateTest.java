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
import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyReq;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.log.enums.Include;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 凭据端点的**注解级门禁**：权限码逐一对应、写操作必须幂等、签发端点必须排除日志采集体。
 *
 * <p>为什么逐方法断言而不是只数总数：{@code GET} 与 {@code DELETE} 的权限码**必须不同**
 * （get 是只读元信息、revoke 能让在网设备掉线），复制粘贴写反了在总数上完全看不出来，
 * 却会让「只有查看权限的人能吊销设备」。同类门禁先例见 {@code IotMaintenanceAdminGateTest}。</p>
 *
 * <p>为什么单独钉 {@code @Log(excludes=…)}：签发响应里有**一次性明文口令**。starter 当前默认
 * 采集集合（{@code REQUEST_PARAM/IP/CLIENT}）不含响应体，但那是**可配置的默认值**；
 * 把排除项写在调用点上，才让「口令不得进操作日志」成为读代码就能确认的事实。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class DeviceCredentialControllerGateTest {

    @Test
    @DisplayName("四个端点的 @SaCheckPermission 逐一对应（查看与吊销绝不能同码）")
    void permissionsMustMatchEachEndpoint() throws Exception {
        assertPermission("issue", new Class<?>[] {Long.class}, "iot:credential:issue");
        assertPermission("view", new Class<?>[] {Long.class}, "iot:credential:get");
        assertPermission("revoke", new Class<?>[] {Long.class}, "iot:credential:revoke");
        assertPermission("connection", new Class<?>[] {Long.class}, "iot:credential:get");

        List<String> codes = Arrays.stream(DeviceCredentialController.class.getDeclaredMethods())
            .filter(method -> method.getAnnotation(SaCheckPermission.class) != null)
            .map(method -> method.getAnnotation(SaCheckPermission.class).value()[0])
            .toList();
        assertThat(codes)
            .as("每个端点都必须显式标注权限码（本仓规约：下游服务 @SaCheckPermission 真正生效）")
            .hasSize(4);
    }

    @Test
    @DisplayName("签发必须 @Idempotent；吊销**不得**加（设计要求重复 DELETE 返回 200）")
    void idempotencyMustFollowTheDesignContract() throws Exception {
        assertThat(DeviceCredentialController.class
            .getMethod("issue", Long.class).getAnnotation(Idempotent.class))
            .as("签发端点连点两次 ⇒ 调用方抄到口令 A、库里生效的是口令 B，现象是「抄对了却连不上」")
            .isNotNull();
        assertThat(DeviceCredentialController.class
            .getMethod("revoke", Long.class).getAnnotation(Idempotent.class))
            .as("设计 P0-3 明确要求「重复 DELETE 返回 200（幂等）」；@Idempotent 会把它变成 R.code=409，"
                + "与契约冲突（生产实测：秒内重复 DELETE 得到 409「请勿重复提交」）。"
                + "吊销的幂等性由服务层实现：已吊销即直接返回、不改写首次吊销时刻")
            .isNull();
    }

    @Test
    @DisplayName("签发端点必须把请求体与响应体排除出操作日志（响应里有一次性明文）")
    void issueEndpointMustExcludeBodiesFromOperationLog() throws Exception {
        Log log = DeviceCredentialController.class.getMethod("issue", Long.class).getAnnotation(Log.class);
        assertThat(log).as("写操作必须有 @Log（本仓规约）").isNotNull();
        assertThat(Arrays.asList(log.excludes()))
            .as("签发响应含一次性明文口令 ⇒ 必须显式排除响应体（以及请求体，防将来加了入参）")
            .contains(Include.RESPONSE_BODY, Include.REQUEST_BODY);
    }

    @Test
    @DisplayName("内部校验端点不得标注权限码（它对机器开放，靠 X-Internal-Token 守卫）")
    void internalVerifyEndpointMustNotCarryPermissionCode() throws Exception {
        Method verify = InternalDeviceCredentialController.class
            .getMethod("verify", DeviceCredentialVerifyReq.class);
        assertThat(verify.getAnnotation(SaCheckPermission.class))
            .as("内部端点没有登录态，标权限码会让 broker 侧永远 403")
            .isNull();
        assertThat(InternalDeviceCredentialController.class.getAnnotation(SaCheckPermission.class)).isNull();
    }

    /**
     * 断言某端点方法的权限码。
     *
     * @param method         方法名
     * @param parameterTypes 参数类型
     * @param expected       期望权限码
     * @throws Exception 反射查找失败
     */
    private static void assertPermission(String method, Class<?>[] parameterTypes, String expected)
        throws Exception {
        SaCheckPermission annotation = DeviceCredentialController.class
            .getMethod(method, parameterTypes).getAnnotation(SaCheckPermission.class);
        assertThat(annotation).as("%s 必须标注 @SaCheckPermission", method).isNotNull();
        assertThat(annotation.value()).as("%s 的权限码必须与菜单登记一致", method)
            .containsExactly(expected);
    }
}
