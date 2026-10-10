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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * <p>①′ 光有①还不够：①只证明父目录被授给了**某些人**，不证明授给了**所有拥有其子菜单的角色/模板**。
 * 没被补授父目录的角色，其子菜单会被 {@code buildRouteTree} 整棵丢弃（K2），于是再有
 * {@link #orphanGuardMustBeGrantedForEveryModule()} 断言四条「防孤儿」补授语句存在且子清单正确。</p>
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

    /**
     * 「防孤儿（K2）」补授语句：
     * {@code INSERT IGNORE INTO <表> (<列>) SELECT DISTINCT x.y, <父 id> FROM <表> x WHERE x.menu_id IN (<子清单>) AND x.<id 列> <> 1;}
     *
     * <p>第 5 组捕获语句**尾部**（到 {@code ;} 为止）。尾部必须一并断言：只校验「语句形态 + 表名 + 父 id + 子清单」
     * 时，把尾部改成 {@code AND 1=0} 之类的空操作仍会全绿（复核变异 M7 实证）。</p>
     */
    private static final Pattern ORPHAN_GUARD = Pattern.compile(
        "INSERT\\s+IGNORE\\s+INTO\\s+(\\w+)\\s*\\([^)]*\\)\\s*"
            + "SELECT\\s+DISTINCT\\s+\\w+\\.(\\w+)\\s*,\\s*(\\d+)\\s+FROM\\s+\\w+\\s+\\w+\\s*"
            + "WHERE\\s+\\w+\\.menu_id\\s+IN\\s*\\(([^)]*)\\)([^;]*);",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 平台模块目录 → 其子菜单清单（与 {@code docs/PLATFORM-IA-PROPOSAL.md} §4.2 的归属表一致）。
     *
     * <p>防孤儿补授必须**逐一覆盖**这两组 × 两张授权表；否则拥有子菜单却没被补授父目录的角色/模板，
     * 其菜单会被 {@code buildRouteTree} 整棵丢弃（K2），而「每个菜单都被授权」那条断言**发现不了**
     * ——它只证明父目录被授给了**某些人**，不证明授给了**所有拥有子菜单的人**。</p>
     */
    private static final Map<String, String> MODULE_CHILDREN = Map.of(
        "3310", "3001,3002,3003,3005,3007,2600,3008,4001",
        "3320", "3004,3009");

    /** 防孤儿语句尾部必须是「排除模板/角色 1」，否则补授会漏掉或变成空操作。 */
    private static final Map<String, String> ORPHAN_GUARD_TAIL = Map.of(
        "sys_role_menu", "AND rm.role_id <> 1",
        "sys_template_menu", "AND tm.template_id <> 1");

    /** 一次性建库脚本。 */
    private static final Path INSTALL_SQL = REPO_ROOT.resolve("deploy/sql/007-iot-data.sql");

    /**
     * 授权语句的扫描面：安装脚本 + <b>全部增量迁移</b>。
     *
     * <p>此前两条门禁只读安装脚本 ⇒ 把「平台级菜单授进租户模板」写进 migration 时门禁一声不响，
     * 而 migration 里有十几条 {@code sys_template_menu} 授权语句，正是补授最可能落地的地方
     * （R8-7 的覆盖面缺口之一）。</p>
     */
    private static final List<Path> GRANT_SQL_SOURCES = grantSqlSources();

    /**
     * 菜单属性（{@code id → platform_only}）的来源：授权脚本之外，还要读平台基座数据
     * {@code 002-data.sql} —— 「有条件补授」的守卫子项（3004/3009 等）定义在那里，
     * 不读它就无法判定守卫是否可达（判据见 {@link #selectGuardReachable}）。</p>
     */
    private static final List<Path> MENU_SQL_SOURCES = menuSqlSources();

    /**
     * 平台级（跨租户生效）菜单的<b>显式清单</b>：必须 {@code platform_only=1}，且绝不得出现在任何
     * 可达的租户模板授权里。每一条都对应一个真实越权面——授给租户管理员，租户即可自助扩大采集范围。
     *
     * <p>为什么是清单而不是「按 id 前缀扫」：前缀规则看不见非 32xx/33xx 段的平台级菜单；
     * 新增平台级菜单时必须同时登记到本表（维护责任见 {@code docs/ACCESS-TECHDEBT-R8.md} §2.5）。</p>
     */
    private static final Map<String, String> PLATFORM_LEVEL_MENUS = Map.of(
        "3206", "IotTenantLedger（租户接入台账：决定「哪些租户可被采集」）",
        "320014", "IotLedgerList（权限码 iot:ledger:list）",
        "320015", "IotLedgerUpdate（权限码 iot:ledger:update）",
        "3320", "PlatformOps（平台运维与监控目录）");

    /**
     * 「目标菜单无法静态判定」的授权语句豁免表（{@code 源文件名:语句摘要 → 理由}）。
     *
     * <p>解析器的能力边界必须显式（{@code docs/ACCESS-TECHDEBT-R8.md} §3）：形如
     * {@code INSERT INTO sys_template_menu (...) SELECT tm.template_id, tm.menu_id ...} 的语句，
     * 目标菜单来自被查询的行而非常量，静态判不了 ⇒ 门禁必须**报出来**要求人工裁定，
     * 而不是静默放过。确需保留时在此登记；豁免项在脚本里已不存在时，门禁会转红（防陈旧豁免）。</p>
     */
    private static final Map<String, String> UNRESOLVED_GRANT_EXEMPTIONS = Map.of();

    private static List<Path> grantSqlSources() {
        List<Path> sources = new ArrayList<>();
        sources.add(INSTALL_SQL);
        Path migrationDir = REPO_ROOT.resolve("deploy/sql/migration");
        try (var stream = Files.list(migrationDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".sql")).sorted().forEach(sources::add);
        } catch (IOException ex) {
            // 读不到迁移目录必须炸掉：否则本门禁静默退化成「只看安装脚本」（正是要修的那条缺口）
            throw new IllegalStateException("读不到迁移脚本目录：" + migrationDir, ex);
        }
        return List.copyOf(sources);
    }

    private static List<Path> menuSqlSources() {
        List<Path> sources = new ArrayList<>();
        sources.add(REPO_ROOT.resolve("deploy/sql/002-data.sql"));
        sources.addAll(GRANT_SQL_SOURCES);
        return List.copyOf(sources);
    }

    private static String sql() throws IOException {
        return code(INSTALL_SQL);
    }

    /** 读脚本并剥离 {@code --} 行注释（门禁文本匹配必须作用在剥注释后的脚本上，教训二十三）。 */
    private static String code(Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
    }

    /** 解析某脚本的菜单属性：{@code id → platform_only}。 */
    private static Map<String, String> menuPlatformOnly(String code) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = MENU_INSERT.matcher(code);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(5));
        }
        return result;
    }

    /** 全脚本合并后的菜单属性（同一 id 多源定义时以扫描顺序后者为准；不一致由专项用例断言）。 */
    private static Map<String, String> allMenusPlatformOnly() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path source : MENU_SQL_SOURCES) {
            result.putAll(menuPlatformOnly(code(source)));
        }
        return result;
    }

    /** 一条「有条件补授」：{@code INSERT IGNORE ... SELECT DISTINCT x.?, <常量目标> ... WHERE x.menu_id IN (子清单)}。 */
    private record SelectGuard(String table, String target, Set<String> children) {
    }

    /** 收集某脚本里全部「有条件补授」语句。 */
    private static List<SelectGuard> selectGuards(String code) {
        List<SelectGuard> guards = new ArrayList<>();
        Matcher matcher = ORPHAN_GUARD.matcher(code);
        while (matcher.find()) {
            guards.add(new SelectGuard(matcher.group(1), matcher.group(3), idSet(matcher.group(4))));
        }
        return guards;
    }

    /**
     * 「有条件补授」是否<b>可达</b>（真的会把目标菜单授进租户模板）。
     *
     * <p>判据：子清单里只要存在一个「非平台级」子项（{@code platform_only != 1} 或未登记），
     * 就说明已有租户模板持有该子项 ⇒ 语句的 {@code WHERE} 能命中 ⇒ 目标菜单会被补授给这些模板。
     * 反之（子项全部平台级）该语句恒命中 0 行 ⇒ 只是<b>潜在</b>授权，今天不构成违规。</p>
     *
     * <p>这条判据是 R8-7 那条 latent gap 的可判定形式：子项一旦被改成 {@code platform_only=0}，
     * 目标平台级菜单就会真的进租户模板，而旧门禁（只认 {@code id IN}）看不见这条语句。</p>
     */
    private static boolean selectGuardReachable(SelectGuard guard, Map<String, String> menuPlatformOnly) {
        for (String child : guard.children()) {
            if (!"1".equals(menuPlatformOnly.get(child))) {
                return true;
            }
        }
        return false;
    }

    /** 某脚本里<b>可达</b>的租户模板授权菜单 id：形态 A（{@code id IN}）+ 可达的形态 B（SELECT 常量）。 */
    private static Set<String> reachableTemplateGrantIds(String code, Map<String, String> menuPlatformOnly) {
        Set<String> reachable = new LinkedHashSet<>(grantedMenuIds(code, "sys_template_menu"));
        for (SelectGuard guard : selectGuards(code)) {
            if ("sys_template_menu".equals(guard.table()) && selectGuardReachable(guard, menuPlatformOnly)) {
                reachable.add(guard.target());
            }
        }
        return reachable;
    }

    /**
     * 目标菜单无法静态判定的授权语句（{@code 源文件名: 归一化后的完整语句}）。
     *
     * <p>键用<b>完整语句</b>而不是前缀：同一脚本里两条只有子清单不同的补授语句前缀完全相同，
     * 用前缀会让豁免与识别互相串味。注意 {@link #ORPHAN_GUARD} 以 {@code ;} 收尾，
     * 而这里按 {@code ;} 切分后语句已无分号，故匹配时补回。</p>
     */
    private static List<String> unresolvedGrantStatements(Path source, String code) {
        Pattern insert = Pattern.compile(
            "^INSERT\\s+(?:IGNORE\\s+)?INTO\\s+(?:sys_role_menu|sys_template_menu)\\b", Pattern.CASE_INSENSITIVE);
        List<String> unresolved = new ArrayList<>();
        for (String statement : code.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || !insert.matcher(trimmed).find()) {
                continue;
            }
            if (ORPHAN_GUARD.matcher(trimmed + ";").find() || GRANT_IN.matcher(trimmed).find()) {
                continue;
            }
            unresolved.add(source.getFileName() + ": " + trimmed.replaceAll("\\s+", " "));
        }
        return unresolved;
    }

    /** 归一化 id 清单为集合。 */
    private static Set<String> idSet(String ids) {
        return Arrays.stream(ids.split(",")).map(String::trim).filter(item -> !item.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
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

    @Test
    @DisplayName("★ K2 防孤儿补授必须存在：模块目录要补授给「已拥有其任一子菜单」的角色与模板")
    void orphanGuardMustBeGrantedForEveryModule() throws IOException {
        String code = sql().replaceAll("--[^\\n]*", "");
        Map<String, String> found = new LinkedHashMap<>();
        Matcher matcher = ORPHAN_GUARD.matcher(code);
        while (matcher.find()) {
            String table = matcher.group(1);
            String children = normalizeIdList(matcher.group(4));
            String tail = matcher.group(5) == null ? "" : matcher.group(5).trim().replaceAll("\\s+", " ");
            found.put(table + ":" + matcher.group(3), children + " | 尾部: " + tail);
        }
        // 自检：一条都没扫到就说明正则与脚本形态脱节，本门禁退化成"0 违规 = 没跑到"（教训八）
        assertThat(found).as("没扫到任何防孤儿补授语句 ⇒ 本门禁空跑").isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> module : MODULE_CHILDREN.entrySet()) {
            for (String table : List.of("sys_role_menu", "sys_template_menu")) {
                String key = table + ":" + module.getKey();
                String expected = module.getValue() + " | 尾部: " + ORPHAN_GUARD_TAIL.get(table);
                String actual = found.get(key);
                if (!expected.equals(actual)) {
                    missing.add(key + "（期望 " + expected + "，实际 " + actual + "）");
                }
            }
        }
        assertThat(missing).as("模块目录缺少防孤儿补授（或尾部条件被改坏）⇒ 拥有其子菜单的角色/模板会整棵丢菜单（K2）")
            .isEmpty();
    }

    /**
     * 归一化 id 清单：去空白、去空项、保持原顺序。
     *
     * @param ids 逗号分隔的 id 清单
     * @return 归一化后的清单
     */
    private static String normalizeIdList(String ids) {
        return Arrays.stream(ids.split(",")).map(String::trim).filter(item -> !item.isEmpty())
            .collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("★ 反向门禁：platform_only=1 的平台级菜单**绝不得**进 sys_template_menu（全脚本，含 migration）")
    void platformOnlyMenusMustNeverEnterTenantTemplate() throws IOException {
        // 为什么必须有反向检查：正向那条只证明「platform_only=0 的都进模板了」，
        // 证明不了「platform_only=1 的没进模板」。而 sys_template_menu 是租户侧配角色的白名单
        // （SysRoleServiceImpl#validateMenus）——一旦平台级菜单进了模板，租户管理员就能把
        // iot:ledger:list 授给租户用户，而该权限码对应的是「改别的租户是否被采集」⇒ 跨租户越权。
        Map<String, String> menuPlatformOnly = allMenusPlatformOnly();
        assertThat(menuPlatformOnly).as("没扫到任何菜单 id ⇒ 本门禁空跑").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        int grantedIds = 0;
        for (Path source : GRANT_SQL_SOURCES) {
            // 「可达的模板授权」= 形态 A（id IN 显式）∪ 可达的形态 B（SELECT 常量且守卫子项里含非平台级项）
            Set<String> templateGranted = reachableTemplateGrantIds(code(source), menuPlatformOnly);
            if (templateGranted.isEmpty()) {
                continue;   // schema-only 迁移：本脚本不涉及模板授权
            }
            grantedIds += templateGranted.size();
            for (String id : templateGranted) {
                if ((id.startsWith("32") || id.startsWith("33")) && "1".equals(menuPlatformOnly.get(id))) {
                    offenders.add(source.getFileName() + " → " + id);
                }
            }
        }
        // 自检：必须真的扫到过模板授权，否则下面的断言恒真（教训二十七）
        assertThat(grantedIds).as("所有脚本加起来一条模板授权都没扫到 ⇒ 本门禁无法咬人").isGreaterThan(0);

        assertThat(offenders)
            .as("这些 platform_only=1 的平台级菜单被（可达的）租户模板授权命中 ⇒ 租户管理员可把平台级权限授给租户用户：%s",
                offenders)
            .isEmpty();
    }

    @Test
    @DisplayName("★ 跨脚本门禁：平台级菜单显式清单 + SELECT 形态补授的可达性（补齐 R8-7 覆盖面）")
    void platformLevelMenusMustStayPlatformOnlyAndOutOfTemplates() throws IOException {
        // 本用例补的是三处覆盖面缺口（见 docs/ACCESS-TECHDEBT-R8.md §2.5）：
        //   ① 只扫安装脚本 ⇒ 这里扫「安装脚本 + 全部 migration」；
        //   ② 只认 32xx/33xx 前缀 ⇒ 这里用**显式清单**，覆盖非该前缀段的平台级菜单；
        //   ③ 只认 `INSERT ... id IN (...)` 形态 ⇒ 这里把「SELECT + 常量目标」的补授也纳入，
        //      并按**可达性**判定（子项全部平台级 ⇒ 恒命中 0 行 ⇒ 只是潜在授权；否则真的会授进模板）。
        assertThat(GRANT_SQL_SOURCES).as("只扫到 %s 个脚本 ⇒ 迁移脚本未被读入，本门禁空跑", GRANT_SQL_SOURCES.size())
            .hasSizeGreaterThan(1);
        assertThat(PLATFORM_LEVEL_MENUS).as("平台级菜单清单为空 ⇒ 本门禁恒真").isNotEmpty();
        assertThat(MENU_SQL_SOURCES.size()).as("菜单属性来源少于 2 个脚本 ⇒ 守卫子项的 platform_only 解析不了")
            .isGreaterThan(1);

        // ① 同一 id 在不同脚本里的 platform_only 必须一致：不一致本身就是缺陷（后写的脚本会覆盖前者的语义）
        Map<String, Set<String>> valuesById = new LinkedHashMap<>();
        for (Path source : MENU_SQL_SOURCES) {
            menuPlatformOnly(code(source)).forEach((id, platformOnly) ->
                valuesById.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(platformOnly));
        }
        List<String> inconsistent = valuesById.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> entry.getKey() + " → " + entry.getValue())
            .collect(Collectors.toList());
        assertThat(inconsistent).as("同一菜单 id 在不同脚本里的 platform_only 取值不一致").isEmpty();

        // ② 清单必须「真的存在且仍是 platform_only=1」：清单陈旧、或有人把平台级菜单改成 0，都要红
        List<String> missing = new ArrayList<>();
        List<String> downgraded = new ArrayList<>();
        for (Map.Entry<String, String> platformMenu : PLATFORM_LEVEL_MENUS.entrySet()) {
            Set<String> values = valuesById.get(platformMenu.getKey());
            if (values == null || values.isEmpty()) {
                missing.add(platformMenu.getKey() + "（" + platformMenu.getValue() + "）");
            } else if (!"1".equals(values.iterator().next())) {
                downgraded.add(platformMenu.getKey() + " → platform_only=" + values.iterator().next());
            }
        }
        assertThat(missing).as("清单里的平台级菜单在任何脚本里都找不到定义 ⇒ 清单已陈旧，请同步维护").isEmpty();
        assertThat(downgraded).as("平台级菜单的 platform_only 不是 1 ⇒ 它会进入租户模板白名单（跨租户越权面）").isEmpty();

        // ③ 平台级菜单不得被任何脚本的**可达**授权命中（形态 A 显式 + 形态 B 可达）
        Map<String, String> menuPlatformOnly = allMenusPlatformOnly();
        List<String> offenders = new ArrayList<>();
        int guards = 0;
        for (Path source : GRANT_SQL_SOURCES) {
            String code = code(source);
            for (SelectGuard guard : selectGuards(code)) {
                if (!"sys_template_menu".equals(guard.table())) {
                    continue;
                }
                guards++;
                if (selectGuardReachable(guard, menuPlatformOnly) && PLATFORM_LEVEL_MENUS.containsKey(guard.target())) {
                    offenders.add(source.getFileName() + " → SELECT 形态补授目标 " + guard.target()
                        + "（子项 " + guard.children() + " 中已有非平台级项 ⇒ 守卫可达）");
                }
            }
            for (String id : grantedMenuIds(code, "sys_template_menu")) {
                if (PLATFORM_LEVEL_MENUS.containsKey(id)) {
                    offenders.add(source.getFileName() + " → id IN 形态显式授权 " + id);
                }
            }
        }
        // 自检：一条「有条件补授」都没扫到，说明解析器与脚本形态脱节（教训八）
        assertThat(guards).as("一条 SELECT 形态补授都没扫到 ⇒ 解析器与脚本形态脱节，本门禁在这一点上咬不到").isGreaterThan(0);
        assertThat(offenders).as("平台级菜单被可达的租户模板授权命中 ⇒ 租户管理员可自助扩大跨租户面：%s", offenders)
            .isEmpty();

        // ④ 解析器看不见的授权语句必须显式报出（能力边界显式化），确需保留则登记豁免
        List<String> unresolved = new ArrayList<>();
        for (Path source : GRANT_SQL_SOURCES) {
            unresolved.addAll(unresolvedGrantStatements(source, code(source)));
        }
        List<String> unregistered = unresolved.stream()
            .filter(item -> !UNRESOLVED_GRANT_EXEMPTIONS.containsKey(item))
            .collect(Collectors.toList());
        assertThat(unregistered).as(
            "这些授权语句的目标菜单无法静态判定（解析器看不见）⇒ 要么改写成可判定形态，要么按原样登记到 UNRESOLVED_GRANT_EXEMPTIONS：%s",
            unregistered).isEmpty();

        // ⑤ 豁免表不得陈旧：登记了但脚本里已不存在同样要红（否则豁免会永久掩盖新问题）
        List<String> stale = UNRESOLVED_GRANT_EXEMPTIONS.keySet().stream()
            .filter(key -> !unresolved.contains(key))
            .collect(Collectors.toList());
        assertThat(stale).as("这些豁免项在脚本里已不存在 ⇒ 请从 UNRESOLVED_GRANT_EXEMPTIONS 移除").isEmpty();
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
