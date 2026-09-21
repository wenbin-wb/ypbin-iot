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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权限码门禁：Controller 上标注的每个 {@code @SaCheckPermission} 必须已在菜单 SQL 注册。
 *
 * <p>为什么需要这条测试：权限的唯一数据源是 {@code sys_menu.auth_code}，sa-token 校验时按权限码查
 * 用户已授菜单。Controller 标了一个没人注册过的权限码 ⇒ <b>非超管角色永远拿不到该权限</b>，
 * 对应功能对租户用户整块不可用（超管因 {@code *:*:*} 通配而不受影响，因此人工点测极易漏过）。
 * 本增量第一次就是这样：{@code PUT /devices/{id}} 标了 {@code iot:device:update}，而菜单只种了
 * list/create/delete。这类「代码与数据不同步」靠人眼审不出来，故钉成门禁。</p>
 *
 * <p>校验对象：{@code deploy/sql/007-iot-data.sql}（全新安装）与
 * {@code deploy/sql/migration/*-iot-*.sql}（已上线库）——两者都必须覆盖，避免只改一边的半同步
 * （教训三十三）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotPermissionCodeGateTest {

    /** 从 Java 源码提取 {@code @SaCheckPermission("...")} 的字面量权限码。 */
    private static final Pattern PERMISSION_ANNOTATION =
        Pattern.compile("@SaCheckPermission\\(\\s*\"([^\"]+)\"\\s*\\)");

    /** 从 SQL 提取 IoT 权限码字面量（形如 {@code 'iot:product:list'}）。 */
    private static final Pattern SQL_AUTH_CODE = Pattern.compile("'(iot:[a-z-]+:[a-z-]+)'");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path CONTROLLER_DIR =
        REPO_ROOT.resolve("ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller");

    @Test
    @DisplayName("Controller 标注的每个 @SaCheckPermission 都已在 007 与 migration 中注册")
    void everyControllerPermissionMustBeRegistered() throws IOException {
        Set<String> declared = collectControllerPermissions();
        assertThat(declared)
            .as("没有扫到任何 @SaCheckPermission —— 门禁本身失效（教训八：0 违规可能是没跑到）")
            .isNotEmpty();

        Set<String> freshInstall = collectSqlPermissions(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"));

        Set<String> migration = new HashSet<>();
        Path migrationDir = REPO_ROOT.resolve("deploy/sql/migration");
        try (Stream<Path> files = Files.list(migrationDir)) {
            List<Path> iotMigrations = files
                .filter(path -> path.getFileName().toString().contains("-iot-"))
                .sorted()
                .toList();
            for (Path path : iotMigrations) {
                migration.addAll(collectSqlPermissions(path));
            }
        }

        Set<String> missingInFresh = difference(declared, freshInstall);
        Set<String> missingInMigration = difference(declared, migration);

        assertThat(missingInFresh)
            .as("以下权限码被 Controller 使用但未在 007-iot-data.sql 注册 ⇒ 非超管角色永远无权，"
                + "对应功能整块不可用")
            .isEmpty();
        assertThat(missingInMigration)
            .as("以下权限码未同步进 migration/*-iot-*.sql（已上线库升级后同样不可用）")
            .isEmpty();
    }

    @Test
    @DisplayName("007 与 migration 注册的权限码集合完全一致（防只改一边的半同步，教训三十三）")
    void sqlSetsMustAgree() throws IOException {
        Set<String> freshInstall = collectSqlPermissions(
            REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"));
        Set<String> migration = new HashSet<>();
        try (Stream<Path> files = Files.list(REPO_ROOT.resolve("deploy/sql/migration"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().contains("-iot-"))
                .sorted().toList()) {
                migration.addAll(collectSqlPermissions(path));
            }
        }
        assertThat(freshInstall).isEqualTo(migration);
    }

    private Set<String> collectControllerPermissions() throws IOException {
        Set<String> permissions = new HashSet<>();
        try (Stream<Path> files = Files.list(CONTROLLER_DIR)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                .toList()) {
                Matcher matcher = PERMISSION_ANNOTATION.matcher(
                    Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    permissions.add(matcher.group(1));
                }
            }
        }
        return permissions;
    }

    private Set<String> collectSqlPermissions(Path sqlFile) throws IOException {
        Set<String> permissions = new HashSet<>();
        Matcher matcher = SQL_AUTH_CODE.matcher(Files.readString(sqlFile, StandardCharsets.UTF_8));
        while (matcher.find()) {
            permissions.add(matcher.group(1));
        }
        return permissions;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> diff = new HashSet<>(left);
        diff.removeAll(right);
        return diff;
    }
}
