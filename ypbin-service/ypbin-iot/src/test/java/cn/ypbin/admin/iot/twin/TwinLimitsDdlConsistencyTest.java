/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code TwinLimits} 的**长度常量必须与 DDL 列宽一致**（工单 #309 遗留项的兜底）。
 *
 * <p>为什么要这条测试：DTO 的 {@code @Size(message = "...64")} 文案、DDL 的
 * {@code VARCHAR(64)}、以及 {@code TwinLimits.MAX_IDENTIFIER_LENGTH} 三者是**同一件事的
 * 三个副本**。注解 message 必须是编译期常量、无法引用 {@code int} 常量，
 * 所以只要有人改了 DDL 列宽却忘了改常量（或反之），约束就会静默失效 ——
 * 表现为「前端放行、SQL 报 Data too long」或「前端误拦」。
 * 这条测试从 {@code 007-iot-data.sql} 里读真实列宽来比对，改任一处都会红。</p>
 */
class TwinLimitsDdlConsistencyTest {

    /** 建表脚本路径（相对模块工作目录，即 ypbin-service/ypbin-iot）。 */
    private static final Path SCHEMA_SQL = Path.of("../..", "deploy", "sql", "007-iot-data.sql");

    @Test
    @DisplayName("服务/属性标识长度常量与 DDL 列宽一致")
    void identifierLengthMatchesDdl() throws IOException {
        String ddl = readSchema();
        // 在 iot_twin_point_binding 的建表段里找两个标识列
        int bindingTable = ddl.indexOf("CREATE TABLE iot_twin_point_binding");
        assertThat(bindingTable).as("未找到 iot_twin_point_binding 建表语句").isGreaterThan(-1);
        String bindingDdl = ddl.substring(bindingTable,
            Math.min(ddl.length(), bindingTable + 4000));

        assertThat(columnLength(bindingDdl, "service_identifier"))
            .as("service_identifier 列宽应与 TwinLimits.MAX_IDENTIFIER_LENGTH 一致")
            .isEqualTo(TwinLimits.MAX_IDENTIFIER_LENGTH);
        assertThat(columnLength(bindingDdl, "property_identifier"))
            .as("property_identifier 列宽应与 TwinLimits.MAX_IDENTIFIER_LENGTH 一致")
            .isEqualTo(TwinLimits.MAX_IDENTIFIER_LENGTH);
    }

    @Test
    @DisplayName("其余长度常量与 DDL 列宽一致")
    void otherLengthsMatchDdl() throws IOException {
        String ddl = readSchema();

        assertThat(columnLength(ddl, "scene_code")).isEqualTo(TwinLimits.MAX_SCENE_CODE_LENGTH);
        assertThat(columnLength(ddl, "scene_name")).isEqualTo(TwinLimits.MAX_SCENE_NAME_LENGTH);
        assertThat(columnLength(ddl, "asset_name")).isEqualTo(TwinLimits.MAX_ASSET_NAME_LENGTH);
        assertThat(columnLength(ddl, "node_name")).isEqualTo(TwinLimits.MAX_NODE_NAME_LENGTH);
        assertThat(columnLength(ddl, "unit_override")).isEqualTo(TwinLimits.MAX_UNIT_LENGTH);
    }

    /**
     * 从 DDL 文本里取出某列的 {@code VARCHAR(n)} 宽度。
     *
     * @param ddl  建表语句片段
     * @param column 列名
     * @return 列宽；未找到或非 VARCHAR 时返回 -1
     */
    private static int columnLength(String ddl, String column) {
        Matcher matcher = Pattern.compile(
            "\\b" + column + "\\s+VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)").matcher(ddl);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * 读取建表脚本。
     *
     * @return SQL 全文
     * @throws IOException 读取失败
     */
    private static String readSchema() throws IOException {
        assertThat(Files.exists(SCHEMA_SQL))
            .as("建表脚本不存在：%s（测试工作目录应在 ypbin-service/ypbin-iot）",
                SCHEMA_SQL.toAbsolutePath().normalize())
            .isTrue();
        return Files.readString(SCHEMA_SQL, StandardCharsets.UTF_8);
    }
}
