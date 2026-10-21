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
 * 数字孪生属性绑定的展示角色。
 *
 * <p>数据库与接口存/传 {@code code}（primary | secondary）。{@link #PRIMARY} 至多一条：
 * 它是锚点图标旁常显的那一行。其余绑定进浮层/详情——若允许多条 primary，
 * 运行态无法决定「标签牌上写哪个值」。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinDisplayRole {

    /** 主展示（常显） */
    PRIMARY("primary", "主展示"),

    /** 次展示（浮层/详情） */
    SECONDARY("secondary", "次展示");

    private final String code;
    private final String desc;

    TwinDisplayRole(String code, String desc) {
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
     * @param code 展示角色码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinDisplayRole of(String code) {
        for (TwinDisplayRole item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
