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
 * 物模型状态（与基类 {@code status} 启停位分离）。
 *
 * <p>数据库与接口存/传 {@code code}（draft | published）；已发布同版本不可变（§3.8）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum ModelStatus {

    /** 草稿 */
    DRAFT("draft", "草稿"),

    /** 已发布 */
    PUBLISHED("published", "已发布");

    private final String code;
    private final String desc;

    ModelStatus(String code, String desc) {
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
     * @param code 状态码
     * @return 枚举；未匹配时 {@code null}
     */
    public static ModelStatus of(String code) {
        for (ModelStatus item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
