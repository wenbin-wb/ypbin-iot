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

import cn.ypbin.starter.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关层硬拒绝 {@code /iot/internal/**}：内部面绝不对外可达（M2）。
 *
 * <p><b>为什么必须在这里拦，而不是只靠服务侧 Token</b>：网关的 {@code iot} 路由是
 * {@code Path=/iot/**} + {@code StripPrefix=1}，外部请求 {@code /iot/internal/...} 会被剥掉短名后
 * 转发到 iot 的 {@code /internal/...}，只剩 {@code X-Internal-Token} 单点。纵深防御要求
 * <b>路由层就不让这类请求进得来</b>，Token 只是第二层。</p>
 *
 * <p><b>为什么用 {@link WebFilter} 而不是 GlobalFilter/路由断言</b>：{@code WebFilter} 在
 * 网关路由解析与 {@code GlobalFilter} 链之前执行，因此<ul>
 *   <li>不受路由表顺序、误配、动态刷新影响——即使 {@code iot} 通配路由被改坏也拦得住；</li>
 *   <li>可脱离 Spring 上下文用 {@code MockServerWebExchange} 直接单测（见
 *       {@code InternalEndpointBlockFilterTest}）。</li>
 * </ul></p>
 *
 * <p>返回 {@code 404} 而不是 {@code 403}：不向外部确认「该路径存在但在内网」。URL 第一段是服务短名，
 * 内部端点统一挂在业务服务的 {@code /internal/**} 下，故按 {@code /iot/internal/**} 前缀判定；
 * 单独的 {@code /iot/internal}（无尾斜杠）也一并拒绝。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@Component
public class InternalEndpointBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalEndpointBlockFilter.class);

    /** 对外不可达的内部面前缀（URL 第一段=服务短名）。 */
    public static final String BLOCKED_PREFIX = "/iot/internal";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isBlocked(path)) {
            return chain.filter(exchange);
        }
        log.warn("[gateway] 拒绝对外访问内部面：path={} remote={}", LogSanitizer.sanitize(path),
            LogSanitizer.sanitize(String.valueOf(exchange.getRequest().getRemoteAddress())));
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    /**
     * 判定路径是否属于内部面。
     *
     * <p>匹配 {@code /iot/internal} 与 {@code /iot/internal/...}，但<b>不</b>匹配
     * {@code /iot/internals/**} 这类同前缀的其它资源（避免误伤）。</p>
     *
     * @param path 请求路径
     * @return 属于内部面返回 {@code true}
     */
    static boolean isBlocked(String path) {
        return path != null
            && (path.equals(BLOCKED_PREFIX) || path.startsWith(BLOCKED_PREFIX + "/"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
