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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 组态看板配置表 {@code iot_dashboard_board_config} 的**租户隔离机制**门禁。
 *
 * <p>与 {@link IotTenantIsolationGateTest} 的分工：那边是「清单式」门禁（把本仓所有租户表列进
 * {@code M1_TENANT_TABLES} 后逐表断言 {@code ignoreTable=false}），新表已登记进该清单；本类则**专门**
 * 针对看板这一张表，用**生产配置**真实调用租户插件的决策函数，把三件事单独钉死：</p>
 * <ol>
 *   <li>表不在 {@code ignore-tables} 里 ⇒ 插件会给它的 SQL 追加 {@code tenant_id} 条件；</li>
 *   <li>租户上下文绑定 42 时插件取到的就是 42 ⇒ 查询只可能命中本租户数据；</li>
 *   <li>无租户上下文时 <b>fail-closed</b> 抛异常 ⇒ 不存在「漏带租户即跨租户」的路径。</li>
 * </ol>
 *
 * <p><b>为什么值得单独一条</b>：看板配置的 {@code config_json} 是用户自由编辑的展示配置，
 * 一旦隔离失效，A 租户能读到 B 租户看板上配置的设备/点位标识，等于泄露对方设备资产清单。
 * 本类首次由独立验收（verify-backend/task-8）以临时用例发现「清单式门禁未登记新表」的覆盖缺口，
 * 缺口已补；本类是把那组命题固化为正式交付物。</p>
 *
 * <p>端到端越权（A 租户 token → B 租户数据，HTTP 200 + R.code=403）需要真库与登录态，归 CI 的
 * {@code -Pit} 路径；本类只验证该用例赖以成立的三条机制。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotDashboardBoardTenantIsolationTest {

    /** 被钉住的看板配置表名（与实体 {@code @TableName}、DDL 必须一致）。 */
    private static final String BOARD_TABLE = "iot_dashboard_board_config";

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("看板表不在 ignore-tables ⇒ 插件会给它追加 tenant_id 条件（真实调用生产处理器）")
    void boardTableMustNotBeIgnored() throws IOException {
        DefaultTenantLineHandler handler = productionHandler();

        assertThat(handler.ignoreTable(BOARD_TABLE))
            .as("若为 true，看板配置将失去租户隔离（跨租户可读对方设备/点位清单）；生产配置必须为 false")
            .isFalse();
        assertThat(handler.getTenantIdColumn())
            .as("租户列名必须与 DDL/实体一致")
            .isEqualTo("tenant_id");
    }

    @Test
    @DisplayName("租户上下文=42 时插件取 42；无上下文 fail-closed 抛异常（不可能退化为全表查询）")
    void tenantScopedOrFailClosed() throws IOException {
        DefaultTenantLineHandler handler = productionHandler();

        TenantContext.runWithTenant(42L, () ->
            assertThat(((LongValue) handler.getTenantId()).getValue())
                .as("插件必须用当前绑定的租户 ID 作为查询条件")
                .isEqualTo(42L));

        assertThatThrownBy(handler::getTenantId)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("缺少租户上下文");
    }

    @Test
    @DisplayName("实体继承租户基类且已登记进清单式门禁（防缺口复发）")
    void entityAndChecklistMustCoverBoardTable() throws IOException {
        Path entityDir = REPO_ROOT.resolve(
            "ypbin-service-api/ypbin-iot-api/src/main/java/cn/ypbin/admin/iot/entity");
        Path boardEntity = entityDir.resolve("IotDashboardBoardConfig.java");
        assertThat(Files.exists(boardEntity))
            .as("找不到看板实体文件 ⇒ 本门禁空跑")
            .isTrue();
        assertThat(Files.readString(boardEntity, StandardCharsets.UTF_8))
            .as("实体必须继承 TenantBaseEntity —— 否则租户插件对它失效，且通用反向门禁扫不到")
            .contains("extends TenantBaseEntity");

        String checklist = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/config/"
                + "IotTenantIsolationGateTest.java"), StandardCharsets.UTF_8);
        assertThat(checklist)
            .as("看板表必须登记进 IotTenantIsolationGateTest.M1_TENANT_TABLES：漏登记会让"
                + "「逐表 ignoreTable=false」这条清单式门禁覆盖不到它（验收方 task-8 发现的缺口）")
            .contains("\"" + BOARD_TABLE + "\"");
    }

    /** 用部署配置里的 ignore-tables 构造生产同款租户处理器。 */
    private DefaultTenantLineHandler productionHandler() throws IOException {
        Map<String, Object> root = loadYaml(REPO_ROOT.resolve("deploy/nacos/ypbin-iot.yaml"));
        TenantProperties properties = new TenantProperties();
        properties.setIgnoreTables(asStringList(section(root, "tenant").get("ignore-tables")));
        properties.setFailOnMissingTenant(true);
        // 与生产一致：租户 ID 由上下文提供（此处给空，用于验证 fail-closed 与显式绑定两条路径）
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
