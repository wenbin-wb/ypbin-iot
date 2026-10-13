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

/**
 * 读数 → 时序表的**列物化**（task-23 §4.6）：**只按给定列** materialize，不再决定列、不再按值形态猜。
 *
 * <p>列由 {@link ValueColumn#from} 依物模型 {@code data_type} 唯一决定；本类只负责把字符串变成该列要写的东西：</p>
 * <ul>
 *   <li>{@link ValueColumn#TEXT}：原样落 {@code value_text}（不 trim、不按词法转换——
 *       值级规范化由生产方按物模型负责，见 §4.6.7）；</li>
 *   <li>{@link ValueColumn#NUMERIC}：经 {@link NumericValues#parse} 严格解析后落 {@code value_double}；不可解析
 *       属调用方必须在入站侧拦下的「声明数值但值不可解析」（{@code iot.timeseries.value.not_numeric}），
 *       本类**不提供落文本列的兜底**。</li>
 * </ul>
 *
 * <p>旧版 {@code map(raw)} / {@code map(raw, forceText)} 及全部按值形态猜列的启发式
 * （前导零、2^53 转文本等）已删除；2^53 精度损失的处置见 §4.6.5（仍写数值列 + 计数 + WARN）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public final class ReadingValueMapper {

    /** 文本列名（§4.6.2）。 */
    public static final String COLUMN_TEXT = "value_text";

    /** 数值列名（§4.6.2）。 */
    public static final String COLUMN_DOUBLE = "value_double";

    private ReadingValueMapper() {
    }

    /**
     * 映射结果：数值列用 {@link #numericValue()}，文本列用 {@link #text()}。
     *
     * @param column       目标列名
     * @param numericValue 数值；**文本行必须为 {@code null}**（用原始 {@code double} 会让「永不双写」易被违反）
     * @param text         文本（仅文本列有意义）
     * @author wenbin
     * @since 2026-09-24
     */
    public record MappedValue(String column, Double numericValue, String text) {

        /** 是否写数值列。 */
        public boolean numeric() {
            return COLUMN_DOUBLE.equals(column);
        }
    }

    /**
     * 按**声明的列**物化读数。
     *
     * @param raw    读数原值（字符串化；调用方保证非空）
     * @param column 目标列（由 {@link ValueColumn#from} 依物模型类型决定）
     * @return 映射结果
     * @throws IllegalArgumentException 声明为 {@link ValueColumn#NUMERIC} 但值不可解析为十进制数值
     *                                  （调用方应在入站侧先行校验并丢弃，见 {@link NumericValues#parse}）
     */
    public static MappedValue map(String raw, ValueColumn column) {
        if (column == ValueColumn.TEXT) {
            // 原样落文本列：不 trim（设备报的空白也是数据）、不按值形态数值化
            return new MappedValue(COLUMN_TEXT, null, raw);
        }
        Double numeric = NumericValues.parse(raw);
        if (numeric == null) {
            throw new IllegalArgumentException("声明为数值列但值不可解析为十进制数值（应在入站侧丢弃）");
        }
        return new MappedValue(COLUMN_DOUBLE, numeric, null);
    }
}
