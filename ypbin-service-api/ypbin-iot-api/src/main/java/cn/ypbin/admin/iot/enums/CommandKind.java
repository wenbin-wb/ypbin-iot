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
 * 运行期命令实例的类型码（设计 §7.1；与物模型定义无关，是"这一次下发要干什么"）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
public enum CommandKind {

    /** 属性设置（下行主题 {@code down/property/set}，payload 用 {@code properties} 映射）。 */
    PROPERTY_SET("property_set", "属性设置"),

    /** 读属性（下行主题 {@code down/property/get}，payload 用 {@code properties} 列表；空列表 = 全部可读属性）。 */
    PROPERTY_GET("property_get", "读属性"),

    /** 服务/命令调用（下行主题 {@code down/service/{identifier}}，payload 用 {@code params}）。 */
    SERVICE_CALL("service_call", "服务调用");

    private final String code;

    private final String desc;

    CommandKind(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 类型码。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按码解析（不区分大小写地先 trim；未知返回 {@code null}，由调用方显式报错）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static CommandKind ofCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (CommandKind kind : values()) {
            if (kind.code.equalsIgnoreCase(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
