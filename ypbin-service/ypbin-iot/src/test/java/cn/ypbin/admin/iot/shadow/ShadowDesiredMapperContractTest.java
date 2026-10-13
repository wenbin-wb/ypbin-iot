/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 影子 desired Mapper 的**源码级契约门禁**（命令携带 desired）。
 *
 * <p>与 {@code ShadowReportedMapperContractTest} 同一动机：合并语义全在 SQL 文本里，单测只 mock Mapper
 * 咬不到；这里把「按点位合并而非整份覆盖」「desired_ts 只前进」「租户/逻辑删除」「注解是合法 XML」
 * 钉在本地，每条断言都能被一次真实变异咬住。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ShadowDesiredMapperContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path MAPPER = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/IotShadowMapper.java");

    @Test
    @DisplayName("必须按点位合并（JSON_MERGE_PATCH），不得退化成整份覆盖")
    void mergeMustBeByPropertyNotOverwrite() throws IOException {
        String sql = methodSql("mergeDesired");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("少了 JSON_MERGE_PATCH：整份覆盖会把其它未确认期望键抹掉")
            .contains("JSON_MERGE_PATCH");
        assertThat(sql).as("合并的右侧必须是本行待插入的值（VALUES(desired)），否则合了个常量")
            .contains("VALUES(desired)");
        assertThat(sql).as("期望值落回 TEXT 列要显式转型").contains("AS CHAR");
    }

    @Test
    @DisplayName("desired_ts 必须在 SQL 里只前进（乱序/重放的旧期望不得把最近期望改小）")
    void desiredTsMustBeMonotonic() throws IOException {
        String sql = methodSql("mergeDesired");

        assertThat(sql).contains(
            "GREATEST(COALESCE(iot_shadow.desired_ts, VALUES(desired_ts)), VALUES(desired_ts))");
    }

    @Test
    @DisplayName("租户与逻辑删除：插入显式带 tenant_id，命中冲突时复活软删行")
    void insertMustCarryTenantAndRevive() throws IOException {
        String sql = methodSql("mergeDesired");

        assertThat(sql).as("iot_shadow 是租户表；语句自带 tenant_id，不依赖租户插件注入")
            .contains("id, tenant_id, device_id");
        assertThat(sql).as("软删行仍占着 uk_iot_shadow，冲突时必须复位 is_deleted/status")
            .contains("status = 1, is_deleted = 0");
    }

    @Test
    @DisplayName("不得用 MySQL 行别名 AS new：JSqlParser 解析不了，租户拦截器会把整条语句打回")
    void mustNotUseRowAliasThatJsqlParserRejects() throws IOException {
        assertThat(methodSql("mergeDesired")).doesNotContain(" AS new ");
    }

    @Test
    @DisplayName("注解本身必须是合法 XML，且渲染后的 SQL 能被租户拦截器同版本 JSqlParser 解析")
    void annotationMustBeWellFormedAndParseable() throws IOException {
        String sql = methodSql("mergeDesired");
        assertThat(sql).startsWith("<script>").endsWith("</script>");
        assertThatCode(() -> new XMLLanguageDriver().createSqlSource(new Configuration(), sql, Object.class))
            .as("注解散列/未闭合会让 MyBatis 启动即解析失败，整个 iot 服务起不来")
            .doesNotThrowAnyException();

        String rendered = sql.replace("<script>", " ").replace("</script>", " ")
            .replaceAll("#\\{[^}]*}", "?").replaceAll("\\s+", " ").trim();
        try {
            CCJSqlParserUtil.parse(rendered);
        } catch (Exception ex) {
            throw new AssertionError("渲染后的 SQL 解析失败（生产里租户拦截器会先解析再执行）: "
                + rendered, ex);
        }
    }

    /** 抽取 {@code mergeDesired} 的 {@code @Insert} SQL 文本（与 ShadowReportedMapperContractTest 同做法）。 */
    private static String methodSql(String methodName) throws IOException {
        String source = Files.readString(MAPPER, StandardCharsets.UTF_8);
        int methodIndex = source.indexOf(" " + methodName + "(");
        assertThat(methodIndex).as("Mapper 里找不到方法 %s（门禁失效）", methodName).isPositive();
        int annotationIndex = source.lastIndexOf("@Insert", methodIndex);
        assertThat(annotationIndex).as("方法 %s 前找不到 @Insert（门禁失效）", methodName).isPositive();
        String block = source.substring(annotationIndex + "@Insert".length() + 1, methodIndex);
        int lastQuote = block.lastIndexOf('"');
        assertThat(lastQuote).as("注解里找不到字符串字面量（门禁失效）").isPositive();
        return stripComments(block.substring(0, lastQuote + 1).replace("+", " ").replace("\"", ""))
            .replaceAll("\\s+", " ").trim();
    }

    /** 剥离注释：门禁断言必须作用在代码上（注释里的示例否则会制造假绿/误报）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
