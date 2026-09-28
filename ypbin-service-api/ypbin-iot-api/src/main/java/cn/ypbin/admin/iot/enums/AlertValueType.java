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

/**
 * 点位值的比较域（设计 §2.1 表 B 的 {@code value_type}）。
 *
 * <p><b>为什么要单列布尔</b>：点位的 {@code value} 是字符串（{@code TimeSeriesPoint.value} 是
 * {@code String}，设计 §2.2.3），布尔点位的「等于 1」语义必须显式声明，否则「把 true 当 1」的隐式转换
 * 会散落到各处，且 {@code true} / {@code 1} / {@code TRUE} 三种写法会各判各的。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertValueType {

    /** 数值（解析为十进制数比较；解析失败即「不可判定」）。 */
    NUMERIC("NUMERIC", "数值"),

    /** 布尔（按 {@code true/false/1/0} 显式解析；仅支持等于/不等于）。 */
    BOOLEAN("BOOLEAN", "布尔");

    private final String code;

    private final String desc;

    AlertValueType(String code, String desc) {
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
    public static AlertValueType of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertValueType value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
