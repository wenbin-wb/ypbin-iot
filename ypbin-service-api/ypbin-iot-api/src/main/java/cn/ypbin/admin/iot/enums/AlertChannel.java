/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知渠道**码**（设计 §2.4 的渠道清单）。
 *
 * <p><b>Webhook 本期不做</b>（设计 §2.4/§2.6）：它是本设计**唯一**的 SSRF 面（平台主动出站到用户给的
 * URL），需要 URL 白名单、DNS 重绑定防护、超时、重试退避、签名、限流一整套，属独立课题。
 * 本枚举因此**只有**两个通道；将来加 Webhook 时新增一个枚举值即可，不影响存量数据的码。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertChannel {

    /** 站内信（复用 {@code sys_message} + {@code SysMessageService}）。 */
    INBOX("INBOX", "站内信"),

    /** 邮件（复用 system 侧已有的 JavaMail 能力）。 */
    EMAIL("EMAIL", "邮件");

    /** 渠道码集合的分隔符（{@code notify_channels} 是 VARCHAR(64) 逗号分隔）。 */
    public static final String SEPARATOR = ",";

    private final String code;

    private final String desc;

    AlertChannel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertChannel of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertChannel value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析逗号分隔的渠道码集合（**未知码不静默丢弃**：调用方拿到 {@code null} 元素必须显式失败或告知用户）。
     *
     * <p>返回顺序 = 出现顺序且去重（{@code LinkedHashSet}），便于通知按稳定顺序投递与断言。</p>
     *
     * @param raw 逗号分隔的渠道码（可空）
     * @return 码集合；空输入返回空集合，永不返回 {@code null}
     */
    public static Set<AlertChannel> parse(String raw) {
        Set<AlertChannel> channels = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return channels;
        }
        for (String part : raw.split(SEPARATOR)) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            AlertChannel channel = of(item);
            if (channel == null) {
                // 未知码必须暴露：静默丢弃会让「配了 Webhook 却什么都没发生」变成一个没人能查的悬案
                throw new IllegalArgumentException("未知的告警通知渠道码：" + item);
            }
            channels.add(channel);
        }
        return channels;
    }

    /**
     * 渠道码集合序列化为存储形态（逗号分隔，顺序稳定）。
     *
     * @param channels 渠道集合（可空）
     * @return 逗号分隔的码；空集合返回空串（不返回 {@code null}）
     */
    public static String join(Set<AlertChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AlertChannel channel : channels) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(channel.getCode());
        }
        return builder.toString();
    }

    /** 全部渠道码（默认通知渠道用）。 */
    public static List<String> codes() {
        return List.of(INBOX.code, EMAIL.code);
    }
}
