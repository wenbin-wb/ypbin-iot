package cn.ypbin.admin.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.PlatformAlertQuery;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 平台告警读端点门禁（看板 #10 二批）。
 *
 * 锁两条：权限码与网关路径口径。
 */
class PlatformAlertControllerGateTest {

    @Test
    @DisplayName("分页端点必须挂 iot:alert:list（复用告警读权限，不新造码、不放宽）")
    void pageMustRequireAlertListPermission() throws Exception {
        Method method = PlatformAlertController.class.getMethod("page",
            PlatformAlertQuery.class);

        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        assertThat(annotation)
            .as("平台告警是只读展示，必须带权限码")
            .isNotNull();
        assertThat(List.of(annotation.value()))
            .containsExactly("iot:alert:list");
    }

    @Test
    @DisplayName("路径必须与网关 StripPrefix=1 口径一致（控制器映射不带 /iot 前缀）")
    void pathsMustMatchGatewayConvention() throws Exception {
        RequestMapping classMapping = PlatformAlertController.class
            .getAnnotation(RequestMapping.class);
        assertThat(classMapping).isNotNull();
        assertThat(List.of(classMapping.value()))
            .containsExactly("/platform-alerts");

        Method method = PlatformAlertController.class.getMethod("page",
            PlatformAlertQuery.class);
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        // GET 无路径 = 挂类级前缀根（与 IotAlertController.page 同款）
        assertThat(getMapping.value().length).isLessThanOrEqualTo(1);
    }
}