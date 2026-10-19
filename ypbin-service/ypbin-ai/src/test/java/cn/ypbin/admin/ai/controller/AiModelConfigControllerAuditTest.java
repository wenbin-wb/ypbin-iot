/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.ai.model.req.KbMultiSearchTestReq;
import cn.ypbin.admin.ai.model.req.KbQueryReq;
import cn.ypbin.admin.ai.model.req.KbSearchTestReq;
import cn.ypbin.admin.ai.service.AiModelConfigService;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.log.aspect.LogAspect;
import cn.ypbin.starter.log.core.IpLocationResolver;
import cn.ypbin.starter.log.core.LogClientProvider;
import cn.ypbin.starter.log.core.LogUserProvider;
import cn.ypbin.starter.log.enums.Include;
import cn.ypbin.starter.log.event.LogEvent;
import cn.ypbin.starter.log.model.LogRecord;
import cn.ypbin.starter.log.support.LogCollector;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code AiModelConfigController#testConnection} 的<strong>审计与凭据卫生</strong>测试（issue #89）。
 *
 * <p><b>修的是什么</b>：该端点会解密 API Key 并向外部模型服务发真实 HTTP 请求（消耗配额），
 * 原先既没有 {@code @Log}（不落 {@code sys_log}），也不写 {@code ai_query_log}
 * ⇒ 「谁在何时测了哪个模型」完全无从追溯。</p>
 *
 * <p><b>为什么这样测</b>：只断言「方法上有 {@code @Log} 注解」是<strong>源码级</strong>证据，
 * 证明不了「切面真的拦到了、写进 {@code sys_log.request_param} 的到底是什么」。本用例把真实的
 * {@link LogAspect} + {@link LogCollector}（与运行时同一套实现）织到控制器上，并在
 * {@link RequestContextHolder} 里放一个模拟真实请求的 Servlet 请求，然后断言切面采集出的
 * {@link LogRecord}：</p>
 * <ol>
 *   <li>{@code description}/{@code module} 与注解一致（证明注解语义接上了）；</li>
 *   <li>{@code requestParam} <b>不含</b> API Key——该端点只有路径变量 {@code id}，
 *       路径变量不进 {@code getParameterMap()}，而 {@code @Log} 默认采集集是
 *       {@code REQUEST_PARAM}+{@code IP}+{@code CLIENT}（不含请求体）；</li>
 *   <li>{@code requestBody}/{@code responseBody} 均未采集（进一步排除密钥落库路径）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class AiModelConfigControllerAuditTest {

    /** 模型配置主键（路径变量）。 */
    private static final long MODEL_ID = 42L;

    @Test
    @DisplayName("★ 切面实测：testConnection 被 @Log 拦到，且采集结果里不含 API Key")
    void testConnectionShouldBeAuditedWithoutLeakingApiKey() {
        AiModelConfigService service = mock(AiModelConfigService.class);
        when(service.testConnection(MODEL_ID)).thenReturn(88L);
        AiModelConfigController target = new AiModelConfigController(service);

        AtomicReference<LogRecord> captured = new AtomicReference<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof LogEvent logEvent) {
                captured.set(logEvent.getLogRecord());
            }
        };
        LogAspect aspect = new LogAspect(collector(), publisher, Include.defaultIncludes());

        try {
            // 与运行时同一套 AOP 织入方式：切面能否拦到 = 注解是否真的生效
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            AiModelConfigController proxy = factory.getProxy();

            // 模拟真实请求：路径变量在 URI 里；该端点没有 query/form 参数
            bindRequest("POST", "/models/" + MODEL_ID + "/test");

            assertThat(proxy.testConnection(MODEL_ID).getData()).containsEntry("latencyMs", 88L);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        LogRecord record = captured.get();
        assertThat(record)
            .as("没有采集到日志记录 ⇒ @Log 没生效（注解写了但切面没拦到），审计仍为零")
            .isNotNull();
        assertThat(record.getDescription()).isEqualTo("模型连通性测试");
        assertThat(record.getModule()).isEqualTo("AI 配置");
        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getRequestUri()).isEqualTo("/models/" + MODEL_ID + "/test");
        assertThat(record.getRequestParam())
            .as("路径变量不进 getParameterMap()，@Log 默认也不采请求体 ⇒ request_param 不可能带出 API Key"
                + "（Key 在 service 内解密，不进入请求参数）")
            .isNull();
        assertThat(record.getRequestBody()).isNull();
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getErrorMsg()).isNull();
    }

    @Test
    @DisplayName("凭据卫生：注解未追加任何会把请求体/响应体带进日志的采集项")
    void logAnnotationShouldNotEnableBodyCollection() throws NoSuchMethodException {
        Method method = AiModelConfigController.class
            .getMethod("testConnection", Long.class);
        Log annotation = method.getAnnotation(Log.class);

        assertThat(annotation).as("testConnection 必须挂 @Log（否则消耗外部配额的调用零审计）").isNotNull();
        assertThat(annotation.value()).isEqualTo("模型连通性测试");
        assertThat(annotation.module()).isEqualTo("AI 配置");
        assertThat(annotation.includes())
            .as("不得追加 REQUEST_BODY/RESPONSE_BODY 等采集项：本端点响应是耗时、请求体为空，"
                + "开启只会扩大敏感信息落库面")
            .isEmpty();
    }

    @Test
    @DisplayName("★ 防误改：5 个问答/检索端点不得挂 @Log（审计由 ai_query_log 承担）")
    void queryEndpointsShouldNotBeAnnotatedWithLog() throws NoSuchMethodException {
        Class<?> kb = AiKnowledgeBaseController.class;
        List<Method> methods = List.of(
            kb.getMethod("query", Long.class, KbQueryReq.class),
            kb.getMethod("searchTest", Long.class, KbSearchTestReq.class),
            kb.getMethod("searchRerankTest", Long.class, KbSearchTestReq.class),
            kb.getMethod("searchMultipleTest", KbMultiSearchTestReq.class),
            kb.getMethod("queryWithSources", Long.class, KbQueryReq.class));

        for (Method method : methods) {
            assertThat(method.getAnnotation(Log.class))
                .as("%s 的审计由 ai_query_log 承担，重复挂 @Log 会双写并放大配额噪声", method.getName())
                .isNull();
        }
    }

    /**
     * 构造与运行时同源的采集器：用户/客户端 provider 与 IP 解析器都用「未接入」的默认实现，
     * 只有 Servlet 请求信息来自真实的 {@link ServletRequestAttributes}。
     */
    private static LogCollector collector() {
        LogUserProvider userProvider = Optional::empty;
        LogClientProvider clientProvider = Optional::empty;
        IpLocationResolver ipResolver = ip -> null;
        return new LogCollector(userProvider, clientProvider, ipResolver, new ObjectMapper());
    }

    private static void bindRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
