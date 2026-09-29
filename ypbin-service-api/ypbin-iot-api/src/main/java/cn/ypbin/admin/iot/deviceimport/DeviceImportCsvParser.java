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

import cn.ypbin.admin.iot.deviceimport.CsvLineSplitter.CsvFormatException;
import cn.ypbin.admin.iot.enums.DeviceImportErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备批量导入的 CSV 解析器（**纯函数式**：不吃 Spring 上下文、不碰数据库，可直接单测）。
 *
 * <p><b>分层契约</b>（谁负责什么，避免重复或漏掉校验）：</p>
 * <ol>
 *   <li><b>文件级</b>（本类）：编码、大小、行数、表头契约。任一不满足 ⇒ 整批拒绝
 *       （{@link DeviceImportParseResult#fatal}）——这类问题用户改一处就能重传，
 *       逐行报错反而是噪音；</li>
 *   <li><b>行级（语法）</b>（本类）：引号未闭合、列数不匹配、单行/单列超长。
 *       **单行失败不影响其它行**，该行带错误码进入明细；</li>
 *   <li><b>行级（业务）</b>（服务层）：设备编码重复、产品是否已发布、分组是否存在……
 *       这些需要查库，放这里会让解析器不可单测。</li>
 * </ol>
 *
 * <p><b>容错点（都是实测过用户真会踩的）</b>：</p>
 * <ul>
 *   <li><b>UTF-8 BOM</b>：Excel「另存为 CSV UTF-8」会写 {@code EF BB BF}。不剥掉的话
 *       第一列表头会变成 {@code \uFEFFdeviceCode} ⇒ 用户明明是从我们模板导出的，
 *       却被告知「缺少必填列 deviceCode」。因此 BOM 必须先剥；</li>
 *   <li><b>CRLF</b>：Windows 换行；按 {@code \r?\n} 切行并去掉行尾 {@code \r}；</li>
 *   <li><b>说明行</b>：以 {@code #} 开头的行是模板自带说明，跳过（不计入数据行）；</li>
 *   <li><b>空行</b>：整行空白跳过（Excel 常在末尾留空行），**不计入失败行**——
 *       否则用户会看到「失败 1 行」却找不到是哪一行；</li>
 *   <li><b>大小写与空白</b>：表头名大小写不敏感、去首尾空白（Excel 会自作主张）。</li>
 * </ul>
 *
 * <p><b>编码判定为什么用严格解码而不是 {@code new String(bytes, UTF_8)}</b>：
 * 后者遇到非法字节会**静默替换成 U+FFFD**，于是用户传了 GBK 文件时会看到一堆
 * 「设备编码格式非法」——真正的病因（编码不对）被掩盖。严格解码失败即报
 * {@link DeviceImportErrorCode#FILE_NOT_UTF8}，一句话说清。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class DeviceImportCsvParser {

    private static final Logger log = LoggerFactory.getLogger(DeviceImportCsvParser.class);

    /** UTF-8 BOM（Excel 的「CSV UTF-8」会写它）。 */
    private static final char BOM = '\uFEFF';

    /**
     * 解析上传的 CSV 字节。
     *
     * @param content 文件原始字节（可能为 null/空）
     * @return 解析结果（**绝不返回 null**；文件级错误在 {@code fatalError} 里）
     */
    public DeviceImportParseResult parse(byte[] content) {
        if (content == null || content.length == 0) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_EMPTY, null);
        }
        if (content.length > DeviceImportLimits.MAX_FILE_BYTES) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_TOO_LARGE,
                actualSizeDetail(content.length));
        }
        String text;
        try {
            text = decodeStrictUtf8(content);
        } catch (CharacterCodingException ex) {
            // 记录完整堆栈（不吞）：解码失败的具体位置对排障有价值
            log.warn("[iot] 批量导入文件不是合法 UTF-8，已拒绝：bytes={}", content.length, ex);
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_NOT_UTF8, null);
        }
        return parseText(text);
    }

    /**
     * 解析已解码的文本（单测入口；上传路径用 {@link #parse(byte[])}）。
     *
     * @param text 文件文本
     * @return 解析结果
     */
    public DeviceImportParseResult parseText(String text) {
        if (text == null || text.isEmpty()) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_EMPTY, null);
        }
        String normalized = stripBom(text);
        List<String> lines = splitLines(normalized);
        if (lines.isEmpty()) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_EMPTY, null);
        }

        // 找表头：跳过开头的说明行/空行（模板第 1 行就是表头，但用户可能把说明留在上面）
        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith(DeviceImportLimits.TEMPLATE_NOTE_PREFIX)) {
                continue;
            }
            headerIndex = i;
            break;
        }
        if (headerIndex < 0) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_EMPTY, "没有找到表头行");
        }

        HeaderResolution header = resolveHeader(lines.get(headerIndex));
        if (header.error() != null) {
            return DeviceImportParseResult.fatal(header.error(), header.detail());
        }

        List<DeviceImportParseResult.ParsedRow> rows = new ArrayList<>();
        int rowNo = 0;
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(DeviceImportLimits.TEMPLATE_NOTE_PREFIX)) {
                // 空行/说明行：既不是数据也不是失败（保证「失败行数 = 用户能数出来的行数」）
                continue;
            }
            rowNo++;
            if (rowNo > DeviceImportLimits.MAX_ROWS) {
                // 超行数是**文件级**拒绝：只收前 N 行会让用户以为「后面的都进去了」
                return DeviceImportParseResult.fatal(DeviceImportErrorCode.TOO_MANY_ROWS,
                    "最多 " + DeviceImportLimits.MAX_ROWS + " 行");
            }
            rows.add(parseRow(rowNo, raw, header.columns()));
        }

        if (rows.isEmpty()) {
            return DeviceImportParseResult.fatal(DeviceImportErrorCode.FILE_EMPTY, "没有数据行");
        }
        return DeviceImportParseResult.ok(header.columns(), rows);
    }

    /**
     * 模板内容（表头 + 说明行 + 示例行）。
     *
     * <p>带 BOM 输出：Excel 双击打开时没有 BOM 的 UTF-8 CSV 会被按 GBK 解读而出现乱码，
     * 用户第一眼就以为模板坏了。带 BOM 对浏览器与 Excel 都是正确的中文显示。</p>
     *
     * @return 模板 CSV 文本（含 BOM 与 CRLF，对齐 Excel 习惯）
     */
    public String buildTemplate() {
        return BOM + DeviceImportColumn.headerLine() + "\r\n"
            + DeviceImportColumn.noteLine() + "\r\n"
            + DeviceImportColumn.exampleLine() + "\r\n";
    }

    /**
     * 生成失败行 CSV（表头与模板一致 + 错误码/错误信息两列）。
     *
     * <p>刻意**追加**而不是替换列：用户拿到这份文件后要能一眼看出「哪一列的值有问题」，
     * 并且**改完直接原样重传**（追加的列在上传时会被当作无法识别的列——所以重传前要先删掉
     * 这两列，这一点写在文件首行的说明里，同样不需要用户读文档）。</p>
     *
     * @param rows 失败行的原始内容与错误信息（顺序即行号顺序）
     * @return 失败行 CSV 文本（含 BOM）
     */
    public String buildFailedCsv(List<FailedRow> rows) {
        // 说明行以 # 开头 ⇒ 用户即使忘了删这两列，上传时错误码列会被判为「无法识别的列」并明确报出
        StringBuilder builder = new StringBuilder(BOM
            + DeviceImportColumn.headerLine() + "," + FailedRow.COLUMN_ERROR_CODE + ","
            + FailedRow.COLUMN_ERROR_MESSAGE + "\r\n"
            + "# 从本文件删除最后两列（" + FailedRow.COLUMN_ERROR_CODE + " / "
            + FailedRow.COLUMN_ERROR_MESSAGE + "）后即可原样重新上传；只保留需要重传的行。\r\n");
        for (FailedRow row : rows) {
            builder.append(escape(row.rawLine())).append(',')
                .append(escape(row.errorCode())).append(',')
                .append(escape(row.errorMessage())).append("\r\n");
        }
        return builder.toString();
    }

    /**
     * 失败行 CSV 的一行。
     *
     * @param rawLine      原始行内容（原样回吐）
     * @param errorCode    错误码
     * @param errorMessage 面向用户的错误信息
     * @author wenbin
     * @since 2026-09-30
     */
    public record FailedRow(String rawLine, String errorCode, String errorMessage) {

        /** 失败行 CSV 追加的错误码列名。 */
        public static final String COLUMN_ERROR_CODE = "errorCode";

        /** 失败行 CSV 追加的错误信息列名。 */
        public static final String COLUMN_ERROR_MESSAGE = "errorMessage";
    }

    /**
     * 解析表头（去重、必填列齐全、无未知列）。
     *
     * @param headerLine 表头行文本
     * @return 解析结果（列顺序 + 可能的错误）
     */
    private HeaderResolution resolveHeader(String headerLine) {
        List<String> rawHeaders;
        try {
            rawHeaders = CsvLineSplitter.split(headerLine);
        } catch (CsvFormatException ex) {
            return new HeaderResolution(List.of(), DeviceImportErrorCode.HEADER_MISSING_COLUMN,
                "表头行本身无法解析（" + ex.getMessage() + "）");
        }
        List<DeviceImportColumn> columns = new ArrayList<>();
        Set<DeviceImportColumn> seen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String raw : rawHeaders) {
            String normalized = raw.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            DeviceImportColumn column = DeviceImportColumn.parse(normalized);
            if (column == null) {
                unknown.add(normalized);
                continue;
            }
            if (!seen.add(column)) {
                duplicated.add(normalized);
                continue;
            }
            columns.add(column);
        }
        if (!unknown.isEmpty()) {
            // 未知列一律拒绝而不是忽略：用户按旧模板多填了一列时，静默忽略会让「我明明填了」
            // 变成一场无法自证的争论（这也是重传失败行 CSV 时忘记删错误码列的护栏）
            return new HeaderResolution(List.of(), DeviceImportErrorCode.HEADER_UNKNOWN_COLUMN,
                String.join("/", unknown));
        }
        if (!duplicated.isEmpty()) {
            return new HeaderResolution(List.of(), DeviceImportErrorCode.HEADER_DUPLICATE_COLUMN,
                String.join("/", duplicated));
        }
        List<String> missing = new ArrayList<>();
        for (DeviceImportColumn column : DeviceImportColumn.values()) {
            if (column.isRequired() && !seen.contains(column)) {
                missing.add(column.getHeader());
            }
        }
        if (!missing.isEmpty()) {
            return new HeaderResolution(List.of(), DeviceImportErrorCode.HEADER_MISSING_COLUMN,
                String.join("/", missing));
        }
        return new HeaderResolution(List.copyOf(columns), null, null);
    }

    /**
     * 解析单行数据（语法层；业务校验在服务层）。
     *
     * @param rowNo    行号
     * @param rawLine  原始行
     * @param columns  表头列顺序
     * @return 行结果
     */
    private DeviceImportParseResult.ParsedRow parseRow(int rowNo, String rawLine, List<DeviceImportColumn> columns) {
        if (rawLine.length() > DeviceImportLimits.MAX_LINE_CHARS) {
            return DeviceImportParseResult.ParsedRow.invalid(rowNo, truncate(rawLine), DeviceImportErrorCode.VALUE_TOO_LONG,
                "本行 " + rawLine.length() + " 字符，超过上限 " + DeviceImportLimits.MAX_LINE_CHARS);
        }
        List<String> fields;
        try {
            fields = CsvLineSplitter.split(rawLine);
        } catch (CsvFormatException ex) {
            return DeviceImportParseResult.ParsedRow.invalid(rowNo, rawLine, DeviceImportErrorCode.LINE_SYNTAX_INVALID,
                ex.getMessage());
        }
        // 末尾空字段容忍：`a,b,` 与 `a,b` 都按两列处理（Excel 末列留空时会多补一个逗号）。
        // 但中间缺列必须报错——否则「设备名称填到了 endpoint 列」会被静默接受。
        int effective = fields.size();
        while (effective > columns.size() && fields.get(effective - 1).trim().isEmpty()) {
            effective--;
        }
        if (effective != columns.size()) {
            return DeviceImportParseResult.ParsedRow.invalid(rowNo, rawLine, DeviceImportErrorCode.COLUMN_COUNT_MISMATCH,
                "本行 " + effective + " 列，表头 " + columns.size() + " 列");
        }
        Map<DeviceImportColumn, String> values = new EnumMap<>(DeviceImportColumn.class);
        for (int i = 0; i < columns.size(); i++) {
            values.put(columns.get(i), fields.get(i));
        }
        return DeviceImportParseResult.ParsedRow.of(rowNo, rawLine, Map.copyOf(values));
    }

    /**
     * 严格 UTF-8 解码（非法字节直接失败，不静默替换成 U+FFFD）。
     *
     * @param content 原始字节
     * @return 文本
     * @throws CharacterCodingException 不是合法 UTF-8
     */
    private String decodeStrictUtf8(byte[] content) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(content)).toString();
    }

    /**
     * 按 CRLF/LF/CR 切行并剥掉行尾 {@code \r}。
     *
     * @param text 文本
     * @return 行列表
     */
    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                lines.add(stripTrailingCr(text.substring(start, i)));
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            lines.add(stripTrailingCr(text.substring(start)));
        }
        return lines;
    }

    private String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * 剥离 UTF-8 BOM（Excel「CSV UTF-8」的产物）。
     *
     * @param text 文本
     * @return 剥离后的文本
     */
    private String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /**
     * CSV 字段转义（含分隔符/引号/换行时用引号包裹，引号翻倍）。
     *
     * @param value 取值
     * @return 转义后的字段
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * 截断超长行（只用于**留痕**；错误信息里给出真实长度）。
     *
     * @param line 原始行
     * @return 截断后的行
     */
    private String truncate(String line) {
        return line.length() <= DeviceImportLimits.MAX_LINE_CHARS
            ? line
            : line.substring(0, DeviceImportLimits.MAX_LINE_CHARS);
    }

    /**
     * 文件大小超限的具体说明。
     *
     * @param actualBytes 实际字节数
     * @return 说明
     */
    private String actualSizeDetail(long actualBytes) {
        return "文件 " + (actualBytes / 1024) + " KB，上限 "
            + (DeviceImportLimits.MAX_FILE_BYTES / 1024 / 1024) + " MB";
    }

    /**
     * 表头解析结果。
     *
     * @param columns 列顺序
     * @param error   错误码（为 null 表示成功）
     * @param detail  错误详情
     * @author wenbin
     * @since 2026-09-30
     */
    private record HeaderResolution(List<DeviceImportColumn> columns,
                                    DeviceImportErrorCode error,
                                    String detail) {
    }
}
