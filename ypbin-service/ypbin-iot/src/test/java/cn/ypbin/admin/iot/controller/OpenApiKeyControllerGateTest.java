package cn.ypbin.admin.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.openapi.OpenApiKeyDtos;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 开放 API Key 管理端点门禁（看板 #11 第 1 批）。
 *
 * 锁三条：管理端点必须有独立权限码（新码导菜单并授权，不借宽权限）；
 * 内部校验端点路径在 /internal/** 之下（既有 X-Internal-Token 守护自动覆盖）；
 * 管理路径刻意避开 /open-api/**（不落入网关 API Key 过滤器）。
 */
class OpenApiKeyControllerGateTest {

    @Test
    @DisplayName("管理端点权限码：create/list/revoke 三枚各自独立")
    void manageEndpointsMustUseDedicatedPermissions() throws Exception {
        Method create = OpenApiKeyController.class.getMethod("create", OpenApiKeyDtos.CreateReq.class);
        SaCheckPermission cp = create.getAnnotation(SaCheckPermission.class);
        assertThat(cp).isNotNull();
        assertThat(List.of(cp.value())).containsExactly("iot:openapi:key-create");

        Method list = OpenApiKeyController.class.getMethod("list");
        SaCheckPermission lp = list.getAnnotation(SaCheckPermission.class);
        assertThat(lp).isNotNull();
        assertThat(List.of(lp.value())).containsExactly("iot:openapi:key-list");

        Method revoke = OpenApiKeyController.class.getMethod("revoke", Long.class);
        SaCheckPermission rp = revoke.getAnnotation(SaCheckPermission.class);
        assertThat(rp).isNotNull();
        assertThat(List.of(rp.value())).containsExactly("iot:openapi:key-revoke");
    }

    @Test
    @DisplayName("内部校验端点必须在 /internal/** 之下（受 X-Internal-Token 守护）")
    void internalVerifyMustLiveUnderInternal() {
        RequestMapping mapping = InternalOpenApiKeyController.class
            .getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()[0]).startsWith("/internal/");
    }
}