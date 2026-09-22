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
 * 断档原因码（枚举带 code/desc，落库与传输一律用 {@code code}，不使用 ordinal）。
 *
 * @author wenbin
 * @since 2026-09-22
 */
public enum OutageReason {

    /** 连续超过 K × 采集周期没有有效数据（quality=GOOD）。 */
    NO_GOOD_DATA("NO_GOOD_DATA", "无有效数据");

    private final String code;

    private final String desc;

    OutageReason(String code, String desc) {
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
