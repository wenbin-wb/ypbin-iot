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
import java.util.Collections;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 影子上报值 Mapper 的**源码级契约门禁**（G2）。
 *
 * <p>为什么必须是源码级：合并语义全部写在 SQL 文本里，而单测只 mock Mapper（咬不到 SQL），
 * 真库 IT 又只在 CI 跑。这里把「合并而非整体覆盖」「多行批量」「时间戳只前进」「租户/逻辑删除」
 * 钉在本地；每条断言都能被一次真实变异咬住（见各断言的理由）。</p>
 *
 * @author wenbin
 * @since 2026-09-25
 */
class ShadowReportedMapperContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path MAPPER = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/IotShadowMapper.java");

    @Test
    @DisplayName("★ 必须按点位**合并**（JSON_MERGE_PATCH），不得退化成整体覆盖")
    void mergeMustBeByPropertyNotOverwrite() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("少了 JSON_MERGE_PATCH：整份覆盖 ⇒ 并发上报的不同点位会丢更新（半影子变全量覆盖）")
            .contains("JSON_MERGE_PATCH");
        assertThat(sql).as("合并的右侧必须是本行待插入的值（VALUES(reported)），否则合了个常量")
            .contains("VALUES(reported)");
        assertThat(sql).as("报告值落回 TEXT 列要显式转型，避免驱动/版本差异下的隐式行为")
            .contains("AS CHAR");
    }

    @Test
    @DisplayName("★ 必须在一条语句里批量 upsert（多行 foreach + ON DUPLICATE KEY UPDATE）")
    void upsertMustBeSingleBatchedStatement() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("没有 foreach ⇒ 一台设备一条语句，上报链路变成 N+1").contains("foreach");
        assertThat(sql).as("没有 ON DUPLICATE KEY UPDATE ⇒ 第二次上报直接撞 uk_iot_shadow 唯一键报错")
            .contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    @DisplayName("★ report_ts 必须在 SQL 里只前进（乱序/重放不得把「最近上报」改小）")
    void reportTsMustBeMonotonic() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("用 GREATEST(现有, 本批) 才能在两个副本各自读到旧快照时不让时间倒退")
            .contains("GREATEST(COALESCE(iot_shadow.report_ts, VALUES(report_ts)), VALUES(report_ts))");
    }

    @Test
    @DisplayName("★ 租户与逻辑删除：插入显式带 tenant_id，命中冲突时复活软删行")
    void insertMustCarryTenantAndRevive() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("iot_shadow 是租户表；写入器在事务提交后运行（无租户上下文），租户必须随行带入")
            .contains("id, tenant_id, device_id");
        assertThat(sql).as("软删行仍占着 uk_iot_shadow，冲突时必须复位 is_deleted/status，否则合并结果永远查不到")
            .contains("status = 1, is_deleted = 0");
    }

    @Test
    @DisplayName("★ 不得用 MySQL 行别名 `AS new`：JSqlParser 解析不了 ⇒ 租户拦截器会把整条语句打回")
    void mustNotUseRowAliasThatJsqlParserRejects() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("MyBatis-Plus 的 TenantLineInnerInterceptor 用 JSqlParser 解析每条语句；"
            + "JSqlParser 5.2 解析 `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE` 直接抛 "
            + "ParseException（实测 'Encountered unexpected token: \"AS\"'）⇒ 语句不可用")
            .doesNotContain(" AS new ");
    }

    @Test
    @DisplayName("★ 注解本身必须是**合法 XML**：MyBatis 启动时就解析它，缺 </script> 会让应用起不来")
    void annotationMustBeWellFormedScriptXml() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");

        assertThat(sql).as("MyBatis 只把以 <script> 开头的注解当 XML 解析").startsWith("<script>");
        assertThat(sql).as("缺 </script> 时 MyBatis 抛 SAXParseException"
            + "（XML document structures must start and end within the same entity）⇒ 服务启动即失败。"
            + "本仓 2026-09-25 生产部署实测就是这个后果（旧镜像回滚才恢复）")
            .endsWith("</script>");
        // 真解析一遍：走的就是 MyBatis 启动时那条路径。此前的 renderForParser 会先把 <script>/</script>
        // strip 掉再交给 JSqlParser，等于把「注解本身不是合法 XML」这个失败模式整段掩盖了（实测漏过）。
        assertThatCode(() -> new XMLLanguageDriver().createSqlSource(new Configuration(), sql, Object.class))
            .as("注解字符串必须能被 MyBatis 的 XMLLanguageDriver 解析，否则整个 iot 服务起不来")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 渲染后的 SQL 必须能被**租户拦截器同版本**的 JSqlParser 解析（本地就能咬到解析陷阱）")
    void renderedSqlMustBeParseable() throws IOException {
        String sql = methodSql("mergeReported", "@Insert");
        String rendered = renderForParser(sql, 2);

        try {
            CCJSqlParserUtil.parse(rendered);
        } catch (Exception ex) {
            throw new AssertionError("渲染后的 SQL 解析失败（生产里租户拦截器会先解析再执行）: "
                + rendered, ex);
        }
    }

    /**
     * 把 MyBatis 注解里的动态 SQL 渲染成「拦截器看到的样子」：展开一次 foreach（两条数据行）、
     * 去掉 script 标签、把 {@code #{...}} 换成 {@code ?}。
     *
     * @param sql        注解里的原始 SQL
     * @param rowCount   foreach 展开的行数
     * @return 可交给 JSqlParser 的 SQL
     */
    private static String renderForParser(String sql, int rowCount) {
        int start = sql.indexOf("<foreach");
        int bodyStart = sql.indexOf('>', start) + 1;
        int end = sql.indexOf("</foreach>");
        assertThat(start).as("找不到 foreach 开始标签（门禁失效）").isPositive();
        assertThat(end).as("找不到 foreach 结束标签（门禁失效）").isPositive();
        String body = sql.substring(bodyStart, end);
        // foreach 的分隔符是逗号：行与行之间加，**末尾不加**（末尾多一个逗号就是语法错，渲染器自己要先对）
        String rows = String.join(", ", Collections.nCopies(rowCount, body));
        return (sql.substring(0, start) + rows + sql.substring(end + "</foreach>".length()))
            .replace("<script>", " ")
            .replace("</script>", " ")
            .replaceAll("#\\{[^}]*}", "?")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** 抽取某个 Mapper 方法注解里的 SQL 文本（与 AvailabilityMapperContractTest 同一做法）。 */
    private static String methodSql(String methodName, String annotation) throws IOException {
        String source = Files.readString(MAPPER, StandardCharsets.UTF_8);
        int methodIndex = source.indexOf(" " + methodName + "(");
        assertThat(methodIndex).as("Mapper 里找不到方法 %s（门禁失效）", methodName).isPositive();
        int annotationIndex = source.lastIndexOf(annotation, methodIndex);
        assertThat(annotationIndex).as("方法 %s 前找不到 %s（门禁失效）", methodName, annotation).isPositive();
        String block = source.substring(annotationIndex + annotation.length() + 1, methodIndex);
        int lastQuote = block.lastIndexOf('"');
        assertThat(lastQuote).as("%s 参数里找不到字符串字面量（门禁失效）", annotation).isPositive();
        return stripComments(block.substring(0, lastQuote + 1).replace("+", " ").replace("\"", ""))
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** 剥离注释：门禁断言必须作用在代码上（注释里的示例否则会制造假绿/误报）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
