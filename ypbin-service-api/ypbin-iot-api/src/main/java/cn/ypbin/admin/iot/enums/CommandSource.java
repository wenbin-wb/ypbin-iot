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
 * 命令实例的来源（谁能发起下发；审计用）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
public enum CommandSource {

    /** 控制台（在线调试页）——有下发人。 */
    CONSOLE("console", "控制台"),

    /** 规则引擎联动（P2-3，本轮未实现，先占位）。 */
    RULE("rule", "规则联动"),

    /** 开放 API（本轮未实现，先占位）。 */
    API("api", "开放接口");

    private final String code;

    private final String desc;

    CommandSource(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 码。
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
}
