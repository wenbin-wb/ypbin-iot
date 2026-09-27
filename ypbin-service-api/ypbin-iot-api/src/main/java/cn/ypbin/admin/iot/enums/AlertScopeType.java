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

import java.util.List;

/**
 * 告警规则的作用域**四级**（设计 §2.1 表 A 的 {@code scope_type}）。
 *
 * <p><b>为什么用单表 + 作用域字段而不是三张关联表</b>：本平台设备量与规则量都不大，单表更容易做门禁与
 * 租户过滤，也更容易在页面上一次查全；代价是存在「组合非法」的可能（如 {@code DEVICE} 却填了
 * {@code scope_product_id}）——这类由服务层校验 + 用例兜住，不靠类型系统（设计 §2.1 的原话）。</p>
 *
 * <p><b>最具体者优先</b>：同一设备同时命中多条规则时，
 * {@link #POINT} &gt; {@link #DEVICE} &gt; {@link #PRODUCT} &gt; {@link #TENANT}；
 * **同优先级的多条规则全部生效**（不做「只取一条」的隐藏覆盖），因为它们可能配的是不同点位或不同级别。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertScopeType {

    /** 整租户（{@code scope_product_id} / {@code scope_device_id} 必须为空）。 */
    TENANT("TENANT", "租户", 0),

    /** 某产品下全部设备（{@code scope_product_id} 必填，{@code scope_device_id} 必须为空）。 */
    PRODUCT("PRODUCT", "产品", 1),

    /** 单台设备（{@code scope_device_id} 必填，{@code scope_product_id} 必须为空）。 */
    DEVICE("DEVICE", "设备", 2),

    /** 单台设备的单个点位（两个字段都必填）。 */
    POINT("POINT", "点位", 3);

    private final String code;

    private final String desc;

    /** 具体程度（越大越具体）；用于「最具体者优先」的排序与比较。 */
    private final int specificity;

    AlertScopeType(String code, String desc, int specificity) {
        this.code = code;
        this.desc = desc;
        this.specificity = specificity;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /** 具体程度（越大越具体）。 */
    public int getSpecificity() {
        return specificity;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertScopeType of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertScopeType value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 全部码（供接口与文档生成；不含 ordinal 语义）。 */
    public static List<String> codes() {
        return List.of(TENANT.code, PRODUCT.code, DEVICE.code, POINT.code);
    }

    /** 是否点位级（必须有点位条件行）。 */
    public boolean isPointLevel() {
        return this == POINT;
    }
}
