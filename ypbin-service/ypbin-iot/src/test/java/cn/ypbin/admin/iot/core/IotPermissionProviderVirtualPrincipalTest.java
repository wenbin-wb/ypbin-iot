/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import cn.ypbin.admin.iot.openapi.OpenApiPrincipal;
import cn.ypbin.admin.system.api.cache.SysCache;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 权限数据源的**虚拟主体分流**用例（看板 #11 / 方案 B2）。
 *
 * <p>这是 F-1 的**代码级证据**（F-1 的端到端复跑需部署后做，见 `docs/OPENAPI-PREFLIGHT.md` §9.4）。
 * 用例要锁死的是"**两条路径互不污染**"：</p>
 * <ol>
 *   <li><b>虚拟主体**只**认 scopes，且**绝不查库**</b>（查库会拿到空 ⇒ 403；更糟的是若虚拟段
 *       不幸撞上真实用户，就会**继承那个人的权限** ⇒ 越权）；</li>
 *   <li><b>真实用户行为完全不变</b>（仍查库；本改动不得影响既有鉴权）；</li>
 *   <li><b>失败一律 fail-closed</b>（无上下文/空 scopes/全是通配符 ⇒ 空权限）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class IotPermissionProviderVirtualPrincipalTest {

    private final IotPermissionProvider provider = new IotPermissionProvider();

    @AfterEach
    void tearDown() {
        // IdentityContext 是 ThreadLocal：不清会污染同线程的其它用例
        IdentityContext.clear();
    }

    /**
     * 造一个虚拟主体身份（模拟网关注入 X-User-Id + X-Roles）。
     *
     * @param userId 虚拟用户 ID
     * @param scopes scopes（放进 roles 字段——B2 就是复用这个头）
     */
    private void givenVirtualIdentity(long userId, Set<String> scopes) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setTenantId(1L);
        user.setRoles(scopes);
        IdentityContext.setLoginUser(user);
    }

    @Test
    @DisplayName("🔴 虚拟主体：scopes 生效，且**绝不查库**")
    void virtualPrincipalMustUseScopesWithoutDb() {
        givenVirtualIdentity(-1_000_000_000L, Set.of("iot:series:get"));

        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            List<String> permissions = provider.getPermissions("-1000000000", "identity");

            assertThat(permissions).containsExactly("iot:series:get");
            // 关键：虚拟主体的权限**不来自库** —— 若查库，虚拟段万一撞上真实用户即越权
            cache.verify(() -> SysCache.getUserPermissions(anyLong()), never());
        }
    }

    @Test
    @DisplayName("🔴 虚拟主体：scopes 里的通配符被剔除（否则 starter 会升级为全权限）")
    void virtualPrincipalWildcardMustBeStripped() {
        givenVirtualIdentity(-1_000_000_000L, Set.of("iot:device:list", "*:*:*"));

        List<String> permissions = provider.getPermissions("-1000000000", "identity");

        assertThat(permissions).containsExactly("iot:device:list");
        assertThat(permissions).doesNotContain("*", "*:*:*");
    }

    @Test
    @DisplayName("虚拟主体无身份上下文 / 空 scopes ⇒ 空权限（fail-closed）")
    void virtualPrincipalWithoutScopesMustBeDenied() {
        // 无身份上下文
        assertThat(provider.getPermissions("-1000000000", "identity")).isEmpty();

        // 有身份但 scopes 为空
        givenVirtualIdentity(-1_000_000_000L, Set.of());
        assertThat(provider.getPermissions("-1000000000", "identity")).isEmpty();

        // 有身份但 scopes 只有通配符
        IdentityContext.clear();
        givenVirtualIdentity(-1_000_000_000L, Set.of("*"));
        assertThat(provider.getPermissions("-1000000000", "identity")).isEmpty();
    }

    @Test
    @DisplayName("🔴 虚拟主体只带 pattern scope（iot:*）⇒ 拒绝（白名单外一律丢弃）")
    void virtualPrincipalPatternScopeMustBeDenied() {
        givenVirtualIdentity(-1_000_000_000L, Set.of("iot:*"));

        assertThat(provider.getPermissions("-1000000000", "identity"))
            .as("iot:* 会被 Sa-Token 的 vagueMatch 命中大量权限码 ⇒ 必须丢弃")
            .isEmpty();
    }

    @Test
    @DisplayName("虚拟主体混合 scopes：只保留白名单内的")
    void virtualPrincipalMixedScopesKeepAllowlistedOnly() {
        givenVirtualIdentity(-1_000_000_000L,
            Set.of("iot:series:get", "iot:*", "system:user:list"));

        assertThat(provider.getPermissions("-1000000000", "identity"))
            .containsExactly("iot:series:get");
    }

    @Test
    @DisplayName("🔴 虚拟主体没有角色（scopes 不得同时满足 @SaCheckRole）")
    void virtualPrincipalMustHaveNoRoles() {
        givenVirtualIdentity(-1_000_000_000L, Set.of("iot:device:list"));

        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            assertThat(provider.getRoles("-1000000000", "identity")).isEmpty();
            cache.verify(() -> SysCache.getUserRoleCodes(anyLong()), never());
        }
    }

    @Test
    @DisplayName("🔴 真实用户：仍走库（本改动不改变既有鉴权行为）")
    void realUserMustStillUseDb() {
        // 真实用户即使带了 X-Roles，也不得改走 scopes 路径（否则伪造/污染 roles 即可提权）
        LoginUser user = new LoginUser();
        user.setId(2L);
        user.setTenantId(1L);
        user.setRoles(Set.of("*:*:*"));
        IdentityContext.setLoginUser(user);

        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            cache.when(() -> SysCache.getUserPermissions(2L))
                .thenReturn(List.of("iot:device:list"));
            cache.when(() -> SysCache.getUserRoleCodes(2L)).thenReturn(List.of("admin"));

            assertThat(provider.getPermissions("2", "identity")).containsExactly("iot:device:list");
            assertThat(provider.getRoles("2", "identity")).containsExactly("admin");
            cache.verify(() -> SysCache.getUserPermissions(2L), times(1));
            cache.verify(() -> SysCache.getUserRoleCodes(2L), times(1));
        }
    }

    @Test
    @DisplayName("边界：恰好等于虚拟段上界 ⇒ 走 scopes；大于 1 ⇒ 走库")
    void boundaryMustSplitPaths() {
        long virtualId = OpenApiPrincipal.VIRTUAL_USER_ID_MAX;

        givenVirtualIdentity(virtualId, Set.of("iot:series:get"));
        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            assertThat(provider.getPermissions(String.valueOf(virtualId), "identity"))
                .containsExactly("iot:series:get");
            cache.verify(() -> SysCache.getUserPermissions(anyLong()), never());
        }

        // 上界 +1（不是虚拟段）⇒ 必须查库
        IdentityContext.clear();
        long realId = OpenApiPrincipal.VIRTUAL_USER_ID_MAX + 1;
        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            cache.when(() -> SysCache.getUserPermissions(realId)).thenReturn(List.of("x:y"));
            assertThat(provider.getPermissions(String.valueOf(realId), "identity"))
                .containsExactly("x:y");
            cache.verify(() -> SysCache.getUserPermissions(realId), times(1));
        }
    }

    @Test
    @DisplayName("库返回 null 时按拒绝处理（既有行为不变）")
    void nullFromDbMustDeny() {
        try (MockedStatic<SysCache> cache = mockStatic(SysCache.class)) {
            cache.when(() -> SysCache.getUserPermissions(7L)).thenReturn(null);
            assertThat(provider.getPermissions("7", "identity")).isEmpty();
        }
    }
}
