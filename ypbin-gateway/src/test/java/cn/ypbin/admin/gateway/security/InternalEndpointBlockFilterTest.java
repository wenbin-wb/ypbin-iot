/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * {@link InternalEndpointBlockFilter} 用例（M2：内部面不得经网关对外可达）。
 *
 * <p>覆盖：{@code /iot/internal/**} 无论带不带 Token 都被拒（本过滤器不读 Token，天然与 Token 状态无关）、
 * 正常业务路径不受影响、前缀边界不误伤同前缀资源。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class InternalEndpointBlockFilterTest {

    private final InternalEndpointBlockFilter filter = new InternalEndpointBlockFilter();

    private static MockServerWebExchange exchange(String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (token != null) {
            builder = builder.header("X-Internal-Token", token);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    @DisplayName("🔴 /iot/internal/** 无 Token 被拒（404，且不再进入下游过滤链）")
    void internalPathWithoutTokenBlocked() {
        MockServerWebExchange exchange = exchange("/iot/internal/device-specs", null);
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chained).as("被拒请求绝不能继续进入后续过滤器/路由").isFalse();
    }

    @Test
    @DisplayName("🔴 /iot/internal/** 带正确 Token 也被拒（网关层不承认任何内部凭证）")
    void internalPathWithTokenStillBlocked() {
        MockServerWebExchange exchange = exchange("/iot/internal/open-api-key/verify", "any-token");
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("正常业务路径不受影响（继续进入下游链）")
    void normalBusinessPathPassesThrough() {
        MockServerWebExchange exchange = exchange("/iot/devices/1", null);
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.filter(exchange, chain(chained)).block();

        assertThat(chained).as("普通业务路径必须继续转发").isTrue();
        assertThat(exchange.getResponse().getStatusCode()).as("不得提前写状态码").isNull();
    }

    @Test
    @DisplayName("前缀边界：/iot/internal 与 /iot/internal/... 命中，/iot/internals/** 不误伤")
    void prefixBoundaryIsExact() {
        assertThat(InternalEndpointBlockFilter.isBlocked("/iot/internal")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/iot/internal/")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/iot/internal/x/y")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/iot/internals/x")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlocked("/iot/devices")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlocked(null)).isFalse();
    }

    private static WebFilterChain chain(AtomicBoolean chained) {
        return exchange -> {
            chained.set(true);
            return Mono.empty();
        };
    }
}
