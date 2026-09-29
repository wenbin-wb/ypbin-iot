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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.enums.DeviceImportErrorCode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 解析器用例（任务的边界面：合法/缺列/表头错/BOM/引号转义/超行数/超大小）。
 *
 * <p>解析器是**纯函数**（不吃上下文、不碰库），所以这些用例可以直接跑、且每条都能精确
 * 定位到「哪一类输入被怎么处理」。它们是本能力对外契约的守门人：
 * 用户从 Excel 另存一份 CSV 能不能用，就看这一组。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class DeviceImportCsvParserTest {

    private final DeviceImportCsvParser parser = new DeviceImportCsvParser();

    private static final String HEADER = DeviceImportColumn.headerLine();

    /**
     * 用给定数据行拼一份合法 CSV（表头 + 行）。
     *
     * @param dataLines 数据行
     * @return CSV 文本
     */
    private static String csv(String... dataLines) {
        return HEADER + "\n" + String.join("\n", dataLines) + "\n";
    }

    /** 一行合法的数据。 */
    private static final String VALID_ROW = "GW-001,一号网关,tcp,tcp://10.0.0.1:502,,,,,";

    @Test
    @DisplayName("合法 CSV：表头解析出全部列，数据行逐列对号入座")
    void validCsvShouldParseAllColumns() {
        DeviceImportParseResult result = parser.parseText(csv(VALID_ROW));

        assertThat(result.isFatal()).isFalse();
        assertThat(result.headerColumns()).containsExactly(DeviceImportColumn.values());
        assertThat(result.rows()).hasSize(1);
        DeviceImportParseResult.ParsedRow row = result.rows().get(0);
        assertThat(row.rowNo()).isEqualTo(1);
        assertThat(row.errorCode()).isNull();
        assertThat(row.value(DeviceImportColumn.DEVICE_CODE)).isEqualTo("GW-001");
        assertThat(row.value(DeviceImportColumn.DEVICE_NAME)).isEqualTo("一号网关");
        assertThat(row.value(DeviceImportColumn.PROTOCOL)).isEqualTo("tcp");
        assertThat(row.value(DeviceImportColumn.ENDPOINT)).isEqualTo("tcp://10.0.0.1:502");
        assertThat(row.value(DeviceImportColumn.PRODUCT_ID)).isEmpty();
    }

    @Test
    @DisplayName("★ UTF-8 BOM 容错：Excel「另存为 CSV UTF-8」的文件必须能被解析（否则用户从模板导出的都不能用）")
    void bomMustBeTolerated() {
        // 不带 BOM 容错时，第一列表头是 "\uFEFFdeviceCode" ⇒ 用户会被告知「缺少必填列 deviceCode」，
        // 而他明明用的就是从我们模板导出的文件 —— 这是最典型的「工具自己坑用户」形态
        byte[] withBom = ("\uFEFF" + csv(VALID_ROW)).getBytes(StandardCharsets.UTF_8);
        assertThat(withBom[0]).isEqualTo((byte) 0xEF);

        DeviceImportParseResult result = parser.parse(withBom);

        assertThat(result.isFatal())
            .as("带 BOM 的文件必须被正常解析（BOM 不是表头的一部分）")
            .isFalse();
        assertThat(result.headerColumns()).contains(DeviceImportColumn.DEVICE_CODE);
        assertThat(result.rows()).hasSize(1);
    }

    @Test
    @DisplayName("★ 引号转义：字段内的逗号、引号、换行不能把一行拆坏")
    void quotesMustBeEscaped() {
        // 备注里带逗号与引号（RFC 4180：引号内的 " 用 "" 表示）
        String row = "GW-002,\"网关,二号\",tcp,tcp://10.0.0.2:502,,,,,\"他说\"\"好\"\"\"";
        DeviceImportParseResult result = parser.parseText(csv(row));

        assertThat(result.isFatal()).isFalse();
        DeviceImportParseResult.ParsedRow parsed = result.rows().get(0);
        assertThat(parsed.errorCode()).isNull();
        assertThat(parsed.value(DeviceImportColumn.DEVICE_NAME))
            .as("引号包裹的字段里的逗号不是分隔符").isEqualTo("网关,二号");
        assertThat(parsed.value(DeviceImportColumn.REMARK))
            .as("两个连续引号表示一个引号字符").isEqualTo("他说\"好\"");
        assertThat(parsed.value(DeviceImportColumn.ENDPOINT)).isEqualTo("tcp://10.0.0.2:502");
    }

    @Test
    @DisplayName("★ 引号未闭合是**行级**失败：该行报错，其它行照常解析（单行失败不影响其它行）")
    void unclosedQuoteMustFailOnlyThatRow() {
        String broken = "GW-003,\"没关引号的名称,tcp,tcp://10.0.0.3:502,,,,,";
        String ok = "GW-004,正常设备,tcp,tcp://10.0.0.4:502,,,,,";
        DeviceImportParseResult result = parser.parseText(csv(broken, ok));

        assertThat(result.isFatal()).as("行级语法错不得升级成整批拒绝").isFalse();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).errorCode())
            .isEqualTo(DeviceImportErrorCode.LINE_SYNTAX_INVALID);
        assertThat(result.rows().get(1).errorCode()).isNull();
        assertThat(result.rows().get(1).value(DeviceImportColumn.DEVICE_NAME)).isEqualTo("正常设备");
    }

    @Test
    @DisplayName("★ 缺必填列 ⇒ 文件级拒绝，错误码指名道姓（不能只说「格式错误」）")
    void missingRequiredColumnMustBeFatal() {
        // 少了 deviceName 列
        String header = "deviceCode,protocol,endpoint,productId,productVersion,groupIds,status,remark";
        DeviceImportParseResult result = parser.parseText(header + "\nGW-005,tcp,tcp://h:1,,,,,\n");

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.HEADER_MISSING_COLUMN);
        assertThat(result.fatalDetail()).contains("deviceName");
        assertThat(result.rows()).isEmpty();
    }

    @Test
    @DisplayName("★ 表头有无法识别的列 ⇒ 文件级拒绝（用户按旧模板多填一列时不能静默忽略）")
    void unknownColumnMustBeFatal() {
        DeviceImportParseResult result = parser.parseText(
            HEADER + ",whatever\n" + VALID_ROW + ",x\n");

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.HEADER_UNKNOWN_COLUMN);
        assertThat(result.fatalDetail()).contains("whatever");
    }

    @Test
    @DisplayName("表头重复列 ⇒ 文件级拒绝（重复列意味着两列都填了却只有一列生效）")
    void duplicateColumnMustBeFatal() {
        DeviceImportParseResult result = parser.parseText(
            HEADER + ",deviceCode\n" + VALID_ROW + ",GW-999\n");

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.HEADER_DUPLICATE_COLUMN);
    }

    @Test
    @DisplayName("表头大小写与空白容错（Excel/手改的产物）")
    void headerShouldBeCaseAndSpaceInsensitive() {
        String header = " DeviceCode , DEVICENAME ,Protocol,endpoint";
        DeviceImportParseResult result = parser.parseText(header + "\nGW-006,x,tcp,tcp://h:1\n");

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).value(DeviceImportColumn.DEVICE_CODE)).isEqualTo("GW-006");
    }

    @Test
    @DisplayName("★ 允许缺**选填**列：用户只填必填四列也能跑通（模板不是强制形状）")
    void optionalColumnsMayBeAbsent() {
        DeviceImportParseResult result = parser.parseText(
            "deviceCode,deviceName,protocol,endpoint\nGW-007,精简设备,mqtt,mqtt://h:1883\n");

        assertThat(result.isFatal()).isFalse();
        assertThat(result.headerColumns()).containsExactly(DeviceImportColumn.DEVICE_CODE,
            DeviceImportColumn.DEVICE_NAME, DeviceImportColumn.PROTOCOL, DeviceImportColumn.ENDPOINT);
        assertThat(result.rows().get(0).isBlank(DeviceImportColumn.REMARK)).isTrue();
    }

    @Test
    @DisplayName("★ 列数与表头不符 ⇒ 该行失败（中间缺列会让「名称填到端点列」被静默接受）")
    void columnCountMismatchMustFailThatRow() {
        // 只给了 3 列（缺 endpoint）
        DeviceImportParseResult result = parser.parseText(csv("GW-008,缺列设备,tcp"));

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows().get(0).errorCode())
            .isEqualTo(DeviceImportErrorCode.COLUMN_COUNT_MISMATCH);
    }

    @Test
    @DisplayName("行尾多余的空逗号被容忍（Excel 末列留空的常见形态）")
    void trailingEmptyFieldsAreTolerated() {
        // 9 列表头，行尾多给两个空字段
        DeviceImportParseResult result = parser.parseText(csv(VALID_ROW + ",,"));

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows().get(0).errorCode()).isNull();
    }

    @Test
    @DisplayName("★ 超行数 ⇒ 文件级拒绝（只收前 N 行会让用户以为后面的都进去了）")
    void tooManyRowsMustBeFatal() {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < DeviceImportLimits.MAX_ROWS + 1; i++) {
            builder.append("GW-").append(i).append(",设备,tcp,tcp://h:1,,,,,\n");
        }
        DeviceImportParseResult result = parser.parseText(builder.toString());

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.TOO_MANY_ROWS);
        assertThat(result.rows()).as("整批拒绝时不得留下半截明细").isEmpty();
    }

    @Test
    @DisplayName("恰好等于行数上限 ⇒ 接受（边界值不能少收一行）")
    void exactlyMaxRowsIsAccepted() {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < DeviceImportLimits.MAX_ROWS; i++) {
            builder.append("GW-").append(i).append(",设备,tcp,tcp://h:1,,,,,\n");
        }
        DeviceImportParseResult result = parser.parseText(builder.toString());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows()).hasSize(DeviceImportLimits.MAX_ROWS);
    }

    @Test
    @DisplayName("★ 超文件大小 ⇒ 文件级拒绝（2MB 上限，与阿里云量级对齐）")
    void tooLargeFileMustBeFatal() {
        byte[] huge = new byte[(int) DeviceImportLimits.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 'a');

        DeviceImportParseResult result = parser.parse(huge);

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("★ 非 UTF-8 字节 ⇒ 明确报「不是 UTF-8」而不是拿 U+FFFD 硬解（否则用户看到的是满屏「编码非法」）")
    void nonUtf8MustBeReportedAsEncodingProblem() {
        // GBK 的「网关」= 0xCD 0xF8 0xB9 0xD8，不是合法 UTF-8 序列
        byte[] gbk = "deviceCode,deviceName,protocol,endpoint\nGW-1,网关,tcp,tcp://h:1\n"
            .getBytes(Charset.forName("GBK"));

        DeviceImportParseResult result = parser.parse(gbk);

        assertThat(result.isFatal()).isTrue();
        assertThat(result.fatalError()).isEqualTo(DeviceImportErrorCode.FILE_NOT_UTF8);
    }

    @Test
    @DisplayName("★ 超长单行 ⇒ 该行失败但**不整批崩**（rawLine 落库列是有限宽度，这是必须显式处理的一处）")
    void overlongLineMustFailOnlyThatRow() {
        String longRemark = "x".repeat(DeviceImportLimits.MAX_LINE_CHARS + 10);
        String overlong = "GW-009,超长备注,tcp,tcp://h:1,,,,," + longRemark;
        DeviceImportParseResult result = parser.parseText(csv(overlong, "GW-010,正常,tcp,tcp://h:1,,,,,"));

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).errorCode()).isEqualTo(DeviceImportErrorCode.VALUE_TOO_LONG);
        assertThat(result.rows().get(1).errorCode()).isNull();
        assertThat(result.rows().get(0).rawLine().length())
            .as("留痕内容必须被截断到上限内（否则落库会失败）")
            .isLessThanOrEqualTo(DeviceImportLimits.MAX_LINE_CHARS);
    }

    @Test
    @DisplayName("空行与说明行被跳过，且**不计入**行号（否则用户看到「失败 3 行」却数不出来）")
    void blankAndNoteLinesAreSkipped() {
        String text = HEADER + "\n"
            + "# 这是用户自己写的注释\n"
            + "\n"
            + VALID_ROW + "\n"
            + "\n";
        DeviceImportParseResult result = parser.parseText(text);

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).rowNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("CRLF 换行（Windows）必须被正确处理：行尾不得残留 \\r")
    void crlfMustBeHandled() {
        String text = HEADER + "\r\nGW-011,回车换行,tcp,tcp://h:1,,,,,\r\n";
        DeviceImportParseResult result = parser.parseText(text);

        assertThat(result.isFatal()).isFalse();
        assertThat(result.rows().get(0).value(DeviceImportColumn.ENDPOINT))
            .as("行尾 \\r 残留会让端点变成 'tcp://h:1\\r' ⇒ 格式校验失败，用户看不出为什么")
            .isEqualTo("tcp://h:1");
    }

    @Test
    @DisplayName("空文件 / 只有表头 ⇒ 文件级拒绝（不是「导入成功 0 台」）")
    void emptyFileMustBeFatal() {
        assertThat(parser.parse(null).fatalError()).isEqualTo(DeviceImportErrorCode.FILE_EMPTY);
        assertThat(parser.parse(new byte[0]).fatalError()).isEqualTo(DeviceImportErrorCode.FILE_EMPTY);
        assertThat(parser.parseText(HEADER + "\n").fatalError())
            .isEqualTo(DeviceImportErrorCode.FILE_EMPTY);
    }

    @Test
    @DisplayName("★ 模板本身必须能被自己的解析器读回去（否则「下载模板 → 填 → 上传」的闭环一开始就是坏的）")
    void templateMustRoundTripThroughParser() {
        String template = parser.buildTemplate();

        DeviceImportParseResult result = parser.parse(template.getBytes(StandardCharsets.UTF_8));

        assertThat(result.isFatal())
            .as("模板必须自洽：用户原样上传（甚至不改）也不该被拒")
            .isFalse();
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).errorCode()).isNull();
        assertThat(result.rows().get(0).value(DeviceImportColumn.DEVICE_CODE)).isEqualTo("GW-DEMO-001");
    }

    @Test
    @DisplayName("模板必须含说明行（以 # 开头且被解析器跳过），让用户不读文档就知道每列填什么")
    void templateMustCarryReadableNotes() {
        String template = parser.buildTemplate();

        assertThat(template).contains(DeviceImportLimits.TEMPLATE_NOTE_PREFIX);
        for (DeviceImportColumn column : DeviceImportColumn.values()) {
            assertThat(template)
                .as("模板说明行必须覆盖列 %s", column.getHeader())
                .contains(column.getHeader() + (column.isRequired() ? "(必填)" : "(选填)"));
        }
    }

    @Test
    @DisplayName("★ 失败行 CSV：原样回吐原始行 + 追加错误码/错误信息两列（用户改完即可重传）")
    void failedCsvMustBeSelfServiceable() {
        List<DeviceImportCsvParser.FailedRow> rows = List.of(
            new DeviceImportCsvParser.FailedRow("GW-1,设备,tcp,tcp://h:1,,,,,",
                DeviceImportErrorCode.DEVICE_CODE_DUPLICATED.getCode(),
                DeviceImportErrorCode.DEVICE_CODE_DUPLICATED.format("GW-1")),
            // 含逗号的原始行必须被正确转义，否则下载下来的文件会错列
            new DeviceImportCsvParser.FailedRow("GW-2,\"名称,带逗号\",tcp,tcp://h:2,,,,,",
                DeviceImportErrorCode.ENDPOINT_INVALID.getCode(),
                DeviceImportErrorCode.ENDPOINT_INVALID.format("h:2")));

        String csv = parser.buildFailedCsv(rows);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains(DeviceImportColumn.headerLine()
            + "," + DeviceImportCsvParser.FailedRow.COLUMN_ERROR_CODE
            + "," + DeviceImportCsvParser.FailedRow.COLUMN_ERROR_MESSAGE);
        assertThat(csv).contains(DeviceImportErrorCode.DEVICE_CODE_DUPLICATED.getCode());
        assertThat(csv).contains("\"GW-2,\"\"名称,带逗号\"\",tcp,tcp://h:2,,,,,\"")
            .as("含逗号的原始行必须被引号包起来（否则用户改完重传会错列）");
        // 说明行必须告诉用户「删掉最后两列就能重传」，这是「不读文档」的关键一步
        assertThat(csv).contains(DeviceImportCsvParser.FailedRow.COLUMN_ERROR_CODE)
            .contains(DeviceImportCsvParser.FailedRow.COLUMN_ERROR_MESSAGE);
    }

    @Test
    @DisplayName("失败行 CSV 为空时仍是一份合法 CSV（只有表头+说明行），不报错")
    void emptyFailedCsvIsStillValid() {
        String csv = parser.buildFailedCsv(List.of());

        assertThat(csv).contains(DeviceImportColumn.headerLine());
        assertThat(csv.lines().count()).isEqualTo(2L);
    }
}
