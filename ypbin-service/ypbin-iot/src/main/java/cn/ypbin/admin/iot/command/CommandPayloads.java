/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.command;

import cn.ypbin.admin.iot.credential.DeviceMqttNaming;
import cn.ypbin.admin.iot.enums.CommandKind;
import cn.ypbin.starter.iot.validate.PropertyIdRules;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 下行报文的**唯一**构造口径（topic + payload）。
 *
 * <p><b>payload 契约（设计 §7.3 ② + 评审确认 2026-09-27）</b>：</p>
 * <ul>
 *   <li>{@code property_set}：{@code {"requestId":…,"properties":{"temperature":25.0}}}
 *       （{@code properties} 是**映射**：点位 → 值）；</li>
 *   <li>{@code property_get}：{@code {"requestId":…,"properties":["temperature"]}}；
 *       <b>{@code properties} 缺省或空数组 = 读取该设备全部可读属性</b>；</li>
 *   <li>{@code service_call}：{@code {"requestId":…,"params":{…}}}（入参与物模型服务定义同源）。</li>
 * </ul>
 *
 * <p><b>主题注入防护</b>：{@code identifier} 会进 {@code down/service/{identifier}} 或作为属性键，
 * 因此**一律先过 {@link cn.ypbin.starter.iot.validate.PropertyIdRules}**（字符集白名单 + 长度上限；它天然排除 {@code /} {@code +}
 * {@code #}）。非法标识**在发布之前**就抛业务错误——绝不出现"发出去了但没人收到"的静默失败。</p>
 *
 * <p><b>体积护栏</b>：{@link #MAX_PAYLOAD_LENGTH} 与入站同口径（64KB），超额在构造期即拒绝。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
public final class CommandPayloads {

    /** 下行 payload 上限（字节/字符；与入站报文体同口径）。 */
    public static final int MAX_PAYLOAD_LENGTH = 64 * 1024;

    /** 报文字段名。 */
    public static final String FIELD_REQUEST_ID = "requestId";

    /** 属性字段名（set 用映射、get 用列表，**共用同一个键名**——评审确认）。 */
    public static final String FIELD_PROPERTIES = "properties";

    /** 服务入参字段名。 */
    public static final String FIELD_PARAMS = "params";

    /** {@code property_set} 的入参键（端点 {@code params} 里的值域）。 */
    public static final String FIELD_VALUE = "value";

    /** 审计标签：{@code property_get} 未指定标识（= 全部可读属性）时写进实例的 {@code identifier} 列。 */
    public static final String ALL_PROPERTIES_LABEL = "all-properties";

    private CommandPayloads() {
    }

    /**
     * 构造下行主题。
     *
     * @param kind       类型
     * @param tenantId   租户 ID
     * @param deviceId   设备 ID
     * @param identifier 目标标识（{@code service_call} 必填）
     * @return 主题
     */
    public static String topic(CommandKind kind, Long tenantId, Long deviceId, String identifier) {
        return switch (kind) {
            case PROPERTY_SET -> DeviceMqttNaming.topicDownPropertySet(tenantId, deviceId);
            case PROPERTY_GET -> DeviceMqttNaming.topicDownPropertyGet(tenantId, deviceId);
            case SERVICE_CALL -> {
                requireIdentifier(identifier, "服务调用");
                yield DeviceMqttNaming.topicDownService(tenantId, deviceId, identifier);
            }
        };
    }

    /**
     * 构造下行 payload。
     *
     * @param kind       类型
     * @param requestId  请求 ID
     * @param identifier 目标标识（{@code property_get} 可为空 = 全部可读属性）
     * @param paramsJson 端点入参（JSON 对象文本；{@code property_get} 忽略）
     * @param mapper     JSON 序列化器
     * @return payload 文本
     */
    public static String build(CommandKind kind, String requestId, String identifier,
                               String paramsJson, ObjectMapper mapper) {
        JsonNode params = readObjectOrNull(paramsJson, mapper);
        ObjectNode payload = mapper.createObjectNode();
        payload.put(FIELD_REQUEST_ID, requestId);
        switch (kind) {
            case PROPERTY_SET -> {
                requireIdentifier(identifier, "属性设置");
                if (params == null || !params.hasNonNull(FIELD_VALUE)) {
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "属性设置的 params 必须形如 {\"value\":<值>}（当前为空或缺少 value）");
                }
                payload.set(FIELD_PROPERTIES, mapper.createObjectNode().set(identifier, params.get(FIELD_VALUE)));
            }
            case PROPERTY_GET -> {
                ArrayNode list = mapper.createArrayNode();
                // 防御：审计标签形态也当"全部可读属性"（调用点传 null，但别让标签意外变成点位列)
                if (identifier != null && !identifier.isBlank() && !ALL_PROPERTIES_LABEL.equals(identifier)) {
                    requireIdentifier(identifier, "读属性");
                    list.add(identifier);
                }
                // 空数组 = 该设备全部可读属性（评审确认的语义，设备侧据此返回全部可读点位）
                payload.set(FIELD_PROPERTIES, list);
            }
            case SERVICE_CALL -> {
                requireIdentifier(identifier, "服务调用");
                payload.set(FIELD_PARAMS, params == null ? mapper.createObjectNode() : params);
            }
        }
        String text = mapper.writeValueAsString(payload);
        if (text.length() > MAX_PAYLOAD_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "下行报文超过上限 " + MAX_PAYLOAD_LENGTH + " 字符（实际 " + text.length() + "）");
        }
        return text;
    }

    /**
     * 解析入参 JSON 对象（空/空白返回 {@code null}；不是对象则显式报错）。
     *
     * @param paramsJson 入参文本
     * @param mapper     序列化器
     * @return 对象节点；入参为空返回 {@code null}
     */
    public static JsonNode readObjectOrNull(String paramsJson, ObjectMapper mapper) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        if (paramsJson.length() > MAX_PAYLOAD_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "params 超过上限 " + MAX_PAYLOAD_LENGTH + " 字符");
        }
        JsonNode node;
        try {
            node = mapper.readTree(paramsJson);
        } catch (RuntimeException ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "params 不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "params 必须是 JSON 对象");
        }
        return node;
    }

    /**
     * 校验标识（字符集白名单 + 长度；**主题与属性键的唯一入口**）。
     *
     * @param identifier 标识
     * @param scene      场景（报错文案用）
     */
    private static void requireIdentifier(String identifier, String scene) {
        if (!PropertyIdRules.isValid(identifier)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                scene + "的标识" + PropertyIdRules.INVALID_MESSAGE + "（含 /、+、# 等会污染主题，必须拒绝）");
        }
    }

}
