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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.common.config.InternalProperties;
import cn.ypbin.admin.system.api.constant.InternalTokenConstants;
import cn.ypbin.starter.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link InternalTokenGuardInterceptor} 安全不变量用例（L 系列）。
 *
 * <p>钉住三条：① 未配置凭证 ⇒ 拒绝（fail-closed，不静默放行）；② 错误/缺失凭证 ⇒ 拒绝；
 * ③ 正确凭证 ⇒ 通过。此前该拦截器无独立单测，安全不变量只能靠 review 记得。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class InternalTokenGuardInterceptorTest {

    private static final String CONFIGURED = "internal-token-value";

    private static MockHttpServletRequest request(String presentedToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/device-specs");
        if (presentedToken != null) {
            request.addHeader(InternalTokenConstants.TOKEN_HEADER, presentedToken);
        }
        return request;
    }

    private static InternalTokenGuardInterceptor interceptor(String configuredToken) {
        InternalProperties properties = new InternalProperties();
        properties.setToken(configuredToken);
        return new InternalTokenGuardInterceptor(properties);
    }

    @Test
    @DisplayName("🔴 未配置 token ⇒ 拒绝（fail-closed，不得静默放行 /internal/**）")
    void unconfiguredTokenMustFailClosed() {
        InternalTokenGuardInterceptor guard = interceptor(null);

        assertThatThrownBy(() -> guard.preHandle(request(CONFIGURED),
            new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内部调用凭证未配置");
    }

    @Test
    @DisplayName("🔴 错误或缺失 token ⇒ 拒绝")
    void wrongOrMissingTokenMustFail() {
        InternalTokenGuardInterceptor guard = interceptor(CONFIGURED);

        assertThatThrownBy(() -> guard.preHandle(request("wrong"),
            new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内部调用凭证校验失败");
        assertThatThrownBy(() -> guard.preHandle(request(null),
            new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内部调用凭证校验失败");
    }

    @Test
    @DisplayName("正确 token ⇒ 通过")
    void correctTokenMustPass() {
        InternalTokenGuardInterceptor guard = interceptor(CONFIGURED);

        assertThat(guard.preHandle(request(CONFIGURED), new MockHttpServletResponse(), new Object()))
            .isTrue();
    }
}
