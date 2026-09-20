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
 * 部署配置门禁：**平台表必须登记进 {@code ypbin.tenant.ignore-tables}**。
 *
 * <p>为什么需要这条测试：租约的每条 SQL 都打 {@code tenant_node_assignment}，而 /internal/lease/** 是
 * access 直连（只有 X-Internal-Token、没有租户身份头）。一旦漏登记，租户插件会追加 tenant_id 条件、
 * 并在无租户上下文时直接抛异常 ⇒ 领取/续约/释放/失效扫描**全部不可用**。
 * 这类「配置漏一行、功能整块不工作」的问题靠人眼审不出来（本增量第一次就是这样：
 * 文档写了、配置里没有），所以把它钉成门禁。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class NacosTenantIgnoreConfigTest {

    /** 平台表名（与 DDL、代码里的 {@code @TableName} 必须一致）。 */
    private static final String PLATFORM_TABLE = "tenant_node_assignment";

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    @DisplayName("nacos 配置里 tenant_node_assignment 必须在 ignore-tables（否则租约功能整体不可用）")
    void platformTableMustBeIgnoredByTenantPlugin() throws IOException {
        Map<String, Object> root = loadYaml(REPO_ROOT.resolve("deploy/nacos/ypbin-iot.yaml"));
        Map<String, Object> tenant = section(root, "tenant");

        assertThat(tenant)
            .as("ypbin-iot.yaml 缺少 tenant 段——租户插件的行为因此不受控")
            .containsKey("ignore-tables");
        assertThat(asStringList(tenant.get("ignore-tables")))
            .as("tenant_node_assignment 必须登记进 ignore-tables：/internal/lease/** 没有租户上下文，"
                + "漏登记会让租约的每条 SQL 被租户插件拒绝（功能整块不可用）")
            .contains(PLATFORM_TABLE);
    }

    @Test
    @DisplayName("租约参数必须在 nacos 配置里（否则跑的是代码默认值，运维改不动）")
    void leaseSectionMustExist() throws IOException {
        Map<String, Object> root = loadYaml(REPO_ROOT.resolve("deploy/nacos/ypbin-iot.yaml"));
        Map<String, Object> lease = section(root, "lease");

        assertThat(lease).containsKey("ttl").containsKey("scan-interval-ms")
            .containsKey("expected-renew-interval").containsKey("assignable-tenant-ids");
    }

    @Test
    @DisplayName("自检：ignore-tables 里登记的表必须真的在 DDL 里存在（防表名改名后配置静默失效）")
    void ignoredTableMustExistInSchema() throws IOException {
        String schema = Files.readString(REPO_ROOT.resolve("deploy/sql/006-iot-schema.sql"),
            StandardCharsets.UTF_8);

        assertThat(schema)
            .as("ignore-tables 登记了 %s，但 DDL 里找不到它——表名改名后配置会静默失效", PLATFORM_TABLE)
            .contains("CREATE TABLE " + PLATFORM_TABLE);
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
        return value == null ? List.of() : ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) throws IOException {
        assertThat(Files.exists(path)).as("找不到配置文件：%s（测试的工作目录假设被改动？）", path).isTrue();
        return new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
    }
}
