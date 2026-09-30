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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 虚拟主体约定用例（看板 #11 / 方案 B2）。
 *
 * <p>重点锁死三条**安全判据**（做错了会直接导致越权，且不会报错）：</p>
 * <ol>
 *   <li><b>虚拟段不与真实用户重叠</b>：真实用户是正数雪花 ID；Sa-Token 哨兵值 `-3/-4/-5`
 *       必须**不算**虚拟主体（否则会改变它们的既有语义）；</li>
 *   <li><b>通配符必须被剔除</b>：starter 在权限集合含 `*:*:*` 时会**追加 `*`（全权限）**
 *       ⇒ 一把"只该看数据"的 Key 会越权成超管；</li>
 *   <li><b>取不到就拒绝</b>：空 scopes ⇒ 空权限列表（调用方据此 403），**绝不返回通配符兜底**。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class OpenApiPrincipalTest {

    @Test
    @DisplayName("🔴 真实用户 ID（正数雪花）绝不能被判为虚拟主体")
    void realUserIdsMustNeverBeVirtual() {
        // 生产实测见到的真实用户 ID 形态
        for (long id : new long[] {1L, 2L, 991_736_107L, 2_104_373_415_356_145_665L}) {
            assertThat(OpenApiPrincipal.isVirtualPrincipal(id))
                .as("真实用户 %s 被判为虚拟主体 ⇒ 会被改走 scopes 路径，等于绕过库权限", id)
                .isFalse();
        }
    }

    @Test
    @DisplayName("🔴 Sa-Token 哨兵值 -3/-4/-5 不算虚拟主体（保持既有语义）")
    void sentinelIdsMustNotBeVirtual() {
        for (long sentinel : new long[] {-1L, -2L, -3L, -4L, -5L, -100L}) {
            assertThat(OpenApiPrincipal.isVirtualPrincipal(sentinel))
                .as("哨兵值 %s 被判为虚拟主体会改变其既有语义", sentinel)
                .isFalse();
        }
    }

    @Test
    @DisplayName("保留段（<= -1e9）与边界值判为虚拟主体")
    void virtualSegmentMustBeRecognized() {
        assertThat(OpenApiPrincipal.isVirtualPrincipal(OpenApiPrincipal.VIRTUAL_USER_ID_MAX)).isTrue();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-1_000_000_001L)).isTrue();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-9_000_000_000_000L)).isTrue();
        // 恰好大于上界 1 ⇒ 不是虚拟
        assertThat(OpenApiPrincipal.isVirtualPrincipal(OpenApiPrincipal.VIRTUAL_USER_ID_MAX + 1))
            .isFalse();
    }

    @Test
    @DisplayName("null 不是虚拟主体（不炸）")
    void nullMustNotBeVirtual() {
        assertThat(OpenApiPrincipal.isVirtualPrincipal(null)).isFalse();
    }

    @Test
    @DisplayName("🔴 通配符 * 与 *:*:* 必须被剔除（否则 starter 会升级为全权限）")
    void wildcardsMustBeStripped() {
        Set<String> scopes = new LinkedHashSet<>(
            List.of("iot:device:list", "*", "*:*:*", "iot:series:get"));

        List<String> permissions = OpenApiPrincipal.scopesToPermissions(scopes);

        assertThat(permissions).containsExactly("iot:device:list", "iot:series:get");
        assertThat(permissions).doesNotContain("*", "*:*:*");
    }

    @Test
    @DisplayName("通配符单独出现 ⇒ 结果为空（fail-closed，不兜底放行）")
    void onlyWildcardMustYieldEmpty() {
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("*:*:*"))))
            .isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("*"))))
            .isEmpty();
    }

    @Test
    @DisplayName("isWildcard：只认两种通配符形态（普通权限码不算）")
    void wildcardDetection() {
        assertThat(OpenApiPrincipal.isWildcard("*")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard("*:*:*")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard(" *:*:* ")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard("iot:device:list")).isFalse();
        assertThat(OpenApiPrincipal.isWildcard("iot:*:list")).as("段内星号不是通配符语义").isFalse();
        assertThat(OpenApiPrincipal.isWildcard(null)).isFalse();
    }

    @Test
    @DisplayName("去空白、去重，并保持首次出现顺序（保存/展示稳定）")
    void trimmingAndDedup() {
        Set<String> scopes = new LinkedHashSet<>(
            List.of(" iot:device:list ", "iot:device:list", "  ", "iot:series:get"));

        assertThat(OpenApiPrincipal.scopesToPermissions(scopes))
            .containsExactly("iot:device:list", "iot:series:get");
    }

    @Test
    @DisplayName("空 / null scopes ⇒ 空列表（绝不 null、绝不放行）")
    void emptyScopesMustYieldEmptyList() {
        assertThat(OpenApiPrincipal.scopesToPermissions(null)).isNotNull().isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(Set.of())).isNotNull().isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("   "))))
            .isEmpty();
    }

    @Test
    @DisplayName("wildcardsIn 能把被剔除的通配符报出来（供记日志，避免静默）")
    void wildcardsInMustReport() {
        Set<String> scopes = new LinkedHashSet<>(List.of("iot:device:list", "*", "*:*:*"));

        assertThat(OpenApiPrincipal.wildcardsIn(scopes)).containsExactly("*", "*:*:*");
        assertThat(OpenApiPrincipal.wildcardsIn(Set.of("iot:device:list"))).isEmpty();
        assertThat(OpenApiPrincipal.wildcardsIn(null)).isEmpty();
    }

    @Test
    @DisplayName("常量与 starter 的约定同值（改了会被静默放大成超管）")
    void constantsMustMatchStarterConvention() {
        // starter: StpPermissionAdapter.SUPER_ADMIN = "*:*:*"、ANY = "*"
        assertThat(OpenApiPrincipal.SUPER_ADMIN_WILDCARD).isEqualTo("*:*:*");
        assertThat(OpenApiPrincipal.ANY_WILDCARD).isEqualTo("*");
    }
}
