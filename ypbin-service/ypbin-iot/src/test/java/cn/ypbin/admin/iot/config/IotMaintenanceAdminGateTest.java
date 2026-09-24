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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import cn.ypbin.admin.iot.controller.IotMaintenanceWindowController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 维护窗口管理面的**源码/SQL 级门禁**（补齐外委复核指出的两个盲区）。
 *
 * <p>① 每个 IoT 菜单 id 必须在 {@code sys_role_menu} 与 {@code sys_template_menu} 里都被授权——
 * 否则菜单建出来但没人看得见；SQL 等价门禁只保证两份脚本一致、权限码门禁只看 {@code auth_code}，
 * **都发现不了**（复核用变异实证：两份 SQL 同时删掉授权后仍全绿）。</p>
 *
 * <p>② 管理端点的权限码必须**挂在方法上且逐一对号**：starter 3.5.0 起微服务下游的
 * {@code @SaCheckPermission} 真正生效（此前与登录拦截共用开关而静默失效，见 ROADMAP 四点十六），
 * 因此本门禁从「必须显式调用临时防线 {@code IotPermissionGuard}」迁移为「三个方法各自的注解权限码正确，
 * 且不得再回退到临时防线」——保护方式换了，门禁必须一起换，否则删除临时防线会被自己的门禁拦下。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotMaintenanceAdminGateTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    /** 菜单 INSERT 的 id（`VALUES (3203, ...)` 或续行 `(320301, 3203, ...)`）。 */
    private static final Pattern MENU_ID = Pattern.compile("VALUES\\s*\\(\\s*(\\d+)\\s*,");

    private static final Pattern MENU_ID_CONT = Pattern.compile("^\\(\\s*(\\d+)\\s*,");

    private static final Pattern GRANT_IN = Pattern.compile("id IN \\(([^)]*)\\)");

    private static String sql() throws IOException {
        return Files.readString(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ 每个 IoT 菜单 id 都必须在 sys_role_menu 与 sys_template_menu 里被授权（复核变异实证的盲区）")
    void everyMenuIdMustBeGranted() throws IOException {
        String sql = sql();
        Set<String> menuIds = new LinkedHashSet<>();
        for (String line : sql.split("\\R")) {
            Matcher inline = MENU_ID.matcher(line);
            while (inline.find()) {
                menuIds.add(inline.group(1));
            }
            Matcher cont = MENU_ID_CONT.matcher(line.trim());
            if (cont.find()) {
                menuIds.add(cont.group(1));
            }
        }
        assertThat(menuIds).as("没扫到任何菜单 id ⇒ 本门禁空跑（教训八：0 违规可能是没跑到）")
            .isNotEmpty();

        for (String table : List.of("sys_role_menu", "sys_template_menu")) {
            Set<String> granted = new LinkedHashSet<>();
            String[] blocks = sql.split("INSERT INTO " + table);
            for (int i = 1; i < blocks.length; i++) {
                Matcher matcher = GRANT_IN.matcher(blocks[i]);
                while (matcher.find()) {
                    for (String item : matcher.group(1).split(",")) {
                        granted.add(item.trim());
                    }
                }
            }
            List<String> missing = new ArrayList<>();
            for (String id : menuIds) {
                // 只有本文件新增的 IoT 菜单（32xx）需要在这里被授权；平台既有菜单由 002-data.sql 负责
                if (id.startsWith("32") && !granted.contains(id)) {
                    missing.add(id);
                }
            }
            assertThat(missing).as("%s 缺少对以下 IoT 菜单的授权（菜单会建出来但没人看得见）", table)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 维护窗口三个端点各自的 @SaCheckPermission 权限码必须正确，且不得回退到临时防线")
    void controllerMustDeclareCorrectPermissions() throws Exception {
        // 逐个方法校验：只数总数会让「复制粘贴错权限码」或「两个方法对调」照样通过（复核变异 N4 实证）
        assertPermission("list", new Class<?>[] {Long.class, LocalDateTime.class, LocalDateTime.class},
            "iot:maintenance:list");
        assertPermission("open", new Class<?>[] {MaintenanceWindowReq.class}, "iot:maintenance:create");
        assertPermission("close", new Class<?>[] {Long.class}, "iot:maintenance:close");

        // 反向断言：starter 3.5.0 起注解鉴权真正生效，临时防线不得复活
        // （复活会造成「双份校验」并让「注解哪天又失效」重新变成不可见）
        String source = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller"
                + "/IotMaintenanceWindowController.java"), StandardCharsets.UTF_8);
        // 门禁文本匹配必须作用在**剥离注释后**的代码上（教训二十三：Javadoc 里提到 guard 不算违规）
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertThat(code)
            .as("不得再引入临时防线 IotPermissionGuard（SF-1 关闭约定）")
            .doesNotContain("IotPermissionGuard")
            .doesNotContain("permissionGuard");
    }

    /**
     * 断言某个端点方法上的注解权限码与预期**逐一对应**。
     *
     * @param method         方法名
     * @param parameterTypes 方法参数类型
     * @param permission     期望的权限码
     * @throws Exception 反射查找方法失败
     */
    private static void assertPermission(String method, Class<?>[] parameterTypes, String permission)
        throws Exception {
        Method target = IotMaintenanceWindowController.class.getMethod(method, parameterTypes);
        SaCheckPermission annotation = target.getAnnotation(SaCheckPermission.class);
        assertThat(annotation).as("%s 必须标注 @SaCheckPermission", method).isNotNull();
        assertThat(annotation.value()).as("%s 的权限码必须与菜单登记一致", method)
            .containsExactly(permission);
    }
}
