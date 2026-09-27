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

import cn.ypbin.admin.common.config.InternalProperties;
import cn.ypbin.admin.system.api.constant.InternalTokenConstants;
import cn.ypbin.starter.core.util.LogSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MQTT 入站端点（{@code /internal/mqtt/**}）的凭证守卫 —— **原始 401**，不走统一响应信封。
 *
 * <p><b>为什么不能复用 {@link InternalTokenGuardInterceptor}</b>：那个拦截器失败时抛
 * {@code BusinessException}，由全局异常处理器转成「HTTP 200 + {@code R.code=401}」——这是本仓
 * 既有约定（浏览器/网关接口一律如此）。但对端是 EMQX 的 HTTP 动作，它**只看 HTTP 状态码**
 * （官方源码 {@code emqx_bridge_http_connector.erl} 按状态码分支）：HTTP 200 会被判「投递成功」，
 * 于是凭证错误的报文既不重试、也不计入 {@code dropped.*}，双端都绿而数据不存在（设计 §6.5 的
 * 「静默丢数据」缺口，H10）。因此破例范围内（**仅** {@code /internal/mqtt/**}，决策 D2）的
 * 认证失败必须写成真正的 {@code 401}。</p>
 *
 * <p><b>为什么用 Filter 而不是端点级 {@code @RestControllerAdvice}</b>：{@code Filter} 在
 * DispatcherServlet 之前直接写响应、**完全不进入异常处理链**，不依赖任何 bean 的优先级顺序
 * （端点级 advice 需要显式 {@code @Order(HIGHEST_PRECEDENCE)}，而全局处理器未标 {@code @Order}
 * ⇒ 「哪个胜出」不确定，是典型的静默失效形态，见设计 §6.5 的路径①②）。</p>
 *
 * <p><b>与既有拦截器并存</b>：本过滤器只覆盖 {@code /internal/mqtt/*}；该路径仍在
 * {@code InternalTokenGuardWebConfig} 的 {@code /internal/**} 拦截范围内 ⇒ 通过本过滤器后还会
 * 再过一次拦截器（纵深防御，行为一致、不冲突）。</p>
 *
 * <p><b>凭据纪律</b>：比较用 {@code MessageDigest.isEqual}（常量时间）；**未配置即拒绝**
 * （fail-closed，与既有拦截器同口径）；响应体与日志都**不含**呈递的凭证值。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public class InternalMqttTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalMqttTokenFilter.class);

    /** 401 响应体的固定文案（不含任何输入值）。 */
    private static final String UNAUTHORIZED_BODY =
        "{\"code\":\"UNAUTHORIZED\",\"message\":\"内部调用凭证校验失败\"}";

    private final InternalProperties internalProperties;

    /**
     * 构造过滤器。
     *
     * @param internalProperties 内部调用参数（凭证）
     */
    public InternalMqttTokenFilter(InternalProperties internalProperties) {
        this.internalProperties = internalProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String configured = internalProperties.getToken();
        if (configured == null || configured.isBlank()) {
            // 不静默放行：没配凭证就是没配信任边界。原始 401（不是 200 + R.code）——对端只认状态码
            log.error("[emqx→iot] 内部调用凭证未配置（ypbin.internal.token），已拒绝 MQTT 入站请求：uri={}",
                LogSanitizer.sanitize(request.getRequestURI()));
            writeUnauthorized(response);
            return;
        }
        String presented = request.getHeader(InternalTokenConstants.TOKEN_HEADER);
        if (presented == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[emqx→iot] MQTT 入站凭证校验失败（原始 401，EMQX 将判为不可重试错误）：uri={} 是否携带头={}",
                LogSanitizer.sanitize(request.getRequestURI()), presented != null);
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 写原始 401 响应（最小 JSON 体）。
     *
     * @param response 响应
     * @throws IOException 写响应失败
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
