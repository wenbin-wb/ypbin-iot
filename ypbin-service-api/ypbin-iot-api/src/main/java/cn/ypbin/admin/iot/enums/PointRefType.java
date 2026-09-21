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
 * 点位映射的关联类型（属性或命令，§3.9）。
 *
 * <p>数据库与接口存/传 {@code code}（property | command）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum PointRefType {

    /** 关联属性 */
    PROPERTY("property", "属性"),

    /** 关联命令 */
    COMMAND("command", "命令");

    private final String code;
    private final String desc;

    PointRefType(String code, String desc) {
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
     * @param code 关联类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static PointRefType of(String code) {
        for (PointRefType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
