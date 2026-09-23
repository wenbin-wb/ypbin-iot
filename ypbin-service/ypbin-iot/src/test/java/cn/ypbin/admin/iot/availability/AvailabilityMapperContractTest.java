/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 活性表 Mapper 的**源码级契约门禁**（M-2）。
 *
 * <p>为什么必须是源码级而不是行为级：这两条约束都写在 SQL 文本里，而 MyBatis 的原生 SQL
 * 不会被任何单测执行（单测只 mock Mapper）。上一轮外委复核正是因此漏过了一个真缺陷——
 * 文档与 Javadoc 都声称「多副本安全靠 {@code AND open_outage_id IS NULL}」，而 SQL 里根本没有这个谓词，
 * 所有 mock 级用例（含变异）都咬不到它。真库并发用例成本高且在 CI 才跑，这里用源码断言把它钉在本地。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
class AvailabilityMapperContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path MAPPER = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/DeviceLivenessMapper.java");

    @Test
    @DisplayName("★ markOpenOutage 必须带 `AND open_outage_id IS NULL`（多副本只允许一个赢家）")
    void markOpenOutageMustBeConditional() throws IOException {
        String sql = methodSql("markOpenOutage");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("少了这个谓词：两副本会各插一条进行中断档 ⇒ 可用率被双计、其中一条永久不闭合")
            .contains("open_outage_id IS NULL");
    }

    @Test
    @DisplayName("★ 上报路径不得回写 open_outage_id（写权分离：扫描开、上报闭）")
    void observedStateUpdateMustNotWriteOpenOutage() throws IOException {
        String sql = methodSql("reviveAndUpdate");

        assertThat(sql).as("抽取到的 SQL 不能为空").isNotBlank();
        assertThat(sql).as("无条件回写 open_outage_id 会把扫描刚开的断档覆盖成 NULL（孤儿）")
            .doesNotContain("open_outage_id");
    }

    @Test
    @DisplayName("★ 清空标记必须带 `AND open_outage_id = #{outageId}`（只清自己那一条）")
    void clearOpenOutageMustBeConditional() throws IOException {
        String sql = methodSql("clearOpenOutage");

        assertThat(sql).as("抽取到的 SQL 不能为空").isNotBlank();
        assertThat(sql).contains("open_outage_id = #{outageId}");
    }

    @Test
    @DisplayName("★ A14：观测状态的时间戳必须**在 SQL 里**只前进（跨副本写回旧值不得回退）")
    void observedStateUpdateMustBeMonotonic() throws IOException {
        String sql = methodSql("reviveAndUpdate");

        // 断言到「THEN/ELSE 的极性」而不只是子串：反转变异（THEN 与 ELSE 对调）也必须被咬住
        assertThat(sql).as("last_good_at 必须取较大值（否则两个副本各自读旧快照再写回，旧时间戳会覆盖新的 ⇒ 可用率被算高）")
            .contains("WHEN last_good_at IS NULL OR #{lastGoodAt,jdbcType=TIMESTAMP} > last_good_at "
                + "THEN #{lastGoodAt,jdbcType=TIMESTAMP} ELSE last_good_at END");
        assertThat(sql).as("first_observed_at 必须取较小值（极性同样要在断言里体现）")
            .contains("WHEN first_observed_at IS NULL OR #{firstObservedAt,jdbcType=TIMESTAMP} < first_observed_at "
                + "THEN #{firstObservedAt,jdbcType=TIMESTAMP} ELSE first_observed_at END");
        assertThat(sql).as("last_observed_at 必须取较大值")
            .contains("WHEN last_observed_at IS NULL OR #{lastObservedAt,jdbcType=TIMESTAMP} > last_observed_at "
                + "THEN #{lastObservedAt,jdbcType=TIMESTAMP} ELSE last_observed_at END");
        assertThat(sql).as("null 参数必须有 jdbcType，否则 MyBatis 拼不出可执行语句")
            .contains("jdbcType=TIMESTAMP");
    }

    @Test
    @DisplayName("★ A11：窗口汇总必须是聚合 SQL，且显式带 tenant_id 与 is_deleted=0")
    void windowSummaryMustBeExactAggregate() throws IOException {
        String sql = outageMapperSql("summarizeInWindow");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("必须一次聚合出精确值（明细截断不影响汇总）").contains("COUNT(*)")
            .contains("SUM(per_row.sec - per_row.msec)").contains("MAX(GREATEST(0, per_row.sec - per_row.msec))")
            .contains("SUM(per_row.msec)");
        // 显式写的理由（按实测更正）：租户条件其实会被 MP 的租户拦截器**重写追加**，显式写属纵深防御
        // （executeIgnore 等跨租户场景下它是唯一护栏）；而**逻辑删除不会被追加** ⇒ is_deleted 必须显式写
        assertThat(sql).as("tenant_id 与 is_deleted 都必须显式写：前者是纵深防御，后者是必需（逻辑删除不会被自动追加）")
            .contains("tenant_id = #{tenantId}").contains("o.is_deleted = 0");
        assertThat(sql).as("单条断档不得为负（否则脏数据会把可用率抬高）")
            .contains("GREATEST(0, TIMESTAMPDIFF");
        assertThat(sql).as("窗口裁剪：起点侧").contains("GREATEST(o.start_ts, #{from,jdbcType=TIMESTAMP})");
        assertThat(sql).as("窗口裁剪：终点侧（进行中的断档结算到 now，now 缺失时退回数据库时钟）")
            .contains("LEAST(COALESCE(o.end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), "
                + "#{to,jdbcType=TIMESTAMP})");
        assertThat(sql).as("窗口谓词不得被删掉").contains("o.start_ts < #{to,jdbcType=TIMESTAMP}")
            .contains("(o.end_ts IS NULL OR o.end_ts > #{from,jdbcType=TIMESTAMP})");
        assertThat(sql).as("可空时间参数必须带 jdbcType（与观测状态更新同一条纪律）")
            .contains("jdbcType=TIMESTAMP");
        // 维护窗口（spec §12.5）：每行断档要与维护窗口求交、并从该行里剔除；子查询自己也要收敛租户与逻辑删除
        assertThat(sql).as("必须把维护窗口内的断档从分子里剔除（否则计划停机仍拉低可用率）")
            .contains("FROM maintenance_window w").contains("(w.device_id IS NULL OR w.device_id = o.device_id)")
            .contains("w.is_deleted = 0").contains("w.tenant_id = o.tenant_id");
        assertThat(sql).as("维护重叠不得超过该行自身断档时长（上限封顶，保证计入断档不为负）")
            .contains("LEAST(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(o.start_ts, "
                + "#{from,jdbcType=TIMESTAMP}),");
        // 断言到**形状**而不是「子查询存在」：把子查询结果乘 0（等于不剔除维护）也必须被咬住——
        // 「存在维护子查询」与「真的把它的结果从断档里减掉」是两回事（前者是装饰，后者才是口径）
        assertThat(sql).as("必须真的用维护子查询的结果（不是只查出来放着）")
            .contains("COALESCE((SELECT SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, ");
        assertThat(sql).as("计入断档必须是 sec 与 msec 的差").contains("SUM(per_row.sec - per_row.msec)");
    }

    @Test
    @DisplayName("★ A3：维护时长聚合必须收敛设备范围（租户级窗口对所有设备生效）且显式带租户/逻辑删除")
    void maintenanceAggregateMustScopeDeviceAndTenant() throws IOException {
        String sql = methodSql(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/MaintenanceWindowMapper.java"),
            "sumMaintenanceSecondsInWindow", "@Select");

        assertThat(sql).as("抽取到的 SQL 不能为空").isNotBlank();
        assertThat(sql).as("设备范围：显式 NULL 表示该租户全部设备").contains("(device_id IS NULL OR device_id = #{deviceId})");
        assertThat(sql).as("原生聚合 SQL 必须显式收敛租户与逻辑删除，且时间参数带 jdbcType")
            .contains("tenant_id = #{tenantId}").contains("is_deleted = 0").contains("jdbcType=TIMESTAMP");
        assertThat(sql).as("窗口裁剪与「不得为负」都要在 SQL 里").contains("GREATEST(start_ts, #{from,jdbcType=TIMESTAMP})")
            .contains("LEAST(COALESCE(end_ts, #{now,jdbcType=TIMESTAMP}), #{to,jdbcType=TIMESTAMP})")
            .contains("GREATEST(0, TIMESTAMPDIFF");
        assertThat(sql).as("窗口谓词不得被删掉").contains("start_ts < #{to,jdbcType=TIMESTAMP}")
            .contains("(end_ts IS NULL OR end_ts > #{from,jdbcType=TIMESTAMP})");
    }

    @Test
    @DisplayName("★ 复杂聚合 SQL：要么能被 JSqlParser 解析，要么必须显式关闭租户拦截器（否则真库会被拒绝执行）")
    void complexAggregateMustBeParseableOrExplicitlyIgnored() throws IOException {
        // CI 真库实测过：派生表 + 相关子查询让 MP 的 JSqlParser 抛 ParseException，
        // 而租户拦截器**改写不了就拒绝执行** ⇒ 这里把「可解析性」钉在本地（不必等 CI 才发现）。
        assertParseableOrIgnored(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/OutageEventMapper.java"),
            "summarizeInWindow");
        assertParseableOrIgnored(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/MaintenanceWindowMapper.java"),
            "sumMaintenanceSecondsInWindow");
    }

    /** 断言：SQL 可被 JSqlParser 解析，或该方法显式关闭了租户拦截器（关了就要求显式租户条件）。 */
    private static void assertParseableOrIgnored(Path mapperPath, String methodName) throws IOException {
        String source = Files.readString(mapperPath, StandardCharsets.UTF_8);
        int methodIndex = source.indexOf(" " + methodName + "(");
        assertThat(methodIndex).as("找不到方法 %s", methodName).isPositive();
        boolean ignored = source.lastIndexOf("@InterceptorIgnore(tenantLine = \"true\")", methodIndex) > 0;
        String sql = methodSql(mapperPath, methodName, "@Select")
            .replaceAll("#\\{[^}]*}", "?");
        boolean parseable;
        try {
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            parseable = true;
        } catch (Exception ex) {
            parseable = false;
        }
        if (parseable) {
            return;
        }
        assertThat(ignored)
            .as("SQL 无法被 JSqlParser 解析（%s），而租户拦截器改写不了就会拒绝执行 ⇒ "
                + "必须加 @InterceptorIgnore(tenantLine = \"true\") 并用显式 tenant_id 兜住隔离", methodName)
            .isTrue();
        assertThat(sql).as("关闭拦截器后，显式租户条件就是唯一护栏").contains("tenant_id");
    }

    /** 抽取某个 Mapper 方法注解里的 SQL 文本（把 Java 字符串拼接还原成一行；支持 @Update 与 @Select）。 */
    private static String methodSql(String methodName) throws IOException {
        return methodSql(MAPPER, methodName, "@Update");
    }

    /** 抽取 OutageEventMapper 的 SQL（@Select 聚合）。 */
    private static String outageMapperSql(String methodName) throws IOException {
        return methodSql(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/OutageEventMapper.java"),
            methodName, "@Select");
    }

    private static String methodSql(Path mapperPath, String methodName, String annotation)
            throws IOException {
        String source = Files.readString(mapperPath, StandardCharsets.UTF_8);
        int methodIndex = source.indexOf(" " + methodName + "(");
        assertThat(methodIndex).as("Mapper 里找不到方法 %s（门禁失效）", methodName).isPositive();
        int annotationIndex = source.lastIndexOf(annotation, methodIndex);
        assertThat(annotationIndex).as("方法 %s 前找不到 %s（门禁失效）", methodName, annotation).isPositive();
        String block = source.substring(annotationIndex + annotation.length() + 1, methodIndex);
        int lastQuote = block.lastIndexOf('"');
        assertThat(lastQuote).as("@Update 参数里找不到字符串字面量（门禁失效）").isPositive();
        // 把「多行字符串拼接」还原成一行 SQL：去掉加号/引号/换行
        return block.substring(0, lastQuote + 1)
            .replace("+", " ")
            .replace("\"", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
