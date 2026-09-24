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
 * <p>② 管理端点的**显式权限守卫**必须真的被调用：本仓微服务下游的 {@code @SaCheckPermission} 不生效
 * （见 ROADMAP 四点十六），维护窗口又直接影响可用率口径。</p>
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
    @DisplayName("★ 维护窗口管理端点的三个方法都必须调用显式权限守卫（下游注解鉴权不生效，见四点十六）")
    void controllerMustCallExplicitPermissionGuard() throws IOException {
        String source = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller"
                + "/IotMaintenanceWindowController.java"), StandardCharsets.UTF_8);
        // 门禁文本匹配必须作用在**剥离注释后**的代码上（教训二十三）
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        long guardCalls = code.lines().filter(line -> line.contains("permissionGuard.require(")).count();
        assertThat(guardCalls).as("三个端点（list/open/close）都要显式校验权限").isEqualTo(3);
        // 逐个方法校验：只数总数会让「复制粘贴错权限码」或「两个方法对调」照样通过（复核变异 N4 实证）
        assertGuard(code, "list", "PERM_LIST");
        assertGuard(code, "open", "PERM_CREATE");
        assertGuard(code, "close", "PERM_CLOSE");
        assertThat(code).as("常量必须指向真实权限码（与 007 登记一致）")
            .contains("PERM_LIST = \"iot:maintenance:list\"")
            .contains("PERM_CREATE = \"iot:maintenance:create\"")
            .contains("PERM_CLOSE = \"iot:maintenance:close\"");
    }

    /**
     * 断言某个端点方法内调用的是**它自己的**权限常量。
     *
     * @param code        Controller 源码（已剥注释）
     * @param method      方法名
     * @param permission  期望的权限常量名
     */
    private static void assertGuard(String code, String method, String permission) {
        int start = code.indexOf(" " + method + "(");
        assertThat(start).as("找不到方法 %s", method).isPositive();
        // 取方法签名后的一段（足够覆盖方法体；本 Controller 方法都很短）
        String body = code.substring(start, Math.min(code.length(), start + 700));
        int nextSignature = body.indexOf("\n    public ");
        if (nextSignature > 0) {
            body = body.substring(0, nextSignature);
        }
        assertThat(body).as("%s 必须调用 permissionGuard.require(%s)", method, permission)
            .contains("permissionGuard.require(" + permission + ")");
    }
}
