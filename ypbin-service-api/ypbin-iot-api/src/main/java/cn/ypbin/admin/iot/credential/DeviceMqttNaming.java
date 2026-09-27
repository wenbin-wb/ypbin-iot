/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.credential;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 设备 MQTT 接入的命名约定（用户名、clientId、主题前缀、凭据引用）。
 *
 * <p>口径来自 {@code docs/EMQX-INGRESS-DESIGN.md} §5.1/§5.3：
 * 用户名 = {@code {tenantId}.{deviceId}}（**两段纯数字**，靠它做 ACL 模板展开）、
 * 主题 = {@code ypbin/v1/{tenantId}/{deviceId}/{up|down}/…}、
 * 凭据引用 = {@code emqx:password_based:built_in_database:{tenantId}.{deviceId}}。</p>
 *
 * <p><b>为什么用户名必须匹配 {@code ^[0-9]+\.[0-9]+$}</b>：ACL 的主题模板会用 username 展开
 * （{@code ${client_attrs.tenant}}），若用户名里混入 {@code +} {@code #} {@code /} 就能把模板展开成
 * 更宽的主题 ⇒ 越权订阅。检测点放在**构造侧**（两段都是数据库 BIGINT，天然满足）与**校验侧**
 * （{@link #parseTenantId}/{@link #parseDeviceId} 一律先过正则），不靠调用方自觉。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public final class DeviceMqttNaming {

    /** 用户名分段符。 */
    public static final String USERNAME_SEPARATOR = ".";

    /** 用户名合法性（两段纯数字；防主题注入，设计 H8）。 */
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[0-9]+\\.[0-9]+$");

    /** 主题根（设计 §5.1：不用 {@code $} 前缀——那是 MQTT 保留给服务端的命名空间）。 */
    public static final String TOPIC_ROOT = "ypbin/v1";

    /** 上行主题前缀模板。 */
    private static final String TOPIC_UP_TEMPLATE = TOPIC_ROOT + "/%d/%d/up/";

    /** 下行主题前缀模板。 */
    private static final String TOPIC_DOWN_TEMPLATE = TOPIC_ROOT + "/%d/%d/down/";

    /** 凭据引用模板（**非密**：只记录凭据实体在何处，可安全落库/进日志）。 */
    private static final String CREDENTIAL_REF_TEMPLATE = "emqx:password_based:built_in_database:%d.%d";

    private DeviceMqttNaming() {
    }

    /**
     * 设备 MQTT 用户名。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 用户名（{@code {tenantId}.{deviceId}}）
     */
    public static String username(Long tenantId, Long deviceId) {
        return tenantId + USERNAME_SEPARATOR + deviceId;
    }

    /**
     * 设备 clientId（与用户名同源；topic 里不放运行时字段，clientId 只做连接标识）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return clientId
     */
    public static String clientId(Long tenantId, Long deviceId) {
        return username(tenantId, deviceId);
    }

    /**
     * 凭据引用（非密引用，设计 §5.3）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 凭据引用
     */
    public static String credentialRef(Long tenantId, Long deviceId) {
        return CREDENTIAL_REF_TEMPLATE.formatted(tenantId, deviceId);
    }

    /**
     * 上行主题前缀。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 形如 {@code ypbin/v1/1/2/up/}
     */
    public static String topicUpPrefix(Long tenantId, Long deviceId) {
        return TOPIC_UP_TEMPLATE.formatted(tenantId, deviceId);
    }

    /**
     * 下行主题前缀。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 形如 {@code ypbin/v1/1/2/down/}
     */
    public static String topicDownPrefix(Long tenantId, Long deviceId) {
        return TOPIC_DOWN_TEMPLATE.formatted(tenantId, deviceId);
    }

    /**
     * 下行属性设置主题（设计 §5.1）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 形如 {@code ypbin/v1/1/2/down/property/set}
     */
    public static String topicDownPropertySet(Long tenantId, Long deviceId) {
        return topicDownPrefix(tenantId, deviceId) + "property/set";
    }

    /**
     * 下行读属性主题（设计 §5.1）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 形如 {@code ypbin/v1/1/2/down/property/get}
     */
    public static String topicDownPropertyGet(Long tenantId, Long deviceId) {
        return topicDownPrefix(tenantId, deviceId) + "property/get";
    }

    /**
     * 下行服务调用主题（设计 §5.1）。
     *
     * <p>{@code identifier} **必须先经 {@code PropertyIdRules} 校验**（调用点在 {@code CommandPayloads}）：
     * 它会被拼进主题段，含 {@code /} {@code +} {@code #} 就能把主题扩成更宽的模式。</p>
     *
     * @param tenantId   租户 ID
     * @param deviceId   设备 ID
     * @param identifier 物模型命令标识
     * @return 形如 {@code ypbin/v1/1/2/down/service/setTemp}
     */
    public static String topicDownService(Long tenantId, Long deviceId, String identifier) {
        return topicDownPrefix(tenantId, deviceId) + "service/" + identifier;
    }

    /**
     * 上行回执主题（设计 §5.1；设备发布，{@code requestId} 在 payload 里而不是主题里）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 形如 {@code ypbin/v1/1/2/up/reply}
     */
    public static String topicUpReply(Long tenantId, Long deviceId) {
        return topicUpPrefix(tenantId, deviceId) + "reply";
    }

    /**
     * 从用户名解析租户 ID（先过正则，避免把非法值当成合法身份）。
     *
     * @param username 用户名
     * @return 租户 ID；用户名不合法时 {@link Optional#empty()}
     */
    public static Optional<Long> parseTenantId(String username) {
        return parseSegment(username, 0);
    }

    /**
     * 从用户名解析设备 ID。
     *
     * @param username 用户名
     * @return 设备 ID；用户名不合法时 {@link Optional#empty()}
     */
    public static Optional<Long> parseDeviceId(String username) {
        return parseSegment(username, 1);
    }

    /**
     * 取用户名第 N 段（只接受两段纯数字）。
     *
     * @param username 用户名
     * @param index    段下标
     * @return 该段数值；用户名不合法时 {@link Optional#empty()}
     */
    private static Optional<Long> parseSegment(String username, int index) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return Optional.empty();
        }
        String[] segments = username.split("\\" + USERNAME_SEPARATOR);
        if (segments.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(segments[index]));
        } catch (NumberFormatException ex) {
            // 正则已保证是纯数字，理论上到不了这里；到得了说明数字超出 Long 范围 ⇒ 按非法身份处理
            return Optional.empty();
        }
    }
}
