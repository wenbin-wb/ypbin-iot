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
     */
    public static final long VIRTUAL_USER_ID_MAX = -1_000_000_000L;

    /** 平台超管权限码（starter `StpPermissionAdapter.SUPER_ADMIN` 同值）。 */
    public static final String SUPER_ADMIN_WILDCARD = "*:*:*";

    /** Sa-Token 官方全权限通配符（starter `StpPermissionAdapter.ANY` 同值）。 */
    public static final String ANY_WILDCARD = "*";

    private OpenApiPrincipal() {
    }

    /**
     * 判定是否为开放 API 的虚拟主体。
     *
     * @param userId 解析出的用户 ID（可为 `null`）
     * @return 虚拟主体返回 `true`
     */
    public static boolean isVirtualPrincipal(Long userId) {
        return userId != null && userId <= VIRTUAL_USER_ID_MAX;
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
     * 把 Key 的 scopes 转成权限码集合（**去空白、去重、剔除通配符**）。
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
            if (token.isEmpty() || isWildcard(token)) {
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
