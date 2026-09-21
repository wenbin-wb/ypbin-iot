/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * M-1 多租户隔离门禁：证明物模型域的表<b>确实受租户插件约束</b>（§10「每服务必测」的机制面）。
 *
 * <p><b>本测试覆盖什么、不覆盖什么（避免口径含糊）：</b>§10/§14 要求的端到端越权用例是
 * 「A 租户 token 访问 B 租户数据 → HTTP 200 + R.code=403」，它需要真实 DB、真实登录态与
 * HTTP 栈，归属 CI 的集成测试面（本机与纯单测形态跑不了）。本测试<b>不替代</b>那条用例，
 * 它验证的是那条用例赖以成立的三条机制，且是<b>真实调用</b>租户插件的决策函数而非读源码推断：</p>
 * <ol>
 *   <li>物模型域的表不在 {@code ignore-tables} 里 ⇒ 插件会给它们的 SQL 追加 {@code tenant_id} 条件；</li>
 *   <li>租户上下文绑定后，插件取到的租户 ID 就是当前租户 ⇒ 查询只可能命中本租户数据；</li>
 *   <li>无租户上下文时插件 <b>fail-closed 抛异常</b>（而非放行全表）⇒ 不存在「漏带租户就跨租户」的路径。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotTenantIsolationGateTest {

    /** M-1 新增的租户业务表（必须逐个受隔离）。 */
    private static final List<String> M1_TENANT_TABLES = List.of(
        "iot_product", "iot_product_version", "iot_service", "iot_property", "iot_command",
        "iot_event", "iot_point_mapping", "iot_device_group", "iot_device_group_member",
        "iot_device_tag", "iot_shadow");

    /** 平台表（不继承租户基类，必须被忽略——作为「ignoreTable 能返回 true」的自检锚点）。 */
    /**
     * 平台表白名单：不继承 {@code TenantBaseEntity} 的实体**只允许**是这些表。
     *
     * <p>新增平台表时必须同步改这里 + {@code deploy/nacos/ypbin-iot.yaml} 的 ignore-tables——
     * 两处缺一，要么本用例转红，要么 {@code NacosTenantIgnoreConfigTest} 转红（这是有意设计的双保险）。</p>
     */
    private static final List<String> PLATFORM_TABLES =
        List.of("tenant_node_assignment", "access_node", "tenant_ledger");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path ENTITY_DIR = REPO_ROOT.resolve(
        "ypbin-service-api/ypbin-iot-api/src/main/java/cn/ypbin/admin/iot/entity");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("M-1 租户表都不在 ignore-tables：插件会给它们追加 tenant_id 条件（隔离生效）")
    void tenantTablesMustNotBeIgnored() throws IOException {
        DefaultTenantLineHandler handler = productionHandler();

        // 自检：ignoreTable 对平台表必须返回 true，否则本断言可能恒真（教训二十七）
        assertThat(handler.ignoreTable(PLATFORM_TABLES.get(0)))
            .as("自检失败：ignoreTable 对平台表也应返回 true，否则本用例的断言无法咬人")
            .isTrue();

        for (String table : M1_TENANT_TABLES) {
            assertThat(handler.ignoreTable(table))
                .as("表 %s 被登记进 ignore-tables ⇒ 租户插件不再给它追加 tenant_id 条件，"
                    + "跨租户读写将失去隔离", table)
                .isFalse();
        }
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    @DisplayName("租户上下文绑定后插件只取当前租户；无上下文时 fail-closed 拒绝执行")
    void tenantIdMustBeScopedOrFailClosed() throws IOException {
        DefaultTenantLineHandler handler = productionHandler();

        TenantContext.runWithTenant(42L, () ->
            assertThat(((LongValue) handler.getTenantId()).getValue())
                .as("插件必须用当前绑定的租户 ID 作为查询条件")
                .isEqualTo(42L));

        // 无租户上下文 ⇒ 抛业务异常（fail-closed），绝不退化为「不带租户条件的全表查询」
        assertThatThrownBy(handler::getTenantId)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("缺少租户上下文");
    }

    @Test
    @DisplayName("M-1 实体全部继承 TenantBaseEntity（漏继承会让该表脱离隔离）")
    void m1EntitiesMustExtendTenantBaseEntity() throws IOException {
        List<String> tenantTables = new ArrayList<>();
        List<String> notExtending = new ArrayList<>();
        try (var files = Files.list(ENTITY_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("@TableName\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(code);
                if (!matcher.find()) {
                    continue;
                }
                String table = matcher.group(1);
                if (code.contains("extends TenantBaseEntity")) {
                    tenantTables.add(table);
                } else {
                    notExtending.add(table);
                }
            }
        }
        assertThat(tenantTables)
            .as("没有扫到任何租户实体 ⇒ 本门禁空跑（教训八：0 违规可能是没跑到）")
            .containsAll(M1_TENANT_TABLES);
        assertThat(notExtending)
            .as("非租户基类的实体只允许平台表，其它表漏继承会让租户插件对它们失效")
            .containsExactlyInAnyOrderElementsOf(PLATFORM_TABLES);
    }

    @Test
    @DisplayName("服务层不得在**租户实体**上写 tenant_id 过滤（隔离由插件统一追加；平台表/ DTO 不算）")
    void servicesMustNotHandWriteTenantFilterOnTenantEntities() throws IOException {
        Set<String> tenantEntities = tenantEntitySimpleNames();
        assertThat(tenantEntities).as("没有扫到租户实体 ⇒ 本门禁空跑").contains("IotProduct", "IotShadow");

        Path serviceDir = REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/service");
        Pattern handWritten = Pattern.compile("([A-Z][A-Za-z0-9]*)::getTenantId");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (var files = Files.walk(serviceDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                Matcher matcher = handWritten.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (tenantEntities.contains(matcher.group(1))) {
                        offenders.add(file.getFileName() + " -> " + matcher.group(1));
                    }
                }
            }
        }
        assertThat(scanned).as("一个服务文件都没扫到 ⇒ 空跑").isPositive();
        assertThat(offenders)
            .as("租户实体上的隔离条件由租户插件统一追加；服务层手写会绕过统一策略")
            .isEmpty();
    }

    /** 扫描实体目录，取出继承 TenantBaseEntity 的实体简单类名。 */
    private Set<String> tenantEntitySimpleNames() throws IOException {
        Set<String> names = new HashSet<>();
        try (var files = Files.list(ENTITY_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                if (code.contains("extends TenantBaseEntity")) {
                    names.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        return names;
    }

    /** 用部署配置里的 ignore-tables 构造生产同款租户处理器。 */
    private DefaultTenantLineHandler productionHandler() throws IOException {
        Map<String, Object> root = loadYaml(REPO_ROOT.resolve("deploy/nacos/ypbin-iot.yaml"));
        TenantProperties properties = new TenantProperties();
        properties.setIgnoreTables(asStringList(section(root, "tenant").get("ignore-tables")));
        properties.setFailOnMissingTenant(true);
        return new DefaultTenantLineHandler(() -> Optional.empty(), properties);
    }

    private Map<String, Object> loadYaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> root, String name) {
        Object ypbin = root.get("ypbin");
        assertThat(ypbin).as("配置缺少顶层 ypbin 段").isInstanceOf(Map.class);
        Object section = ((Map<String, Object>) ypbin).get(name);
        assertThat(section).as("配置缺少 ypbin.%s 段", name).isInstanceOf(Map.class);
        return (Map<String, Object>) section;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        assertThat(value).as("ignore-tables 必须是列表").isInstanceOf(List.class);
        return ((List<Object>) value).stream().map(String::valueOf).toList();
    }
}
