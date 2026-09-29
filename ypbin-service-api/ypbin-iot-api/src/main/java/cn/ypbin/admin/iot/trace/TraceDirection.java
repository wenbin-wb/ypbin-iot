/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

/**
 * 消息方向（设计 `docs/MESSAGE-TRACE-DESIGN.md` §3.2）。
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum TraceDirection {

    /** 上行：设备 → 平台。 */
    UP("up", "上行"),

    /** 下行：平台 → 设备。 */
    DOWN("down", "下行"),

    /** 平台侧内部事件（离线/断档等，不属于链路两端之间的报文）。 */
    INTERNAL("internal", "平台事件");

    private final String code;

    private final String desc;

    TraceDirection(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 方向码。
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
     * 按码解析（忽略大小写；未知返回 {@code null}）。
     *
     * @param code 码
     * @return 方向；未知返回 {@code null}
     */
    public static TraceDirection ofCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (TraceDirection direction : values()) {
            if (direction.code.equalsIgnoreCase(normalized)) {
                return direction;
            }
        }
        return null;
    }
}
