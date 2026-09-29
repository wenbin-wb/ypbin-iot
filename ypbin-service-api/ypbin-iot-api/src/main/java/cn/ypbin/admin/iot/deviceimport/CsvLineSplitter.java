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
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 单行切分器（RFC 4180 的核心子集：引号包裹 + 双引号转义 + 字段内逗号/换行）。
 *
 * <p><b>为什么不用现成库</b>：仓内没有 CSV 依赖（不引入一条只为 200 行逻辑的供应链）；
 * 而「逗号分隔 + 引号转义」的语义是本能力**对外契约**的一部分（用户从 Excel 另存出来的
 * 文件必须能被正确解析），所以它必须有自己的、可被单测钉死的实现。</p>
 *
 * <p><b>支持的形态</b>：</p>
 * <ul>
 *   <li>{@code a,b,c} —— 普通行；</li>
 *   <li>{@code "a,b",c} —— 引号内的逗号不是分隔符；</li>
 *   <li>{@code "他说""好""",c} —— 引号内两个连续引号表示一个引号字符；</li>
 *   <li>引号包裹的字段可以跨行（本类按「单条记录」处理，跨行由调用方拼好后再传入）；</li>
 *   <li>字段首尾空白：**不**自动 trim（写出者应自行处理），但引号外的裸空白在解析时保留——
 *       取值是否合法由校验层判断，解析层不替用户做决定；</li>
 *   <li>BOM：调用方负责剥离，本类不处理。</li>
 * </ul>
 *
 * <p><b>非法形态</b>：引号未闭合、引号后紧跟非分隔符字符（如 {@code "a"b}）。
 * 这两类一律抛 {@link CsvFormatException} ← 由调用方转成该行的
 * {@link DeviceImportErrorCode#LINE_SYNTAX_INVALID}，**单行失败不影响其它行**。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class CsvLineSplitter {

    /** 字段分隔符（本能力只支持逗号；分号/制表符不在契约内，传了就按「一列」处理）。 */
    private static final char DELIMITER = ',';

    /** 引号字符。 */
    private static final char QUOTE = '"';

    private CsvLineSplitter() {
    }

    /**
     * 切分一条 CSV 记录。
     *
     * @param line 单条记录的文本（不含行尾换行符；调用方保证已剥离 BOM）
     * @return 字段列表（**绝不返回 null**；空行返回只含一个空串的列表）
     * @throws CsvFormatException 引号未闭合或引号后紧跟非分隔符字符
     */
    public static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            fields.add("");
            return fields;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == QUOTE) {
                    // 连续两个引号 = 一个转义引号；单个引号 = 引号段结束
                    if (i + 1 < n && line.charAt(i + 1) == QUOTE) {
                        current.append(QUOTE);
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                current.append(ch);
                i++;
                continue;
            }
            if (ch == QUOTE) {
                if (current.length() > 0 || fieldWasQuoted) {
                    // 引号只能出现在字段开头（或紧接前面的引号段）："a"b 这种是非法输入，
                    // 静默按字面量处理会让用户以为「导入成功了但值不对」
                    throw new CsvFormatException("引号只能出现在字段开头");
                }
                inQuotes = true;
                fieldWasQuoted = true;
                i++;
                continue;
            }
            if (ch == DELIMITER) {
                fields.add(current.toString());
                current.setLength(0);
                fieldWasQuoted = false;
                i++;
                continue;
            }
            if (fieldWasQuoted) {
                // 引号段结束后只允许分隔符/行尾，其它字符是非法输入（同上，不静默吞掉）
                throw new CsvFormatException("引号段结束后出现多余字符");
            }
            current.append(ch);
            i++;
        }
        if (inQuotes) {
            throw new CsvFormatException("引号未闭合");
        }
        fields.add(current.toString());
        return fields;
    }

    /**
     * CSV 语法非法（单行级别；由调用方转成业务错误码，不影响其它行）。
     *
     * @author wenbin
     * @since 2026-09-30
     */
    public static class CsvFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造。
         *
         * @param message 具体原因（会拼进该行的错误信息给用户看）
         */
        public CsvFormatException(String message) {
            super(message);
        }
    }
}
