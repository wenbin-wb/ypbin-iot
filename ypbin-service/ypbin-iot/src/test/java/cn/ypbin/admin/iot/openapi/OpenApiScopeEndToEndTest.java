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

import cn.dev33.satoken.strategy.SaStrategy;
import cn.ypbin.admin.iot.core.IotPermissionProvider;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import cn.ypbin.starter.security.satoken.StpPermissionAdapter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API 作用域的**端到端"咬合"测试**（看板 #11）。
 *
 * <p><b>为什么必须有它</b>（独立复核 2026-09-30 的"最重要建议"）：
 * 只测 {@code scopesToPermissions} 丢弃 pattern，<b>并不能证明 Sa-Token 端不会命中</b> ——
 * 两者之间的因果链（provider → adapter → {@code SaStrategy.hasElement}）此前没有任何用例钉住。
 * 一旦有人"顺手优化"回 pattern 包容语义，纯逻辑用例可能仍然全绿。本类走<b>真实链路</b>：</p>
 *
 * <pre>
 * IotPermissionProvider → StpPermissionAdapter(starter) → SaStrategy.instance.hasElement
 * </pre>
 *
 * <p><b>并且反向钉住漏洞机制本身</b>：直接给 hasElement 一个含 {@code iot:*} 的集合，
 * 断言它<b>确实能</b>命中 {@code iot:debug:send} —— 证明"这些断言不是空跑"，
 * 也把"为什么必须白名单"变成可执行的文档。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class OpenApiScopeEndToEndTest {

    /** 固定的虚拟主体 ID（保留段）。 */
    private static final long VIRTUAL_ID = -1_000_000_000L;

    private final IotPermissionProvider provider = new IotPermissionProvider();

    private final StpPermissionAdapter adapter = new StpPermissionAdapter(provider);

    @AfterEach
    void tearDown() {
        IdentityContext.clear();
    }

    /**
     * 造虚拟主体身份（模拟网关注入 X-User-Id + X-Roles）。
     *
     * @param scopes scopes
     */
    private void givenVirtual(long userId, Set<String> scopes) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setTenantId(1L);
        user.setRoles(scopes);
        IdentityContext.setLoginUser(user);
    }

    /**
     * 走<b>真实链路</b>判定某权限码是否被授予。
     *
     * @param requiredCode 端点要求的权限码
     * @return 授予返回 true
     */
    private boolean grantedBySaToken(String requiredCode) {
        List<String> permissions = adapter.getPermissionList(String.valueOf(VIRTUAL_ID), "identity");
        return Boolean.TRUE.equals(SaStrategy.instance.hasElement.apply(permissions, requiredCode));
    }

    @Test
    @DisplayName("🔴 只授予 iot:device:list 的 Key：能读设备，但**绝不能**下发命令")
    void deviceListScopeMustNotGrantCommandSending() {
        givenVirtual(VIRTUAL_ID, Set.of("iot:device:list"));

        assertThat(grantedBySaToken("iot:device:list")).isTrue();
        assertThat(grantedBySaToken("iot:debug:send"))
            .as("命令下发是高危能力，只读 Key 绝不能被授予")
            .isFalse();
        assertThat(grantedBySaToken("iot:series:get")).isFalse();
        assertThat(grantedBySaToken("iot:alert:list")).isFalse();
    }

    @Test
    @DisplayName("🔴 pattern scope（iot:*）经真实链路后必须一无所获")
    void patternScopeMustGrantNothingEndToEnd() {
        givenVirtual(VIRTUAL_ID, Set.of("iot:*"));

        assertThat(grantedBySaToken("iot:device:list")).isFalse();
        assertThat(grantedBySaToken("iot:debug:send")).isFalse();
        assertThat(grantedBySaToken("iot:series:get")).isFalse();
    }

    @Test
    @DisplayName("🔴 超管通配符（*:*:*）经真实链路后必须一无所获（不得被补成 *）")
    void superAdminWildcardMustGrantNothingEndToEnd() {
        givenVirtual(VIRTUAL_ID, Set.of("*:*:*"));

        for (String code : OpenApiPrincipal.ALLOWED_SCOPES) {
            assertThat(grantedBySaToken(code))
                .as("Key 带 *:*:* 时不得获得任何权限（%s）", code)
                .isFalse();
        }
        assertThat(grantedBySaToken("system:user:list")).isFalse();
    }

    @Test
    @DisplayName("🔴 反向钉住漏洞机制：若权限集合里**真有** iot:*，Sa-Token 确实会命中 iot:debug:send")
    void patternWouldMatchIfItEverReachedSaToken() {
        // 直接调 Sa-Token 的真实匹配函数（绕过白名单），证明"漏洞机制真实存在"：
        // 这也是"上面的断言不是空跑"的反证 —— 一旦有人把 pattern 放回权限列表，
        // 前三个用例就会立刻转红。
        assertThat(Boolean.TRUE.equals(
            SaStrategy.instance.hasElement.apply(List.of("iot:*"), "iot:debug:send")))
            .as("iot:* 作为 pattern 能命中命令下发 ⇒ 白名单是必需的最后一道闸门")
            .isTrue();
        assertThat(Boolean.TRUE.equals(
            SaStrategy.instance.hasElement.apply(List.of("iot:device:*"), "iot:device:list")))
            .isTrue();
    }

    @Test
    @DisplayName("多 scope 的 Key：每一项各自只命中自己（不做横向扩张）")
    void multipleScopesMustNotExpandLaterally() {
        givenVirtual(VIRTUAL_ID, Set.of("iot:device:list", "iot:series:get"));

        assertThat(grantedBySaToken("iot:device:list")).isTrue();
        assertThat(grantedBySaToken("iot:series:get")).isTrue();
        assertThat(grantedBySaToken("iot:debug:send")).isFalse();
        // 未授予的短码与长码都不得被授予
        assertThat(grantedBySaToken("iot:device:import")).isFalse();
        assertThat(grantedBySaToken("iot:device:list:extra")).isFalse();
    }

    @Test
    @DisplayName("非白名单码（含段内星号）经真实链路一无所获")
    void nonAllowlistedCodesMustGrantNothing() {
        givenVirtual(VIRTUAL_ID, Set.of("iot:device:*", "iot:*:list", "system:user:list"));

        assertThat(grantedBySaToken("iot:device:list")).isFalse();
        assertThat(grantedBySaToken("system:user:list")).isFalse();
    }
}