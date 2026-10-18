/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 网关路由配置门禁（M2）：本地 {@code application.yml} 与 Nacos 样例必须同构地拒绝
 * {@code /iot/internal/**}。
 *
 * <p><b>为什么要有这条</b>：运行期硬保证是 starter 4.0.0 的 {@code InternalPathBlockGlobalFilter}
 * （默认开启，命中「任意层级前缀 + /internal/」即返回统一 404 信封），但两处 YAML 是运维实际会改的路由来源——
 * 只改一处或漏掉 {@code order} 都会让「配置层纵深」静默消失。本用例把「两处都有 deny 路由、
 * 且排在 iot 通配路由之前」变成构建失败。</p>
 *
 * <p><b>为什么 deny 路由仍然保留</b>（starter 已默认拦截）：它是**配置层**纵深防御与可见性
 * （运维在 Nacos 控制台就能看到「内部面被明确拒绝」），且不依赖运行时 Bean 装配；与 starter 的
 * 运行期过滤器是两道独立防线。注意两道的 404 **响应体不同**：starter 返回统一信封
 * （{@code code=404 / message=接口不存在}），{@code RedirectTo} 路由返回重定向目标页——后者只在
 * 过滤器被显式关闭（{@code ypbin.gateway.internal-path-block.enabled=false}）时才可能命中。</p>
 *
 * <p>路径基于 Maven 模块工作目录（{@code ypbin-gateway}）推导，与仓内其它源码扫描门禁同口径。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class GatewayInternalDenyRouteConfigTest {

    private static final Path LOCAL = Path.of("src", "main", "resources", "application.yml");

    private static final Path NACOS = Path.of("..", "deploy", "nacos", "ypbin-gateway.yaml");

    @Test
    @DisplayName("🔴 本地与 Nacos 两处 YAML 都必须有 /iot/internal/** deny 路由，且排在 iot 通配路由之前")
    void bothRouteFilesMustDenyInternalPathBeforeIotRoute() throws IOException {
        assertThat(LOCAL).as("网关本地路由来源必须存在").exists();
        assertThat(NACOS).as("Nacos 路由样例必须存在").exists();

        assertDenyRouteBeforeIot(LOCAL);
        assertDenyRouteBeforeIot(NACOS);
    }

    private static void assertDenyRouteBeforeIot(Path file) throws IOException {
        List<Map<String, Object>> routes = routes(file);
        Map<String, Object> deny = routeById(routes, "iot-internal-deny");
        Map<String, Object> iot = routeById(routes, "iot");

        assertThat(predicates(deny)).as("%s 的 deny 路由必须精确匹配 /iot/internal/**", file)
            .contains("Path=/iot/internal/**");
        assertThat(filters(deny)).as("%s 的 deny 路由必须用 RedirectTo 短路（不转发上游）", file)
            .anyMatch(filter -> filter.startsWith("RedirectTo=404"));
        assertThat(predicates(iot)).as("%s 的 iot 通配路由必须仍在", file).contains("Path=/iot/**");

        int denyOrder = order(deny);
        int iotOrder = order(iot);
        assertThat(denyOrder).as("%s：deny 路由必须排在 iot 通配路由之前（deny=%s iot=%s）",
                file, denyOrder, iotOrder)
            .isLessThan(iotOrder);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Path file) throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
        Map<String, Object> server = (Map<String, Object>) gateway.get("server");
        Map<String, Object> webflux = (Map<String, Object>) server.get("webflux");
        return (List<Map<String, Object>>) webflux.get("routes");
    }

    private static Map<String, Object> routeById(List<Map<String, Object>> routes, String id) {
        return routes.stream().filter(route -> id.equals(route.get("id"))).findFirst()
            .orElseThrow(() -> new AssertionError("路由缺失：" + id));
    }

    @SuppressWarnings("unchecked")
    private static List<String> predicates(Map<String, Object> route) {
        return (List<String>) route.get("predicates");
    }

    @SuppressWarnings("unchecked")
    private static List<String> filters(Map<String, Object> route) {
        return (List<String>) route.get("filters");
    }

    private static int order(Map<String, Object> route) {
        Object order = route.get("order");
        return order == null ? 0 : Integer.parseInt(String.valueOf(order));
    }
}
