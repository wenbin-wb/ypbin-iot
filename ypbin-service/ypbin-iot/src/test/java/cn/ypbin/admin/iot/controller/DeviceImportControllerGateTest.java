/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.query.DeviceImportRowQuery;
import cn.ypbin.admin.iot.service.DeviceImportService;
import cn.ypbin.starter.log.annotation.Log;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量导入端点的**权限码与路由契约**门禁（看板 #7）。
 *
 * <p>三件事必须被钉死，且都靠人眼审不出来：</p>
 * <ol>
 *   <li><b>四个端点各自的路径</b>：用户拿到的失败行 CSV 里写着「删掉最后两列后重传」，
 *       而失败行 CSV 的表头来自模板——端点的路径一旦漂移，页面上四个按钮里有几个会 404，
 *       而编译、类型检查、其它门禁**全绿**；</li>
 *   <li><b>权限码分流</b>：读（模板/批次/明细/失败行）走 {@code iot:device:list}，
 *       写（上传）走 {@code iot:device:import}。把上传也标成 list 会让只读角色能批量建上万台设备；</li>
 *   <li><b>真状态码例外不扩散</b>：两个下载端点返回 {@code ResponseEntity}（文件下载必须如此），
 *       其余端点必须维持 {@code R} 信封——这是「HTTP 200 + R.code」惯例的最小例外面，
 *       一旦有人往查询端点上加了原始响应对象就转红。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class DeviceImportControllerGateTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    @DisplayName("★ 四个端点的路径与权限码逐一对应（下载走 list、上传走 import）")
    void endpointsMustMatchSpec() throws Exception {
        assertRoute("template", new Class<?>[] {}, "iot:device:list", "/devices/import/template");
        assertRoute("upload", new Class<?>[] {org.springframework.web.multipart.MultipartFile.class},
            "iot:device:import", null);
        assertRoute("pageBatches", new Class<?>[] {long.class, long.class},
            "iot:device:list", "/devices/import");
        assertRoute("detail", new Class<?>[] {Long.class, DeviceImportRowQuery.class},
            "iot:device:list", "/devices/import/{batchId}");
        assertRoute("failedCsv", new Class<?>[] {Long.class},
            "iot:device:list", "/devices/import/{batchId}/failed.csv");
    }

    @Test
    @DisplayName("★ 上传端点必须有独立权限码 iot:device:import（不得复用 iot:device:create）")
    void uploadMustUseDedicatedPermission() throws Exception {
        Method upload = DeviceImportController.class.getMethod("upload",
            org.springframework.web.multipart.MultipartFile.class);
        SaCheckPermission permission = upload.getAnnotation(SaCheckPermission.class);
        assertThat(permission).as("上传端点必须标注权限码").isNotNull();
        assertThat(permission.value())
            .as("批量导入比单条创建高危得多（一次请求能建上万台）⇒ 必须有独立权限码，"
                + "否则「只想让某角色逐台建」的租户被迫放开批量口子")
            .containsExactly("iot:device:import");
    }

    @Test
    @DisplayName("上传端点必须记操作日志（审计：谁在什么时候传了哪批设备）")
    void uploadMustBeLogged() throws Exception {
        Method upload = DeviceImportController.class.getMethod("upload",
            org.springframework.web.multipart.MultipartFile.class);
        assertThat(upload.getAnnotation(Log.class)).as("批量写入必须有操作日志").isNotNull();
    }

    @Test
    @DisplayName("★ 真状态码例外只允许在两个**下载**端点上，其余端点必须是 R 信封")
    void rawResponsesOnlyAllowedOnDownloads() {
        List<String> offenders = new ArrayList<>();
        for (Method method : DeviceImportController.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GetMapping.class)
                && !method.isAnnotationPresent(PostMapping.class)) {
                continue;
            }
            String returnType = method.getReturnType().getSimpleName();
            boolean isDownload = method.getName().equals("template")
                || method.getName().equals("failedCsv");
            if (isDownload) {
                assertThat(returnType)
                    .as("%s 是文件下载，必须返回 ResponseEntity 字节（返回 R<String> 会让浏览器"
                        + "拿到一段被引号包裹的 JSON，用户点「下载」得到一个打不开的 .csv）",
                        method.getName())
                    .isEqualTo("ResponseEntity");
            } else if (!"R".equals(returnType)) {
                offenders.add(method.getName() + " -> " + returnType);
            }
        }
        assertThat(offenders)
            .as("这些端点用了原始响应对象：真状态码例外只在 /internal/mqtt/** 与文件下载，不得扩散")
            .isEmpty();
    }

    @Test
    @DisplayName("★ 控制器类上必须是 /devices/import 前缀（网关 StripPrefix=1，客户端看到的才是 /iot/devices/import）")
    void classLevelRequestMappingMustBeStable() {
        RequestMapping mapping = DeviceImportController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/devices/import");
        assertThat(DeviceImportController.class.getAnnotation(RestController.class)).isNotNull();
    }

    @Test
    @DisplayName("★ 权限码必须已在 007 与 migration 中注册（否则非超管角色永远 403）")
    void importPermissionMustBeRegisteredInSql() throws IOException {
        Pattern authCode = Pattern.compile("'(iot:device:import)'");
        Path fresh = REPO_ROOT.resolve("deploy/sql/007-iot-data.sql");
        assertThat(authCode.matcher(Files.readString(fresh, StandardCharsets.UTF_8)).find())
            .as("007-iot-data.sql 未注册 iot:device:import ⇒ 全新安装的库上该功能对非超管不可用")
            .isTrue();

        boolean foundInMigration = false;
        Path migrationDir = REPO_ROOT.resolve("deploy/sql/migration");
        try (var files = Files.list(migrationDir)) {
            for (Path path : files.filter(p -> p.getFileName().toString().contains("-iot-"))
                .sorted().toList()) {
                if (authCode.matcher(Files.readString(path, StandardCharsets.UTF_8)).find()) {
                    foundInMigration = true;
                }
            }
        }
        assertThat(foundInMigration)
            .as("migration/*-iot-*.sql 未注册 iot:device:import ⇒ 已上线库升级后同样不可用")
            .isTrue();
    }

    @Test
    @DisplayName("★ 菜单 id 320024 必须同时进 sys_role_menu 与 sys_template_menu（platform_only=0）")
    void menuMustBeGranted() throws IOException {
        String sql = Files.readString(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"),
            StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
        assertThat(sql).as("菜单 320024 未登记").contains("(320024, 3200, 'IotDeviceImport'");
        Pattern grant = Pattern.compile(
            "INSERT INTO (sys_(?:role|template)_menu)\\s*\\([^)]*\\)\\s*SELECT 1, id FROM sys_menu "
                + "WHERE is_deleted = 0 AND id IN \\(320024\\)");
        Matcher matcher = grant.matcher(sql);
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        assertThat(tables)
            .as("platform_only=0 的菜单必须同时进 sys_role_menu 与 sys_template_menu，"
                + "否则要么平台管理员看不到、要么租户侧保存角色会被 validateMenus 拦下")
            .containsExactlyInAnyOrder("sys_role_menu", "sys_template_menu");
    }

    @Test
    @DisplayName("★ 回滚脚本必须存在且覆盖两张表与菜单（生产回滚物不能缺）")
    void rollbackScriptMustCoverEverything() throws IOException {
        Path rollback = REPO_ROOT.resolve(
            "deploy/sql/rollback/2026-10-04-iot-device-import-rollback.sql");
        assertThat(rollback).as("回滚脚本缺失 ⇒ 生产上出问题只能手工救").exists();
        String sql = Files.readString(rollback, StandardCharsets.UTF_8);
        assertThat(sql).contains("DROP TABLE IF EXISTS iot_device_import_row")
            .contains("DROP TABLE IF EXISTS iot_device_import_batch")
            .contains("sys_menu").contains("320024");
    }

    /**
     * 断言端点方法的路由与权限码。
     *
     * @param method          方法名
     * @param parameterTypes  参数类型
     * @param permission      期望权限码
     * @param expectedPath 期望路径；{@code null} 表示不校验（用方法上的 RequestMapping 形态断言）
     * @throws Exception 反射查找失败
     */
    private static void assertRoute(String method, Class<?>[] parameterTypes, String permission,
                                    String expectedPath) throws Exception {
        Method target = DeviceImportController.class.getMethod(method, parameterTypes);
        SaCheckPermission annotation = target.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).as("%s 必须标注 @SaCheckPermission", method).isNotNull();
        assertThat(annotation.value()).as("%s 的权限码必须与菜单登记一致", method)
            .containsExactly(permission);
        if (expectedPath == null) {
            return;
        }
        GetMapping get = target.getAnnotation(GetMapping.class);
        PostMapping post = target.getAnnotation(PostMapping.class);
        String[] paths = get != null ? get.value() : post.value();
        // 集合根路径写成 @GetMapping 不带值（空数组）等价于空前缀
        String actual = paths.length == 0 ? "" : paths[0];
        assertThat(actual).as("%s 的路径必须与页面调用的地址一致", method)
            .isEqualTo(expectedPath.substring("/devices/import".length()));
    }
}
