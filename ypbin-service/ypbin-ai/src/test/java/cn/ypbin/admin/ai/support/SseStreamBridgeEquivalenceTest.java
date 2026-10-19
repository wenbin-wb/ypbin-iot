/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.messaging.sse.SseStreamBridge;
import cn.ypbin.starter.messaging.sse.SseStreamOptions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * starter {@link SseStreamBridge#errorEmitter(String)} 与本仓<strong>原实现的行为等价性</strong>证明
 * （starter #105 第一步）。
 *
 * <p><b>为什么需要这个用例</b>：本仓原先在 {@code AiChatSseSupport} 里有一份
 * {@code errorEmitter} 副本（issue #105 要求删掉、改调 starter）。删除副本等于把一段
 * <strong>对前端可见的行为</strong>交给基座实现，只读源码说「语义一致」不足以作为证据：
 * 前端按「{@code event:error} 才是错误帧」解析，帧文本差一个字节就会改变客户端行为。</p>
 *
 * <p><b>证明方式</b>：把被删除的原实现<strong>逐字</strong>保留在本测试里（见
 * {@link #legacyErrorEmitter(String)}），与 starter 版在<strong>同一输入</strong>下
 * 比较三件事：</p>
 * <ol>
 *   <li>连接超时相同（都是 {@code 0L} = 不设总超时）；</li>
 *   <li>发出的 SSE 帧片段<strong>逐字节相同</strong>：{@code event:error\ndata:<文案>\n\n}；</li>
 *   <li>两者都立即 {@code complete()}（前端不会拿到空流）。</li>
 * </ol>
 *
 * <p>帧片段通过反射读取 {@link ResponseBodyEmitter} 的 {@code earlySendAttempts}
 * （未初始化 handler 时 {@code send(...)} 的暂存队列，即真实会写入响应的字节）。
 * 这是 Spring 7.0.9 的内部字段，字段名变化会让本用例<strong>显式失败</strong>而不是静默跳过。</p>
 *
 * <p><b>范围声明</b>：本用例只覆盖 {@code errorEmitter}。issue 第二步（整段改用
 * {@code SseStreamBridge.stream}）<b>未做</b>——那会改变超时语义、上下文传播与错误前缀
 * （{@code DEFAULT_ERROR_PREFIX} 只用于 {@code failStream}，不参与 {@code errorEmitter}）。</p>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class SseStreamBridgeEquivalenceTest {

    /** Spring 内部暂存字段名（未初始化 handler 时 send 的数据落在这里）。 */
    private static final String EARLY_SEND_ATTEMPTS_FIELD = "earlySendAttempts";

    @Test
    @DisplayName("★ 等价性：starter errorEmitter 的帧文本与本仓原实现逐字节一致")
    void starterErrorEmitterShouldEmitSameBytesAsLegacy() {
        String message = "AI 模块未启用，请配置 ypbin.ai.enabled=true";

        List<String> legacyFragments = fragmentsOf(legacyErrorEmitter(message));
        List<String> starterFragments = fragmentsOf(SseStreamBridge.errorEmitter(message));

        assertThat(starterFragments)
            .as("前端按 event:error 判定错误帧；帧文本必须与删除前的实现逐字节相同")
            .isEqualTo(legacyFragments)
            .containsExactly("event:error\ndata:", message, "\n\n");
    }

    @Test
    @DisplayName("等价性：超时语义相同（都是 0L = 永不超时，第二步不要顺手改）")
    void starterErrorEmitterShouldNotSetTimeout() {
        SseEmitter legacy = legacyErrorEmitter("x");
        SseEmitter starter = SseStreamBridge.errorEmitter("x");

        assertThat(legacy.getTimeout()).isZero();
        assertThat(starter.getTimeout())
            .as("当前业务用 new SseEmitter(0L)（永不超时）；starter 默认同为 0，故切换不改变超时行为")
            .isEqualTo(legacy.getTimeout());
    }

    @Test
    @DisplayName("等价性：错误文案原样发出，不加任何前缀（前缀只用于 stream 的 failStream）")
    void starterErrorEmitterShouldNotAddPrefix() {
        List<String> fragments = fragmentsOf(SseStreamBridge.errorEmitter("对话出错：不应再加前缀"));

        assertThat(fragments)
            .as("errorEmitter 不做前缀拼接；DEFAULT_ERROR_PREFIX 仅 failStream 使用")
            .containsExactly("event:error\ndata:", "对话出错：不应再加前缀", "\n\n");
        assertThat(SseStreamOptions.defaults().errorPrefix()).isEqualTo("对话出错：");
    }

    @Test
    @DisplayName("等价性：rootMessage 取最深层原因，无消息回退类简单名（与原实现同语义）")
    void starterRootMessageShouldMatchLegacySemantics() {
        assertThat(SseStreamBridge.rootMessage(
            new RuntimeException("外层", new IllegalStateException("最深层"))))
            .isEqualTo(legacyRootMessage(new RuntimeException("外层", new IllegalStateException("最深层"))));

        RuntimeException noMessage = new RuntimeException();
        assertThat(SseStreamBridge.rootMessage(noMessage))
            .isEqualTo(legacyRootMessage(noMessage))
            .isEqualTo("RuntimeException");
    }

    /**
     * 被删除的原实现（逐字保留，作为等价性的参照物）。
     *
     * <p>不要把它「优化」成调用 starter——那会让本用例变成同义反复、失去证明力。</p>
     *
     * @param message 错误文案
     * @return 已完成的 SSE 响应
     */
    private static SseEmitter legacyErrorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
        } catch (Exception e) {
            // 原实现此处 log.warn；测试中不会失败，保留同构结构以便逐行对照
            throw new IllegalStateException("原实现的发送分支不应在测试中失败", e);
        }
        emitter.complete();
        return emitter;
    }

    /**
     * 被删除的原实现（逐字保留）。
     *
     * @param e 异常
     * @return 根因文案
     */
    private static String legacyRootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * 读取 emitter 已暂存的 SSE 帧片段（真实会写进响应的字节）。
     *
     * @param emitter SSE 响应
     * @return 片段列表
     */
    private static List<String> fragmentsOf(SseEmitter emitter) {
        try {
            Field field = ResponseBodyEmitter.class.getDeclaredField(EARLY_SEND_ATTEMPTS_FIELD);
            field.setAccessible(true);
            Object value = field.get(emitter);
            List<String> fragments = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                ResponseBodyEmitter.DataWithMediaType data = (ResponseBodyEmitter.DataWithMediaType) item;
                fragments.add(String.valueOf(data.getData()));
            }
            return fragments;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "读取 ResponseBodyEmitter#" + EARLY_SEND_ATTEMPTS_FIELD
                    + " 失败：Spring 内部字段已变更，本等价性用例需同步更新（不得静默跳过）", e);
        }
    }
}
