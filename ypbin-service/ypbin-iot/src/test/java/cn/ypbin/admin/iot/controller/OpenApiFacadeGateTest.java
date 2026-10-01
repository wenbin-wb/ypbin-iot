package cn.ypbin.admin.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.openapi.OpenApiAlertController;
import cn.ypbin.admin.iot.openapi.OpenApiDeviceController;
import cn.ypbin.admin.iot.openapi.OpenApiProductController;
import cn.ypbin.admin.iot.openapi.OpenApiWhoamiController;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 开放 API 门面门禁（看板 #11 门面）。
 *
 * <p>锁四条：① 门面路径全部在 {@code /open-api/v1} 之下（网关只把该前缀交给 Key 鉴权）；
 * ② 除 whoami 外每个读方法都有精确的作用域权限码（防"顺手改宽"）；
 * ③ 门面禁止一切写映射（O-7 命令下发需 C1–C4 先落地，在此之前连映射都不许出现）；
 * ④ {@code /tsl} 必须用独立码（防复用 list 码把导出权限默认塞给第三方）。</p>
 */
class OpenApiFacadeGateTest {

    private static final List<Class<?>> FACADE = List.of(
        OpenApiDeviceController.class,
        OpenApiAlertController.class,
        OpenApiProductController.class,
        OpenApiWhoamiController.class);

    @Test
    @DisplayName("门面路径全部在 /open-api/v1 之下")
    void facadeMustLiveUnderOpenApiV1() {
        for (Class<?> controller : FACADE) {
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.value()[0]).startsWith("/open-api/v1");
        }
    }

    @Test
    @DisplayName("门面禁止一切写映射（O-7 未达标前连映射都不许出现）")
    void facadeMustBeReadOnly() {
        for (Class<?> controller : FACADE) {
            for (Method method : controller.getDeclaredMethods()) {
                assertThat(method.getAnnotation(PostMapping.class))
                    .as("%s#%s 禁止 POST", controller.getSimpleName(), method.getName())
                    .isNull();
                assertThat(method.getAnnotation(PutMapping.class))
                    .as("%s#%s 禁止 PUT", controller.getSimpleName(), method.getName())
                    .isNull();
                assertThat(method.getAnnotation(DeleteMapping.class))
                    .as("%s#%s 禁止 DELETE", controller.getSimpleName(), method.getName())
                    .isNull();
            }
        }
    }

    @Test
    @DisplayName("除 whoami 外每个 GET 都有精确的作用域权限码")
    void everyGetExceptWhoamiMustDeclareScope() {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : FACADE) {
            if (controller == OpenApiWhoamiController.class) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getAnnotation(GetMapping.class) == null) {
                    continue;
                }
                SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
                if (permission == null || permission.value().length != 1) {
                    violations.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("作用域映射：device 域七枚 + alert 三枚 + product 八枚（含 tsl 独立码）")
    void scopeMappingMustMatchDesign() throws Exception {
        assertThat(permissionOf(OpenApiDeviceController.class, "page")).isEqualTo("iot:device:list");
        assertThat(permissionOf(OpenApiDeviceController.class, "latest")).isEqualTo("iot:device:latest");
        assertThat(permissionOf(OpenApiDeviceController.class, "series")).isEqualTo("iot:series:get");
        assertThat(permissionOf(OpenApiDeviceController.class, "events")).isEqualTo("iot:device:list");
        assertThat(permissionOf(OpenApiDeviceController.class, "availability"))
            .isEqualTo("iot:availability:get");
        assertThat(permissionOf(OpenApiDeviceController.class, "deviceAlerts")).isEqualTo("iot:alert:list");
        assertThat(permissionOf(OpenApiDeviceController.class, "deviceAlertSummary"))
            .isEqualTo("iot:alert:list");

        assertThat(permissionOf(OpenApiAlertController.class, "page")).isEqualTo("iot:alert:list");
        assertThat(permissionOf(OpenApiAlertController.class, "detail")).isEqualTo("iot:alert:list");
        assertThat(permissionOf(OpenApiAlertController.class, "summary")).isEqualTo("iot:alert:list");

        assertThat(permissionOf(OpenApiProductController.class, "page")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "detail")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "versions")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "services")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "properties")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "commands")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "events")).isEqualTo("iot:product:list");
        assertThat(permissionOf(OpenApiProductController.class, "tsl")).isEqualTo("iot:product:tsl-export");
    }

    @Test
    @DisplayName("active-counts 不得出现在门面（设计未盘点端点一律不开放）")
    void activeCountsMustNotBeExposed() {
        for (Class<?> controller : FACADE) {
            for (Method method : controller.getDeclaredMethods()) {
                assertThat(method.getName()).doesNotContain("activeCounts");
            }
        }
    }

    private static String permissionOf(Class<?> controller, String methodName) throws Exception {
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getAnnotation(SaCheckPermission.class).value()[0];
            }
        }
        throw new IllegalStateException("方法不存在：" + controller.getSimpleName() + "#" + methodName);
    }
}
