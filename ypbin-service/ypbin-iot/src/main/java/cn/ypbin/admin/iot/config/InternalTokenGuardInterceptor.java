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
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部端点（{@code /internal/**}）调用凭证守卫。
 *
 * <p>与 system 服务同构（平台统一的入站可信校验三要点）：<b>fail-closed</b>（凭证未配置整体拒绝）、
 * <b>常量时间比较</b>（{@code MessageDigest.isEqual}，不用 {@code String.equals}）、
 * 失败转统一响应（由全局异常处理器转 HTTP 200 + {@code R.code=401}）。</p>
 *
 * <p>为什么 IoT 服务要自带一份：它是独立部署单元，不能依赖 system 的 Web 配置；
 * 复用的是同一份 {@code InternalProperties} 与 {@code InternalTokenConstants}（同一个凭证）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class InternalTokenGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenGuardInterceptor.class);

    private final InternalProperties internalProperties;

    /**
     * 构造守卫。
     *
     * @param internalProperties 内部调用参数（凭证）
     */
    public InternalTokenGuardInterceptor(InternalProperties internalProperties) {
        this.internalProperties = internalProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String configured = internalProperties.getToken();
        if (configured == null || configured.isBlank()) {
            // 不静默放行：没配凭证就是没配信任边界
            log.error("[iot] 内部调用凭证未配置（ypbin.internal.token），已拒绝 /internal/** 请求：uri={}",
                LogSanitizer.sanitize(request.getRequestURI()));
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED, "内部调用凭证未配置，请先配置 ypbin.internal.token");
        }
        String presented = request.getHeader(InternalTokenConstants.TOKEN_HEADER);
        if (presented == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[iot] 内部调用凭证校验失败，已拒绝：uri={}",
                LogSanitizer.sanitize(request.getRequestURI()));
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED, "内部调用凭证校验失败");
        }
        return true;
    }
}
