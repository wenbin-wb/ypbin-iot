/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.deviceimport;

import cn.ypbin.admin.iot.enums.DeviceImportErrorCode;
import java.util.List;
import java.util.Map;

/**
 * CSV 解析结果（内存态，不落库）。
 *
 * <p>解析层只做**与业务无关**的事：编码、行数/大小上限、表头契约、逐行切分与取值裁剪。
 * 业务校验（设备编码重复、产品是否已发布等）在服务层做——那里的数据源是数据库。</p>
 *
 * @param headerColumns 表头解析出的列顺序（下标 ↔ {@link DeviceImportColumn}）
 * @param rows          逐行解析结果（顺序即文件顺序，行号从 1 开始）
 * @param fatalError    整个文件级别的错误（表头错/超限等）；非空时 {@code rows} 必为空
 * @param fatalDetail   文件级错误的具体说明（拼进 {@code fatalError.format()}）
 * @author wenbin
 * @since 2026-09-30
 */
public record DeviceImportParseResult(List<DeviceImportColumn> headerColumns,
                                      List<ParsedRow> rows,
                                      DeviceImportErrorCode fatalError,
                                      String fatalDetail) {

    /**
     * 整个文件可用的解析结果。
     *
     * @param headerColumns 表头列顺序
     * @param rows          数据行
     * @return 结果
     */
    public static DeviceImportParseResult ok(List<DeviceImportColumn> headerColumns, List<ParsedRow> rows) {
        return new DeviceImportParseResult(headerColumns, rows, null, null);
    }

    /**
     * 文件级失败（表头错/超限/编码错）。整批拒绝，不产生任何明细行。
     *
     * @param error  错误码
     * @param detail 具体说明
     * @return 结果
     */
    public static DeviceImportParseResult fatal(DeviceImportErrorCode error, String detail) {
        return new DeviceImportParseResult(List.of(), List.of(), error, detail);
    }

    /**
     * 是否文件级失败。
     *
     * @return 失败返回 {@code true}
     */
    public boolean isFatal() {
        return fatalError != null;
    }

    /**
     * 一行数据的解析结果。
     *
     * <p>{@code rawLine} 与 {@code values} **并存**：前者原样留痕（写进明细表供用户对照），
     * 后者是切分后的取值。刻意不在校验失败时丢掉 {@code values}——失败行的失败行 CSV 要能
     * 按列还原出模板形态，用户改完直接重传。</p>
     *
     * @param rowNo    行号（数据行从 1 开始）
     * @param rawLine  原始行内容
     * @param values   列 → 取值（只含表头里出现过的列）
     * @param errorCode 该行的 CSV 语法错误（引号未闭合等）；为 {@code null} 时取值可用
     * @param errorDetail 语法错误的具体说明
     * @author wenbin
     * @since 2026-09-30
     */
    public record ParsedRow(int rowNo,
                            String rawLine,
                            Map<DeviceImportColumn, String> values,
                            DeviceImportErrorCode errorCode,
                            String errorDetail) {

        /**
         * 语法合法的行。
         *
         * @param rowNo   行号
         * @param rawLine 原始行
         * @param values  取值
         * @return 行
         */
        public static ParsedRow of(int rowNo, String rawLine, Map<DeviceImportColumn, String> values) {
            return new ParsedRow(rowNo, rawLine, values, null, null);
        }

        /**
         * CSV 语法非法的行（该行失败，但**不影响其它行**）。
         *
         * @param rowNo   行号
         * @param rawLine 原始行
         * @param error   错误码
         * @param detail  具体说明
         * @return 行
         */
        public static ParsedRow invalid(int rowNo, String rawLine, DeviceImportErrorCode error,
                                        String detail) {
            return new ParsedRow(rowNo, rawLine, Map.of(), error, detail);
        }

        /**
         * 取值（去首尾空白；未出现的列返回空串，不是 null）。
         *
         * @param column 列
         * @return 取值（永不返回 {@code null}）
         */
        public String value(DeviceImportColumn column) {
            String raw = values.get(column);
            return raw == null ? "" : raw.trim();
        }

        /**
         * 取值是否为空（空白视为空）。
         *
         * @param column 列
         * @return 空返回 {@code true}
         */
        public boolean isBlank(DeviceImportColumn column) {
            return value(column).isEmpty();
        }
    }
}
