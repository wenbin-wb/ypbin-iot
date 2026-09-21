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
 * 接入协议码（与 ypbin-iot-starter 协议码一致）。
 *
 * <p>数据库与接口存/传 {@code code}；禁止散落裸字符串。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum IotProtocol {

    /** TCP 直连 */
    TCP("tcp", "TCP"),

    /** Modbus */
    MODBUS("modbus", "Modbus"),

    /** MQTT */
    MQTT("mqtt", "MQTT"),

    /** OPC UA */
    OPCUA("opcua", "OPC UA");

    private final String code;
    private final String desc;

    IotProtocol(String code, String desc) {
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
     * @param code 协议码
     * @return 枚举；未匹配时 {@code null}
     */
    public static IotProtocol of(String code) {
        for (IotProtocol item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
