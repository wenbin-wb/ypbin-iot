/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归属 Mapper 的**源码级 SQL 契约门禁**（M3/M7）。
 *
 * <p>为什么是源码级：这些约束写在 SQL 文本里，而单测只 mock Mapper，原生 SQL 不被任何单测执行
 * （与 {@code AvailabilityMapperContractTest} 同一理由）。本用例钉住 M3 的「必须看到软删行 / 只 revive
 * 软删行」与 M7 的「一次 GROUP BY 聚合」不被后续改动悄悄改掉。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class TenantNodeAssignmentMapperContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path MAPPER = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/TenantNodeAssignmentMapper.java");

    @Test
    @DisplayName("★ M3：存在性查询必须**包含软删行**（带 is_deleted=0 就永远看不到占住唯一键的软删行）")
    void includingDeletedQueryMustNotFilterDeleted() throws IOException {
        String sql = methodSql("selectIncludingDeletedByTenantIds", "@Select");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则门禁空跑").isNotBlank();
        assertThat(sql).as("必须按租户 ID 批量查").contains("tenant_id IN");
        assertThat(sql).as("带上 is_deleted = 0 会漏掉软删行 ⇒ 又回到「永久零采集」的静默失效")
            .doesNotContain("is_deleted = 0");
    }

    @Test
    @DisplayName("★ M3：revive 只作用于软删行（is_deleted=1）并清掉删除标记（is_deleted=0）")
    void reviveMustTargetOnlySoftDeletedRowsAndClearFlag() throws IOException {
        String sql = methodSql("reviveIgnoringDuplicates", "@Update");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则门禁空跑").isNotBlank();
        assertThat(sql).as("只 revive 软删行：并发下已被别的副本复活的行走 UPDATE 之外的路径，不覆盖其归属")
            .contains("is_deleted = 1");
        assertThat(sql).as("必须清删除标记，否则该行仍被逻辑删除过滤").contains("is_deleted = 0");
        assertThat(sql).as("必须按租户 ID 批量 revive（循环单条 UPDATE 是 N+1）").contains("tenant_id IN");
        assertThat(sql).as("复活必须重置为可采集状态与到期时间").contains("lease_expire_at = #{expireAt}")
            .contains("state = #{state}");
        // methodSql 会把 Java 字符串拼接的 "+" 归一成空格，故 epoch 的算术 "+" 直接在原始源码上断言
        // （先剥注释，避免 javadoc 里的示例制造假绿）。
        String raw = stripComments(Files.readString(MAPPER, StandardCharsets.UTF_8));
        assertThat(raw).as("revive 属重新分配，epoch 必须 +1（重置为初始值会让旧快照被判为更新）")
            .contains("epoch = epoch + 1");
    }

    @Test
    @DisplayName("★ M7：运维概览必须一次 GROUP BY 聚合（COUNT + MIN），不得退回逐节点查询")
    void overviewAggregateMustGroupByNode() throws IOException {
        String sql = methodSql("selectActiveAggregateByNode", "@Select");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则门禁空跑").isNotBlank();
        assertThat(sql).contains("COUNT(*)").contains("MIN(lease_expire_at)");
        assertThat(sql).as("必须在 SQL 侧按节点聚合（Java 侧逐节点查询就是 N+1）").contains("GROUP BY access_node");
        assertThat(sql).as("只统计在采且未删除的行").contains("state = 'active'").contains("is_deleted = 0");
    }

    /** 抽取某个 Mapper 方法注解里的 SQL 文本（把 Java 字符串拼接还原成一行）。 */
    private static String methodSql(String methodName, String annotation) throws IOException {
        String source = Files.readString(MAPPER, StandardCharsets.UTF_8);
        int methodIndex = source.indexOf(" " + methodName + "(");
        assertThat(methodIndex).as("Mapper 里找不到方法 %s（门禁失效）", methodName).isPositive();
        int annotationIndex = source.lastIndexOf(annotation, methodIndex);
        assertThat(annotationIndex).as("方法 %s 前找不到 %s（门禁失效）", methodName, annotation).isPositive();
        String block = source.substring(annotationIndex + annotation.length() + 1, methodIndex);
        int lastQuote = block.lastIndexOf('"');
        assertThat(lastQuote).as("注解参数里找不到字符串字面量（门禁失效）").isPositive();
        return stripComments(block.substring(0, lastQuote + 1)
            .replace("+", " ")
            .replace("\"", ""))
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** 剥离行注释与块注释（文本门禁必须作用在代码上，否则注释里的示例会制造假绿）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
