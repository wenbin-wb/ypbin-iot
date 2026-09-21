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
 * 属性读写权限。
 *
 * <p>数据库与接口存/传 {@code code}（R | W | RW）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum AccessMode {

    /** 只读 */
    READ("R", "只读"),

    /** 只写 */
    WRITE("W", "只写"),

    /** 读写 */
    READ_WRITE("RW", "读写");

    private final String code;
    private final String desc;

    AccessMode(String code, String desc) {
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
     * @param code 权限码
     * @return 枚举；未匹配时 {@code null}
     */
    public static AccessMode of(String code) {
        for (AccessMode item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 校验编码是否属于本枚举。
     *
     * @param code 权限码
     * @return true 表示合法
     */
    public static boolean isValid(String code) {
        return of(code) != null;
    }
}
