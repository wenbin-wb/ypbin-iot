/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import java.util.Locale;

/**
 * 运行期事件级别码（枚举带 code/desc，落库与传输一律用 {@code code}，不使用 ordinal）。
 *
 * <p>只有三档是刻意的：物模型事件定义（{@code iot_event}）当前连 type 字段都没有，
 * 引入更细的分级会造出一个前后端都无据可依的契约。三档能覆盖「信息/告警/错误」的展示与过滤需求。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
public enum EventLevel {

    /** 信息：正常业务事件（如上线、参数变更）。 */
    INFO("info", "信息"),
    /** 告警：需要关注但不影响运行（如超阈值）。 */
    WARN("warn", "告警"),
    /** 错误：设备或链路异常。 */
    ERROR("error", "错误");

    /** 级别码的合法长度上限（与 DDL 的 {@code level VARCHAR(16)} 对齐）。 */
    public static final int MAX_CODE_LENGTH = 16;

    private final String code;

    private final String desc;

    EventLevel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按码解析级别（大小写不敏感，前后空白已在调用方规范化）。
     *
     * @param code 级别码；{@code null}/空白/未知一律返回 {@code null}（由调用方决定报错还是兜底）
     * @return 匹配的级别；无匹配返回 {@code null}
     */
    public static EventLevel ofCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (EventLevel level : values()) {
            if (level.code.equals(normalized)) {
                return level;
            }
        }
        return null;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }
}
