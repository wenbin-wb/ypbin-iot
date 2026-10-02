/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import cn.ypbin.starter.security.identity.VirtualPrincipalScopes;
import cn.ypbin.starter.security.satoken.StpPermissionAdapter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 开放 API 的**虚拟主体**约定（看板 #11，方案 B2）——**纯逻辑，零 IO**。
 *
 * <p><b>背景（F-1 实测结论）</b>：既有权限解析（`IotPermissionProvider`）只认"库里的真实用户"
 * （`SysCache.getUserPermissions(userId)`），**任何 scope 注入位都不被读取** ⇒ 虚拟主体权限恒空。
 * 生产实测（`docs/OPENAPI-PREFLIGHT.md` §9.3）已确认：虚拟主体带 `X-Roles` / `X-Api-Scopes`
 * 调受保护端点**一律 403**；且真实用户带**乱码** `X-Roles` 仍 200 ⇒ 证明 `X-Roles` 当时完全不被读。</p>
 *
 * <p><b>B2 做法</b>：网关为 API Key 请求注入一个**保留用户 ID 段**的虚拟主体，并把该 Key 的
 * scopes 放进**既有** `X-Roles` 头（`IdentityHeaderFilter` 已把它解析进
 * `LoginUser.roles`，见 starter 源码）；iot 侧在权限解析时**按用户 ID 段识别虚拟主体**，
 * 并**只对虚拟主体**改用该头里的 scopes 作为权限码。真实用户**行为完全不变**（仍走库）。</p>
 *
 * <p><b>为什么复用 `X-Roles` 而不是新增 `X-Api-Scopes`</b>：① 新头必须**同时**并入网关的
 * `header-sanitize` 表，漏了就等于给第三方开门（SF-5 同类教训）；② 既有头已贯通全链路，
 * 改动面更小。代价是"roles"这个字段在开放 API 场景下承载的是 scopes —— 由本文的
 * **按 ID 段分流**把两者严格分开，不靠字段名区分。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class OpenApiPrincipal {

    /**
     * 虚拟用户 ID 的**上界**：`userId <= 本值` 即视为虚拟主体。
     *
     * <p><b>为什么是负数高位段</b>：真实用户 ID 是雪花 ID（**正数**、约 19 位）；
     * 用负数高位段可确保**永不与真实用户空间重叠**（设计 §3.3 的安全红线：
     * "虚拟主体的 `X-User-Id` 必须取保留 ID 段……否则可能出现 Key 意外持有某个真实用户的权限"）。</p>
     *
     * <p><b>为什么不取 -3/-4/-5</b>：那是 Sa-Token 的哨兵值（`IdentityStpLogic` 类注释
     * "账号标识"条已提示冲突）⇒ 本段**必须避开**它们。取 `-1_000_000_000` 使
     * `-1/-3/-4/-5` 等小负数**仍按"非虚拟"处理**，保持既有语义不变。</p>
     *
     * <p><b>直接引用 starter 常量，不复制字面量</b>：本值曾与
     * {@link VirtualPrincipalScopes#DEFAULT_VIRTUAL_USER_ID_MAX}、
     * {@code OpenApiKeyConstants.VIRTUAL_USER_ID_BASE} 三处各写一遍字面量，
     * 漂移即"网关认为是虚拟主体、服务端认为是真实用户"的身份误判。
     * 现以 starter 为准，另有 `OpenApiPrincipalVirtualIdConsistencyTest` 把三处钉在一起。</p>
     */
    public static final long VIRTUAL_USER_ID_MAX = VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX;

    /**
     * 平台超管权限码 —— **直接引用** starter 常量（StpPermissionAdapter.SUPER_ADMIN）。
     *
     * <p>刻意**不复制字面量**：复制会与 starter 漂移，而漂移的后果是**通配符过滤漏掉新形态**
     * （本类存在的全部理由就是防这类"静默放行"）。</p>
     */
    public static final String SUPER_ADMIN_WILDCARD = StpPermissionAdapter.SUPER_ADMIN;

    /** Sa-Token 官方全权限通配符 —— 同样**直接引用** starter 常量（StpPermissionAdapter.ANY）。 */
    public static final String ANY_WILDCARD = StpPermissionAdapter.ANY;

    /**
     * 虚拟主体**允许**持有的 scopes（= 设计 §2.3 的既定开放作用域，全部是既有 iot: 权限码）。
     *
     * <p>🔴 <b>为什么是"白名单"而不是"只过滤通配符"</b>（本类最重要的一处决定）：
     * Sa-Token 把账号的权限码当 <b>pattern</b> 走 `SaFoxUtil.vagueMatch` 做模糊匹配
     * （starter `StpPermissionAdapter` 类注释明确写了这一点）⇒ 形如 `iot:*`、`iot:device:*`
     * 的**段内星号同样能命中**真实权限码。只过滤 `*`/`*:*:*` 会**漏掉这一类**，
     * 于是一把 Key 只要带上 `iot:*` 就能调用**包括命令下发在内**的全部 iot 能力 ——
     * 正是设计 §2.3 要防的"作用域隔离形同虚设"。</p>
     *
     * <p>白名单把可授予范围**收敛到既定清单**：清单外的任何值（含各种 pattern 形态）一律丢弃并记日志。
     * 代价是"新增开放作用域必须改这里"—— 这正是安全白名单应有的性质：让扩权成为一次**显式、可评审**的改动。</p>
     */
    public static final Set<String> ALLOWED_SCOPES = Set.of(
        "iot:device:list",
        "iot:device:latest",
        "iot:series:get",
        "iot:alert:list",
        "iot:product:list",
        "iot:availability:get",
        // 命令下发：设计 §2.3 列为**高危、默认不授予**；白名单允许"显式勾选"时生效
        "iot:debug:send");

    private OpenApiPrincipal() {
    }

    /**
     * 判定是否为开放 API 的虚拟主体。
     *
     * <p>直接委托 starter 通用判定（语义相同：非空且 `<=` 上界），上界本身也是 starter 常量。</p>
     *
     * @param userId 解析出的用户 ID（可为 `null`）
     * @return 虚拟主体返回 `true`
     */
    public static boolean isVirtualPrincipal(Long userId) {
        return VirtualPrincipalScopes.isVirtualPrincipal(userId);
    }

    /**
     * 判定单个 scope/权限码是否为**通配符**。
     *
     * <p>🔴 <b>必须拦掉通配符</b>：starter 的 `StpPermissionAdapter#withSuperAdminWildcard`
     * 在权限集合**含 `*:*:*`** 时会**追加 `*`** ⇒ 命中任意权限码（全权限）。
     * 若一把只该"看数据"的 Key 的作用域里出现通配符，它就会**越权为超管**——
     * 这正是设计 §2.3 警告的"作用域隔离形同虚设"。故此处按 **fail-closed** 过滤，
     * 并把该情况**记日志**（不静默）。</p>
     *
     * <p>⚠️ <b>刻意不委托 starter 的 `VirtualPrincipalScopes.containsWildcard`</b>：
     * 那个判定是"含 `*` 即算"（宽口径，用于底层统一剥离一切 pattern）；
     * 而本方法是"只认 `*` 与 `*:*:*` 两种精确形态"（窄口径，**仅用于日志分类**，
     * 见 `wildcardsIn` 与 `OpenApiPrincipalTest.isWildcardIsOnlyForLoggingNotSecurity`）。
     * 真正的安全判据是**白名单**（`isAllowedScope` 精确匹配），不是本方法 ——
     * `iot:*` 这类段内星号在本方法返回 false，但白名单同样拒绝它。
     * 若把本方法改成宽口径，会改变日志语义并破坏既有测试，故保持窄口径。</p>
     *
     * @param code 待判的码
     * @return 是通配符返回 `true`
     */
    public static boolean isWildcard(String code) {
        if (code == null) {
            return false;
        }
        String token = code.trim();
        return ANY_WILDCARD.equals(token) || SUPER_ADMIN_WILDCARD.equals(token);
    }

    /**
     * 判定是不是**允许授予**的开放作用域（白名单精确匹配，无 pattern 语义）。
     *
     * @param code 待判的码
     * @return 在白名单内返回 \`true\`
     */
    public static boolean isAllowedScope(String code) {
        return code != null && ALLOWED_SCOPES.contains(code.trim());
    }

    /**
     * 挑出**会被丢弃**的 scopes（白名单外的全部，含通配符与各类 pattern）。
     *
     * <p>用途：调用方据此**记日志**。静默丢弃是危险的——"Key 配了 scope 却不生效"
     * 若不留痕，排查时只能靠猜（这正是 F-1 实测里"403 但不知道为什么"的处境）。</p>
     *
     * @param scopes 原始 scopes
     * @return 被丢弃的项（保持首次出现顺序）
     */
    public static List<String> droppedScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            String token = scope.trim();
            if (!token.isEmpty() && !isAllowedScope(token)) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 把 Key 的 scopes 转成权限码集合（**去空白、去重、白名单过滤**）。
     *
     * @param scopes 原始 scopes（可空）
     * @return 权限码列表（**绝不返回 `null`**；无有效项时返回空列表 ⇒ 调用方据此拒绝）
     */
    public static List<String> scopesToPermissions(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            String token = scope.trim();
            // 白名单：清单外一律丢弃（含 * / *:*:* / iot:* 等一切 pattern 形态）
            if (!isAllowedScope(token)) {
                continue;
            }
            out.add(token);
        }
        return List.copyOf(out);
    }

    /**
     * 从 scopes 中挑出**被剔除的通配符**（供调用方记日志，让"为什么没生效"可查）。
     *
     * @param scopes 原始 scopes
     * @return 被剔除的通配符（保持首次出现顺序）
     */
    public static List<String> wildcardsIn(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null) {
                continue;
            }
            String token = scope.trim();
            if (isWildcard(token)) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }
}
