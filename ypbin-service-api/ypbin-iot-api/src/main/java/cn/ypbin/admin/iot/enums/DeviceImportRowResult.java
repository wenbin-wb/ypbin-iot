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
 * 设备批量导入**单行结果**（有界集合，落库/筛选/统计都用 {@code code}）。
 *
 * <p>与批次状态（{@link DeviceImportStatus}）是**两个维度**，不可互相替代：
 * 批次状态回答「这一批整体怎么样」，单行结果回答「这一行进没进去」。
 * 页面的「只看失败行」筛选筛的是**单行结果**，不是批次状态。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum DeviceImportRowResult {

    /** 该行创建成功。 */
    SUCCESS("success", "成功"),

    /** 该行校验或写入失败（失败原因见 {@link DeviceImportErrorCode}）。 */
    FAILED("failed", "失败");

    private final String code;

    private final String desc;

    DeviceImportRowResult(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 结果码（数据库/接口/筛选参数用）。
     *
     * @return 结果码
     */
    public String getCode() {
        return code;
    }

    /**
     * 结果描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 宽松解析筛选参数（大小写不敏感；非法值返回 {@code null} 由调用方决定是报错还是忽略）。
     *
     * @param code 结果码文本
     * @return 匹配的枚举；无匹配返回 {@code null}
     */
    public static DeviceImportRowResult parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (DeviceImportRowResult value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }
}
