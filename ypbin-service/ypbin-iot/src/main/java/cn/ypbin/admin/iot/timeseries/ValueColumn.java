/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import cn.ypbin.admin.iot.enums.ThingModelDataType;

/**
 * 时序表的**落列**（{@code value_double} / {@code value_text}）——落列的**唯一真源**。
 *
 * <p>列只由物模型 {@code iot_property.data_type} 决定，**与值的形态无关**。这解决的是
 * 「同一属性的不同值可能落进不同列」⇒ 按单列查询静默漏数据（曲线/聚合/告警统计全中招）的问题；
 * 也根除了「v3 {@code forceText} 只对 string 生效」的补丁式做法。见
 * {@code docs/VALUE-DECODE-DESIGN.md} §4.6。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public enum ValueColumn {

    /** 数值列 {@code value_double}。 */
    NUMERIC("value_double"),

    /** 文本列 {@code value_text}。 */
    TEXT("value_text");

    private final String columnName;

    ValueColumn(String columnName) {
        this.columnName = columnName;
    }

    /**
     * 物理列名。
     *
     * @return {@code value_double} / {@code value_text}
     */
    public String columnName() {
        return columnName;
    }

    /**
     * 由物模型声明类型决定落列（**穷举 switch，无 default**）。
     *
     * <p><b>这是 SSOT 的机器守卫</b>：将来物模型新增第 10 类时，本方法的 switch 会**编译失败**，
     * 强制新增者更新唯一映射表——绝不允许退化成 {@code default -> 某个列} 的猜测。</p>
     *
     * <p>映射表（与 {@code docs/VALUE-DECODE-DESIGN.md} §4.6.2 一致）：</p>
     * <ul>
     *   <li>{@code int}/{@code long}/{@code decimal} ⇒ {@link #NUMERIC}；</li>
     *   <li>{@code bool}/{@code string}/{@code enum}/{@code date_time}/{@code json_object}/{@code array}
     *       ⇒ {@link #TEXT}（布尔存文本与本仓既有决定一致）。</li>
     * </ul>
     *
     * @param type 物模型声明类型（非空；缺失/未知类型由调用方先行显式丢弃，不得传 null 进本方法）
     * @return 目标列
     */
    public static ValueColumn from(ThingModelDataType type) {
        return switch (type) {
            case INT, LONG, DECIMAL -> NUMERIC;
            case BOOL, STRING, ENUM, DATE_TIME, JSON_OBJECT, ARRAY -> TEXT;
        };
    }
}
