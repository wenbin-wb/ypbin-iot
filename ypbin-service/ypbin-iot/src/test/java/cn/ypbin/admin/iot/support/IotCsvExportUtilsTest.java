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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 导出工具纯单测：BOM、逗号/引号/换行转义、危险首字符中和、空数据与时间格式化。
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotCsvExportUtilsTest {

    @Test
    @DisplayName("★ 空数据也只输出 BOM + 表头，不返回 null/空串")
    void emptyRowsShouldStillEmitBomAndHeader() {
        String csv = IotCsvExportUtils.toCsv(List.of("设备编码", "设备名称"), List.of());

        assertThat(csv).isEqualTo(IotCsvExportUtils.UTF8_BOM + "设备编码,设备名称\r\n");
        assertThat(csv).startsWith(IotCsvExportUtils.UTF8_BOM);
    }

    @Test
    @DisplayName("★ 逗号/引号/换行按 RFC 4180 转义（引号翻倍）")
    void cellsWithSeparatorsMustBeQuotedAndEscaped() {
        assertThat(IotCsvExportUtils.escapeCell("a,b")).as("逗号包引号").isEqualTo("\"a,b\"");
        assertThat(IotCsvExportUtils.escapeCell("he said \"hi\""))
            .as("普通引号：包引号 + 引号翻倍").isEqualTo("\"he said \"\"hi\"\"\"");
        assertThat(IotCsvExportUtils.escapeCell("line1\nline2"))
            .as("换行包引号").isEqualTo("\"line1\nline2\"");
        assertThat(IotCsvExportUtils.escapeCell("a\"b,c"))
            .as("同时含引号与逗号").isEqualTo("\"a\"\"b,c\"");
        assertThat(IotCsvExportUtils.escapeCell("普通值")).isEqualTo("普通值");
    }

    @Test
    @DisplayName("★ 公式注入中和：= + - @ TAB CR 开头一律前缀单引号")
    void dangerousFirstCharactersMustBeNeutralized() {
        assertThat(IotCsvExportUtils.escapeCell("=1+1")).isEqualTo("'=1+1");
        assertThat(IotCsvExportUtils.escapeCell("+SUM(A1)")).isEqualTo("'+SUM(A1)");
        assertThat(IotCsvExportUtils.escapeCell("-1")).as("负数也按危险首字符处理").isEqualTo("'-1");
        assertThat(IotCsvExportUtils.escapeCell("@cmd")).isEqualTo("'@cmd");
        assertThat(IotCsvExportUtils.escapeCell("\tX")).isEqualTo("'\tX");
        assertThat(IotCsvExportUtils.escapeCell("\rX")).as("CR 中和后仍需包引号").isEqualTo("\"'\rX\"");
        assertThat(IotCsvExportUtils.escapeCell("'=已是文本")).as("已有前缀不重复处理（首字符是单引号）")
            .isEqualTo("'=已是文本");
    }

    @Test
    @DisplayName("null 与空串输出空单元格，不抛异常（两字段仍保留分隔符）")
    void nullAndEmptyCellsMustBeBlank() {
        assertThat(IotCsvExportUtils.escapeCell(null)).isEmpty();
        assertThat(IotCsvExportUtils.escapeCell("")).isEmpty();
        assertThat(IotCsvExportUtils.toCsv(List.of("A", "B"), List.of(Arrays.asList(null, ""))))
            .isEqualTo(IotCsvExportUtils.UTF8_BOM + "A,B\r\n,\r\n");
    }

    @Test
    @DisplayName("时间列空值安全；正常值固定 yyyy-MM-dd HH:mm:ss")
    void dateTimeMustBeFormattedOrBlank() {
        assertThat(IotCsvExportUtils.formatDateTime(null)).isEmpty();
        assertThat(IotCsvExportUtils.formatDateTime(LocalDateTime.of(2026, 10, 15, 1, 2, 3)))
            .isEqualTo("2026-10-15 01:02:03");
    }
}
