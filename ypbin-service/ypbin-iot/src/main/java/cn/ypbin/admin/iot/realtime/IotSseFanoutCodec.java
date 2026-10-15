/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.realtime;

import cn.ypbin.starter.messaging.sse.SseFanoutCodec;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * IoT 实时事件的**跨实例扇出**载荷编解码器（注册为 starter 的 {@link SseFanoutCodec} Bean）。
 *
 * <p><b>为什么必须自己实现</b>：starter 的默认实现 {@code TextSseFanoutCodec} 只支持
 * null/布尔/数字/字符串/字符/枚举等**标量**，对结构化对象（本类的 record 事件载荷）返回
 * {@link Optional#empty()}；按 starter 的约定，发布方会记 WARN 并**只投本实例**——即多实例部署时
 * 「连到别的实例的订阅者收不到事件」（改造前 IoT 自建实现**根本没有**跨实例能力，所以这不是回退，
 * 但既然 starter 留了扩展点，就应该补上以免能力上收后反而少了）。starter 的接口注释明确写着
 * 「结构化对象由业务注册本类型的 Bean（IoT 的实时事件就属于这一类）」——本类正是那个 Bean。</p>
 *
 * <p><b>协议标识</b>：{@link #id()} 写进扇出帧，订阅方遇到 id 不一致的帧即丢弃并 WARN。
 * 故改动序列化口径（换 ObjectMapper 配置、改字段名）必须同步改 {@link #ID}，视为协议变更。</p>
 *
 * <p><b>JavaTime 与 Long</b>：IoT 事件含 {@code long ts} 与 {@code Long} 包装类型，必须由
 * Spring 容器的 {@link ObjectMapper} 序列化（与单实例直投时 MVC 用的那一个同源），否则
 * 「本实例收到的 JSON」与「跨实例扇出后的 JSON」会出现字段格式差异（如时间戳小数形态）。
 * 容器无 ObjectMapper 时（纯单测/裁剪部署）退化为 Jackson 默认实例，并记 debug。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Component
public class IotSseFanoutCodec implements SseFanoutCodec {

    private static final Logger log = LoggerFactory.getLogger(IotSseFanoutCodec.class);

    /** 协议标识：改序列化口径必须同步改这里（订阅方按 id 判版本，不一致即丢弃）。 */
    public static final String ID = "iot-json-v1";

    /** 载荷类型标记：结构化事件（JSON 文本）。 */
    private static final String MARKER_JSON = "j";

    /** 载荷类型标记：字符串（零依赖形态，避免为纯文本再走一次 JSON 引号包裹）。 */
    private static final String MARKER_TEXT = "s";

    private final ObjectProvider<ObjectMapper> objectMapperProvider;

    public IotSseFanoutCodec(ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.objectMapperProvider = objectMapperProvider;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<String> encode(Object payload) {
        if (payload == null) {
            // 与 starter 约定一致：null 载荷在扇出侧有明确语义（「事件名 + 空数据」），用标记承载
            return Optional.of("n");
        }
        if (payload instanceof CharSequence text) {
            return Optional.of(MARKER_TEXT + text);
        }
        try {
            return Optional.of(MARKER_JSON + mapper().writeValueAsString(payload));
        } catch (RuntimeException ex) {
            // 编码失败 ⇒ 返回空：由 starter 记 WARN 并只投本实例（**显式降级**，不发垃圾帧、不吞异常）
            log.warn("[iot] SSE 扇出载荷无法序列化（本次只投本实例，跨实例部分丢失）：payloadType={}",
                payload.getClass().getName(), ex);
            return Optional.empty();
        }
    }

    @Override
    public Object decode(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String body = text.substring(1);
        if (text.startsWith("n")) {
            return null;
        }
        if (text.startsWith(MARKER_TEXT)) {
            return body;
        }
        if (text.startsWith(MARKER_JSON)) {
            try {
                // 解码成通用树而不是具体 record 类型：跨实例帧里没有类型信息，
                // 而 MVC 的 SSE 写出会把树序列化成同一份 JSON（客户端看到的事件契约不变）
                return mapper().readTree(body);
            } catch (RuntimeException ex) {
                log.warn("[iot] SSE 扇出载荷无法反序列化（该帧丢弃）：payloadLength={}", body.length(), ex);
                return null;
            }
        }
        // 未知标记：说明对端 codec 与本端不一致，保留原文比丢数据更可诊断
        return text;
    }

    /** 容器 ObjectMapper 优先（与单实例直投同源），缺席时用 Jackson 默认实例。 */
    private ObjectMapper mapper() {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        if (mapper != null) {
            return mapper;
        }
        log.debug("[iot] 容器无 ObjectMapper，SSE 扇出编解码退化为 Jackson 默认实例");
        return DEFAULT_MAPPER;
    }

    /** 兜底 ObjectMapper（仅容器缺席时使用；不可变、线程安全）。 */
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();
}
