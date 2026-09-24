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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.security.core.PermissionProvider;
import cn.ypbin.starter.security.identity.IdentityContext;
import cn.ypbin.starter.security.core.LoginUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 显式权限守卫的行为（fail-closed）。存在的理由：本仓微服务下游的 {@code @SaCheckPermission}
 * 目前不生效（见 {@link IotPermissionGuard} 类注释与 ROADMAP 四点十六）。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotPermissionGuardTest {

    private static final String CODE = "iot:maintenance:create";

    private final PermissionProvider provider = mock(PermissionProvider.class);

    private final IotPermissionGuard guard = new IotPermissionGuard(provider, new SimpleMeterRegistry());

    @AfterEach
    void clear() {
        IdentityContext.clear();
    }

    private void loginAs(long userId) {
        IdentityContext.setLoginUser(new LoginUser(userId, "tester"));
    }

    @Test
    @DisplayName("有权限码 ⇒ 放行（不打异常）")
    void mustPassWhenPermissionPresent() {
        loginAs(7L);
        when(provider.getPermissions(any(), anyString())).thenReturn(List.of("other:code", CODE));

        guard.require(CODE);
    }

    @Test
    @DisplayName("★ 没有该权限码 ⇒ 拒绝")
    void mustDenyWhenPermissionMissing() {
        loginAs(7L);
        when(provider.getPermissions(any(), anyString())).thenReturn(List.of("other:code"));

        assertThatThrownBy(() -> guard.require(CODE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("没有权限");
    }

    @Test
    @DisplayName("★ 没有登录身份（缺网关注入的身份头）⇒ 拒绝，不得放行")
    void mustDenyWhenNoIdentity() {
        assertThatThrownBy(() -> guard.require(CODE)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 权限查询返回 null/空 ⇒ 一律拒绝（绝不静默放行）")
    void mustDenyWhenProviderReturnsNothing() {
        loginAs(7L);
        when(provider.getPermissions(any(), anyString())).thenReturn(null);
        assertThatThrownBy(() -> guard.require(CODE)).isInstanceOf(BusinessException.class);

        when(provider.getPermissions(any(), anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> guard.require(CODE)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 权限查询抛异常 ⇒ 按拒绝处理（并抛业务异常，不吞）")
    void mustDenyWhenProviderFails() {
        loginAs(7L);
        when(provider.getPermissions(any(), anyString())).thenThrow(new IllegalStateException("cache down"));

        assertThatThrownBy(() -> guard.require(CODE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("权限校验失败");
    }

    @Test
    @DisplayName("拒绝计数会被记录（便于发现配置错误/被挡的人）")
    void mustCountDenials() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IotPermissionGuard counting = new IotPermissionGuard(provider, registry);
        loginAs(7L);
        when(provider.getPermissions(any(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> counting.require(CODE)).isInstanceOf(BusinessException.class);
        assertThat(registry.get(IotPermissionGuard.METRIC_DENIED).counter().count()).isEqualTo(1.0d);
    }
}
