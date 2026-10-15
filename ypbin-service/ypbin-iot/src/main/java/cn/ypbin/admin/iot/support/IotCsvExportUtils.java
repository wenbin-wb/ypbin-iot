/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 导出工具：UTF-8 BOM、RFC 4180 转义与公式注入中和。
 *
 * <p><b>为什么单独抽类</b>：这三件事是纯函数，设备导出与告警导出共用；放在 service 里没法独立验证，
 * 而「转义少一层引号」「危险首字符被 Excel 当公式执行」正是导出功能最容易静默出错的地方。</p>
 *
 * <p><b>公式注入</b>：以 {@code = + - @ TAB CR} 开头的单元格会被 Excel/WPS 当公式解析（CSV 注入），
 * 统一前缀单引号中和。负数 <code>-1</code> 也在中和范围内：导出不是数值计算入口，安全优先。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public final class IotCsvExportUtils {

    /** 单次导出上限（设备/告警共用）；超限由 service 显式拒绝，不静默截断。 */
    public static final int MAX_EXPORT_ROWS = 10_000;

    /** UTF-8 BOM：Excel 对无 BOM 的 UTF-8 CSV 常按本机编码解读，中文列会乱码。 */
    public static final String UTF8_BOM = "\uFEFF";

    /** 会被表格软件当公式解析的危险首字符。 */
    private static final char[] FORMULA_PREFIXES = {'=', '+', '-', '@', '\t', '\r'};

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private IotCsvExportUtils() {
    }

    /**
     * 组装 CSV：首部 BOM，行尾 CRLF，每个单元格先中和公式注入再按 RFC 4180 转义。
     *
     * @param headers 表头（不可为 {@code null}）
     * @param rows    数据行；单元格可为 {@code null} 或空串（输出为空串）
     * @return CSV 文本（按 UTF-8 写入响应即为 BOM 字节 EF BB BF）
     */
    public static String toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder(UTF8_BOM);
        appendRow(csv, headers);
        for (List<String> row : rows) {
            appendRow(csv, row);
        }
        return csv.toString();
    }

    /**
     * 单个单元格处理：公式注入中和 + CSV 转义。
     *
     * @param value 原始值（可为 {@code null}）
     * @return 可直接拼进 CSV 的单元格
     */
    public static String escapeCell(String value) {
        String neutralized = neutralizeFormula(value);
        boolean needsQuote = neutralized.indexOf(',') >= 0
            || neutralized.indexOf('"') >= 0
            || neutralized.indexOf('\n') >= 0
            || neutralized.indexOf('\r') >= 0;
        if (!needsQuote) {
            return neutralized;
        }
        return '"' + neutralized.replace("\"", "\"\"") + '"';
    }

    /**
     * 空值安全的导出时间格式化（设备/告警时间列统一形态）。
     *
     * @param value 时间（可空）
     * @return {@code yyyy-MM-dd HH:mm:ss}；{@code null} 返回空串
     */
    public static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME_FORMATTER.format(value);
    }

    private static void appendRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCell(cells.get(i)));
        }
        csv.append("\r\n");
    }

    /** 危险首字符（{@code = + - @ TAB CR}）前缀单引号；其余原样。 */
    private static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        for (char prefix : FORMULA_PREFIXES) {
            if (first == prefix) {
                return "'" + value;
            }
        }
        return value;
    }
}
