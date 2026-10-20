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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字孪生权限码门禁：Controller 标注的 {@code iot:twin:*} 必须同时登记在 007 与 migration。
 *
 * <p>与 {@code IotPermissionCodeGateTest} 同一判据、更窄范围（只看孪生控制器）。
 * 为什么还要单独一条：那条门禁证明的是「声明的码都被注册过」，但**证不了「四个码齐全」**
 * ——若有人误删 {@code iot:twin:update} 的控制器注解，声明的集合会少一个而旧门禁仍全绿
 * （它的判据是「声明 ⊆ 注册」，不是双向相等）。本用例把四个码作为**显式清单**钉死。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinPermissionCodeGateTest {

    /** 孪生域必须存在的四个权限码（清单陈旧或漏登记都会转红）。 */
    private static final List<String> REQUIRED_CODES = List.of(
        "iot:twin:list", "iot:twin:create", "iot:twin:update", "iot:twin:delete");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path CONTROLLER_DIR = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller");

    /** 与 {@code IotPermissionCodeGateTest} 同口径：兼容两种注解写法。 */
    private static final Pattern PERMISSION_ANNOTATION =
        Pattern.compile("@SaCheckPermission\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    /** 从 SQL 提取 IoT 权限码字面量（与既有门禁正则同源）。 */
    private static final Pattern SQL_AUTH_CODE = Pattern.compile("'(iot:[a-z-]+:[a-z-]+)'");

    @Test
    @DisplayName("★ 孪生控制器声明的权限码恰好是四个必需码（多一个少一个都转红）")
    void declaredCodesMustEqualRequiredSet() throws IOException {
        Set<String> declared = collectTwinPermissions();
        assertThat(declared)
            .as("没有扫到任何孪生权限码 —— 门禁本身失效（0 违规可能是没跑到）")
            .isNotEmpty();
        assertThat(declared)
            .as("孪生控制器声明的权限码集合必须与必需码完全一致")
            .containsExactlyInAnyOrderElementsOf(REQUIRED_CODES);
    }

    @Test
    @DisplayName("四个权限码必须同时出现在 007-iot-data.sql 与孪生 migration")
    void codesMustBeRegisteredInBothSql() throws IOException {
        Set<String> fresh = collectSqlPermissions(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"));
        Set<String> migration = collectMigrationPermissions();
        for (String code : REQUIRED_CODES) {
            assertThat(fresh)
                .as("全新安装脚本缺少权限码 " + code + "（非超管角色将永远拿不到该权限）")
                .contains(code);
            assertThat(migration)
                .as("迁移脚本缺少权限码 " + code + "（已上线库升级后该功能整块不可用）")
                .contains(code);
        }
    }

    @Test
    @DisplayName("★ 007 与 migration 的权限码集合完全一致（防只改一边的半同步）")
    void sqlSetsMustAgree() throws IOException {
        Set<String> fresh = collectSqlPermissions(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"));
        Set<String> migration = collectMigrationPermissions();
        assertThat(fresh)
            .as("007 与 migration/*.sql 的 IoT 权限码集合必须相等（只改一边会导致"
                + "全新安装与升级库的能力不一致）")
            .isEqualTo(migration);
    }

    @Test
    @DisplayName("孪生的四个权限码在 migration 文件里真实存在（按文件名定位，不靠遍历）")
    void twinMigrationFileMustDeclareCodes() throws IOException {
        Path twinMenu = REPO_ROOT.resolve(
            "deploy/sql/migration/2026-10-21-iot-twin-menu.sql");
        assertThat(twinMenu)
            .as("孪生菜单迁移文件必须存在（否则已上线库升级后菜单与权限码都不会创建）")
            .exists();
        String sql = strip(Files.readString(twinMenu, StandardCharsets.UTF_8));
        for (String code : REQUIRED_CODES) {
            assertThat(sql)
                .as("孪生菜单迁移缺少 " + code)
                .contains("'" + code + "'");
        }
    }

    private static Set<String> collectTwinPermissions() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(CONTROLLER_DIR)) {
            List<Path> twinControllers = files
                .filter(path -> path.getFileName().toString().startsWith("IotTwin"))
                .sorted()
                .toList();
            assertThat(twinControllers)
                .as("没找到任何 IotTwin*Controller（控制器改名后本门禁会空跑）")
                .isNotEmpty();
            for (Path file : twinControllers) {
                String source = strip(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = PERMISSION_ANNOTATION.matcher(source);
                while (matcher.find()) {
                    declared.add(matcher.group(1));
                }
            }
        }
        return declared;
    }

    private static Set<String> collectSqlPermissions(Path sqlFile) throws IOException {
        Set<String> permissions = new LinkedHashSet<>();
        Matcher matcher = SQL_AUTH_CODE.matcher(
            Files.readString(sqlFile, StandardCharsets.UTF_8));
        while (matcher.find()) {
            permissions.add(matcher.group(1));
        }
        return permissions;
    }

    private static Set<String> collectMigrationPermissions() throws IOException {
        Path dir = REPO_ROOT.resolve("deploy/sql/migration");
        List<String> contents = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files
                .filter(item -> item.getFileName().toString().contains("-iot-"))
                .sorted().toList()) {
                contents.add(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        Set<String> permissions = new LinkedHashSet<>();
        Matcher matcher = SQL_AUTH_CODE.matcher(String.join("\n", contents));
        while (matcher.find()) {
            permissions.add(matcher.group(1));
        }
        return permissions;
    }

    /**
     * 剥离 {@code --} 行注释与块注释（门禁文本匹配必须作用在剥注释后的代码上）。
     *
     * @param text 原始文本
     * @return 剥注释后的文本
     */
    private static String strip(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("--[^\\n]*", "");
    }
}
