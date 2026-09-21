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
 * 点位映射的协议地址类型（§3.9）。
 *
 * <p>数据库与接口存/传 {@code code}；具体协议支持的子集由 iot-starter 能力声明决定，
 * 不支持时按 {@code UnsupportedCapabilityException} 显式失败（I6），不得静默。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public enum AddressType {

    /** Modbus 保持寄存器 */
    HOLDING("holding", "保持寄存器"),

    /** Modbus 输入寄存器 */
    INPUT("input", "输入寄存器"),

    /** Modbus 线圈 */
    COIL("coil", "线圈"),

    /** Modbus 离散输入 */
    DISCRETE("discrete", "离散输入"),

    /** OPC UA NodeId */
    NODEID("nodeid", "OPC UA NodeId"),

    /** MQTT 主题 */
    TOPIC("topic", "MQTT 主题");

    private final String code;
    private final String desc;

    AddressType(String code, String desc) {
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
     * @param code 地址类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static AddressType of(String code) {
        for (AddressType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
