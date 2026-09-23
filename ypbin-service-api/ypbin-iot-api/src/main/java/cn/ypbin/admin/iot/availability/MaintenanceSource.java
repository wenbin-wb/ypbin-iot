/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

/**
 * 维护窗口来源码（枚举带 code/desc，落库与传输一律用 {@code code}，不使用 ordinal）。
 *
 * @author wenbin
 * @since 2026-09-23
 */
public enum MaintenanceSource {

    /** 人工声明的计划维护（夜间/周末停机等）。 */
    MANUAL("MANUAL", "人工维护"),

    /** 租约交接自动窗口：旧节点已停采、新节点尚未接管之间的空档（不是设备断档）。 */
    LEASE_HANDOVER("LEASE_HANDOVER", "租约交接");

    private final String code;

    private final String desc;

    MaintenanceSource(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }
}
