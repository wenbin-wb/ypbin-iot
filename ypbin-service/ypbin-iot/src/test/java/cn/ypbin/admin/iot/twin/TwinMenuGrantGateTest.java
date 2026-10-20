/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字孪生菜单授权门禁：{@code 3210} 与 {@code 321001~321003} 必须同时进
 * {@code sys_role_menu} 与 {@code sys_template_menu}。
 *
 * <p>为什么要有单独一条（{@code IotMaintenanceAdminGateTest} 的通用规则已覆盖一半）：
 * 那条门禁读的是 {@code 007-iot-data.sql}，且其 {@code MODULE_CHILDREN} 只约束
 * {@code 3310}/{@code 3320} 两个平台模块目录。孪生菜单挂在既有 {@code 3204} 下，
 * **不受 K2 防孤儿断言约束**——若有人把它误登记进 {@code PLATFORM_LEVEL_MENUS}，
 * 通用门禁表现为「合法」，而整页会对所有租户消失。本用例把「必须租户可见」这条意图钉死。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinMenuGrantGateTest {

    /** 孪生菜单与按钮 id 清单（与 migration 脚本逐项一致）。 */
    private static final List<String> MENU_IDS = List.of("3210", "321001", "321002", "321003");

    /** 孪生菜单的父目录（IoT 平台目录，既有）。 */
    private static final String PARENT_MENU_ID = "3204";

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    @DisplayName("★ 孪生菜单在 007 中定义，且 platform_only=0、挂在 3204 下")
    void menusDefinedAsTenantVisibleUnderIotCatalog() throws IOException {
        String sql = strip(Files.readString(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8));
        for (String id : MENU_IDS) {
            assertThat(sql)
                .as("菜单 " + id + " 未在 007-iot-data.sql 中定义")
                .containsPattern(Pattern.compile("VALUES \\(\\s*" + id + "\\s*,"));
        }
        // 3210 必须是租户可见页面菜单，且父目录是 3204
        Pattern pageMenu = Pattern.compile(
            "VALUES \\(\\s*3210\\s*,\\s*" + PARENT_MENU_ID + "\\s*,\\s*'IotDigitalTwin'\\s*,"
                + "\\s*'menu'\\s*,\\s*0\\s*,"
                + "\\s*'/iot/twin'\\s*,\\s*'/iot/twin/index'\\s*,\\s*'iot:twin:list'");
        assertThat(sql)
            .as("3210 必须是 platform_only=0 的页面菜单，路径 /iot/twin，"
                + "component /iot/twin/index，auth_code iot:twin:list")
            .containsPattern(pageMenu);
    }

    @Test
    @DisplayName("★ 四个 id 同时进 sys_role_menu 与 sys_template_menu")
    void menusGrantedInBothTables() throws IOException {
        String sql = strip(Files.readString(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8));
        Pattern roleGrant = Pattern.compile(
            "INSERT INTO sys_role_menu[^;]*3210,\\s*321001,\\s*321002,\\s*321003", Pattern.DOTALL);
        Pattern templateGrant = Pattern.compile(
            "INSERT INTO sys_template_menu[^;]*3210,\\s*321001,\\s*321002,\\s*321003",
            Pattern.DOTALL);
        assertThat(sql)
            .as("sys_role_menu 缺少孪生菜单授权（菜单会建出来但平台管理员看不到）")
            .containsPattern(roleGrant);
        assertThat(sql)
            .as("sys_template_menu 缺少孪生菜单授权（租户管理员建角色时无法授予该菜单，"
                + "且非平台租户的该菜单会整棵丢失）")
            .containsPattern(templateGrant);
    }

    @Test
    @DisplayName("★ 孪生菜单不得进平台级清单（否则对租户不可见）")
    void menusMustNotBePlatformOnly() throws IOException {
        Path gate = REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/config/"
                + "IotMaintenanceAdminGateTest.java");
        assertThat(gate).exists();
        String source = Files.readString(gate, StandardCharsets.UTF_8);
        // 只检查 PLATFORM_LEVEL_MENUS 那一段（避免误判注释里出现的 id）
        int start = source.indexOf("PLATFORM_LEVEL_MENUS");
        assertThat(start).as("找不到 PLATFORM_LEVEL_MENUS 定义").isGreaterThan(0);
        int end = source.indexOf(";", start);
        String block = source.substring(start, Math.max(end, start));
        for (String id : MENU_IDS) {
            assertThat(block)
                .as("孪生菜单 " + id + " 被登记进 PLATFORM_LEVEL_MENUS 会让整页对租户消失")
                .doesNotContain("\"" + id + "\"");
        }
    }

    @Test
    @DisplayName("迁移脚本与 007 的孪生菜单段 id 集合一致")
    void migrationMenuMatchesInstallScript() throws IOException {
        Path migration = REPO_ROOT.resolve(
            "deploy/sql/migration/2026-10-21-iot-twin-menu.sql");
        assertThat(migration).exists();
        String sql = strip(Files.readString(migration, StandardCharsets.UTF_8));
        for (String id : MENU_IDS) {
            assertThat(sql)
                .as("孪生菜单迁移缺少 id " + id)
                .containsPattern(Pattern.compile("VALUES \\(\\s*" + id + "\\s*,"));
        }
        assertThat(sql)
            .as("迁移脚本必须同时授权 sys_role_menu 与 sys_template_menu")
            .contains("INSERT INTO sys_role_menu")
            .contains("INSERT INTO sys_template_menu");
    }

    /**
     * 剥离 SQL 行注释（门禁文本匹配必须作用在剥注释后的脚本上，否则注释掉的
     * INSERT 会被当成真实菜单）。
     *
     * @param sql 原始 SQL
     * @return 剥注释后的 SQL
     */
    private static String strip(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
