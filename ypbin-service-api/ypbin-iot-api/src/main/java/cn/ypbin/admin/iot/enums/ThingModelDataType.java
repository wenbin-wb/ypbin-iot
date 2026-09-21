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
 * 物模型数据类型（对齐 IoTDA 在线开发 9 类）。
 *
 * <p>数据库与接口存/传 {@code code}（camelCase 官方名）；导出 TSL 时按官方名输出。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum ThingModelDataType {

    /** 整型 */
    INT("int", "整型"),

    /** 长整型 */
    LONG("long", "长整型"),

    /** 小数 */
    DECIMAL("decimal", "小数"),

    /** 字符串 */
    STRING("string", "字符串"),

    /** 布尔 */
    BOOL("bool", "布尔"),

    /** 枚举 */
    ENUM("enum", "枚举"),

    /** 日期时间 */
    DATE_TIME("date_time", "日期时间"),

    /** JSON 对象 */
    JSON_OBJECT("json_object", "JSON 对象"),

    /** 字符串数组 */
    ARRAY("array", "数组");

    private final String code;
    private final String desc;

    ThingModelDataType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static ThingModelDataType of(String code) {
        for (ThingModelDataType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
