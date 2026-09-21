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
 * 服务选项（对齐 IoTDA）。
 *
 * <p>数据库与接口存/传 {@code code}（master | mandatory | optional）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum ServiceOption {

    /** 主服务（产品必须有且仅有一个） */
    MASTER("master", "主服务"),

    /** 必选服务 */
    MANDATORY("mandatory", "必选服务"),

    /** 可选服务 */
    OPTIONAL("optional", "可选服务");

    private final String code;
    private final String desc;

    ServiceOption(String code, String desc) {
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
     * @param code 服务选项码
     * @return 枚举；未匹配时 {@code null}
     */
    public static ServiceOption of(String code) {
        for (ServiceOption item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 校验编码是否属于本枚举。
     *
     * @param code 服务选项码
     * @return true 表示合法
     */
    public static boolean isValid(String code) {
        return of(code) != null;
    }
}
