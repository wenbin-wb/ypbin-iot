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

import java.util.List;

/**
 * 比较符**码**（设计 §2.1 表 B 的 {@code operator}）。
 *
 * <p>取值与阿里云监控的运算符集合一致（{@code >=} {@code >} {@code <=} {@code <} {@code !=}
 * 及相等，设计 §1.2），但存的是**码**不是符号：符号要落地成 SQL/表达式时是注入面与转义面
 * （本仓铁律：枚举一律存 code）。{@link #symbol()} 只用于展示与通知文案，不参与任何解析。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertOperator {

    /** 大于（严格）。 */
    GT("GT", "大于", ">"),

    /** 大于等于。 */
    GTE("GTE", "大于等于", ">="),

    /** 小于（严格）。 */
    LT("LT", "小于", "<"),

    /** 小于等于。 */
    LTE("LTE", "小于等于", "<="),

    /** 等于。 */
    EQ("EQ", "等于", "=="),

    /** 不等于。 */
    NE("NE", "不等于", "!=");

    private final String code;

    private final String desc;

    private final String symbol;

    AlertOperator(String code, String desc, String symbol) {
        this.code = code;
        this.desc = desc;
        this.symbol = symbol;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /** 人类可读的数学符号（**仅用于展示与文案**，不得用它回解析表达式）。 */
    public String symbol() {
        return symbol;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertOperator of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertOperator value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 是否为「上界」类比较符（大于/大于等于/等于）：回差方向朝下。 */
    public boolean isUpperBound() {
        return this == GT || this == GTE || this == EQ;
    }

    /** 全部码。 */
    public static List<String> codes() {
        return List.of(GT.code, GTE.code, LT.code, LTE.code, EQ.code, NE.code);
    }
}
