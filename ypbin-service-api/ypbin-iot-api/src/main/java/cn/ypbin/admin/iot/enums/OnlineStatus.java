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
 * 设备在线状态（§4.3：两条路分别写，不混用）。
 *
 * <p>数据库与接口存/传 {@code code}（online | offline | unknown）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum OnlineStatus {

    /** 在线 */
    ONLINE("online", "在线"),

    /** 离线 */
    OFFLINE("offline", "离线"),

    /** 未知（未上报过心跳/状态） */
    UNKNOWN("unknown", "未知");

    private final String code;
    private final String desc;

    OnlineStatus(String code, String desc) {
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
    public static OnlineStatus of(String code) {
        for (OnlineStatus item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
