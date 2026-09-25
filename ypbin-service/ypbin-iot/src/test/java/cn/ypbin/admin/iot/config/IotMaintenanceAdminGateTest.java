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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 维护窗口管理面的**源码/SQL 级门禁**（补齐外委复核指出的盲区）。
 *
 * <p>① 本文件新增的 IoT 菜单（{@code 32xx}）与平台模块目录（{@code 33xx}）必须在授权表里被授权，
 * 否则菜单建出来但没人看得见；SQL 等价门禁只保证两份脚本一致、权限码门禁只看 {@code auth_code}，
 * **都发现不了**。期望按 {@code platform_only} 分流：一律要 {@code sys_role_menu}；
 * **仅当**菜单自身 {@code platform_only=0} 时才要 {@code sys_template_menu}
 * （它同时是租户管理员配角色时的白名单，见 {@code SysRoleServiceImpl#validateMenus}）。</p>
 *
 * <p><b>为什么取数必须按语句归属</b>：早前的实现用 {@code sql.split("INSERT INTO " + table)}，
 * 切出的 block 会跨语句串味（一直延伸到下一次出现同一张表为止），把其它表的 INSERT、乃至
 * {@code UPDATE ... WHERE id IN (...)} 都算成"已授权" ⇒ 两张表收集到的集合完全相同、
 * 模板侧检查形同虚设。外委复核用**真实 JUnit** 实证：删掉某条模板授权、甚至删掉整条菜单授权，
 * 门禁都**不转红**（独立发现的第二条假绿路径是 {@code 007} 末尾的
 * {@code UPDATE sys_menu SET pid = 3204 WHERE id IN (3200, ...)} 被当成了授权）。</p>
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

    /**
     * 菜单 INSERT 的值元组 {@code (id, pid, name, type, platform_only)}。
     *
     * <p>全局扫描（而不是逐行 {@code VALUES (} / 行首 {@code (}）顺带修掉两个缺口：
     * 同一行多个 VALUES 元组的第二个不会再漏扫；剥注释后注释掉的 INSERT 不会再被当成真实菜单。</p>
     */
    private static final Pattern MENU_INSERT =
        Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*([01])\\s*,");

    /**
     * 授权语句里的菜单 id 清单。
     *
     * <p><b>必须锚定词边界</b>：旧写法 {@code id IN \(([^)]*)\)} 会命中 {@code menu_id IN (...)}，
     * 把「防孤儿」SQL 里的**子菜单 id** 也算成父目录的授权。</p>
     */
    private static final Pattern GRANT_IN = Pattern.compile("(?<![A-Za-z0-9_])id\\s+IN\\s*\\(([^)]*)\\)");

    private static String sql() throws IOException {
        return Files.readString(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ IoT/平台模块菜单必须在授权表里被授权（platform_only 决定是否必须进租户模板）")
    void everyMenuIdMustBeGranted() throws IOException {
        // 门禁文本匹配必须作用在**剥离注释后**的脚本上（教训二十三）：否则注释掉的 INSERT 会被当成真实菜单
        String code = sql().replaceAll("--[^\\n]*", "");

        // 菜单自身属性：id → platform_only。门禁不能只看 id，还要按 platform_only 决定期望。
        Map<String, String> menuPlatformOnly = new LinkedHashMap<>();
        Matcher menuMatcher = MENU_INSERT.matcher(code);
        while (menuMatcher.find()) {
            menuPlatformOnly.put(menuMatcher.group(1), menuMatcher.group(5));
        }
        assertThat(menuPlatformOnly).as("没扫到任何菜单 id ⇒ 本门禁空跑（教训八：0 违规可能是没跑到）")
            .isNotEmpty();

        Set<String> roleGranted = grantedMenuIds(code, "sys_role_menu");
        Set<String> templateGranted = grantedMenuIds(code, "sys_template_menu");

        List<String> missingRole = new ArrayList<>();
        List<String> missingTemplate = new ArrayList<>();
        for (Map.Entry<String, String> menu : menuPlatformOnly.entrySet()) {
            String id = menu.getKey();
            // 本文件只负责本仓新增的 IoT 菜单（32xx）与平台模块目录（33xx）段；平台既有菜单由 002-data.sql 负责
            if (!id.startsWith("32") && !id.startsWith("33")) {
                continue;
            }
            if (!roleGranted.contains(id)) {
                missingRole.add(id);
            }
            // platform_only=0 ⇒ 必须同时进 sys_template_menu：
            // ① 它是租户管理员配角色时的白名单（SysRoleServiceImpl#validateMenus 用 containsAll 卡口）；
            // ② 父目录没进模板，非平台租户用户的菜单会整棵丢失。
            // platform_only=1 的平台专用菜单按既有约定不进模板（先例：007-iot-data.sql 的 M2 台账菜单）。
            if ("0".equals(menu.getValue()) && !templateGranted.contains(id)) {
                missingTemplate.add(id);
            }
        }
        assertThat(missingRole).as("sys_role_menu 缺少对以下菜单的授权（菜单会建出来但没人看得见）")
            .isEmpty();
        assertThat(missingTemplate)
            .as("sys_template_menu 缺少对以下 platform_only=0 菜单的授权（租户侧角色保存会被 validateMenus 拦下）")
            .isEmpty();
    }

    /**
     * 按**语句归属**收集某张授权表里的菜单 id。
     *
     * <p>先剥 {@code --} 行注释（调用方已剥），再按 {@code ;} 切语句，只在**该语句确实是**
     * {@code INSERT [IGNORE] INTO <本表>} 时，才取本语句内的 {@code id IN (...)} ⇒ 不跨语句、
     * 不会被其它表的 INSERT 或 {@code UPDATE ... id IN (...)} 污染。</p>
     *
     * @param code  已剥离行注释的安装脚本全文
     * @param table 授权表名（{@code sys_role_menu} / {@code sys_template_menu}）
     * @return 该表被显式授权的菜单 id 集合（查无授权返回空集合）
     */
    private static Set<String> grantedMenuIds(String code, String table) {
        Pattern insert = Pattern.compile("^INSERT\\s+(?:IGNORE\\s+)?INTO\\s+" + Pattern.quote(table) + "\\b",
            Pattern.CASE_INSENSITIVE);
        Set<String> granted = new LinkedHashSet<>();
        for (String statement : code.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || !insert.matcher(trimmed).find()) {
                continue;
            }
            Matcher matcher = GRANT_IN.matcher(trimmed);
            while (matcher.find()) {
                for (String item : matcher.group(1).split(",")) {
                    String id = item.trim();
                    if (!id.isEmpty()) {
                        granted.add(id);
                    }
                }
            }
        }
        return granted;
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
