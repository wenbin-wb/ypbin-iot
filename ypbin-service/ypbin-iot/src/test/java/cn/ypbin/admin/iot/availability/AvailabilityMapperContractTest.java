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
import java.util.List;
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
    @DisplayName("★ A11/A3：断档聚合必须**保持简单可解析**（无派生表/相关子查询），并显式带租户与逻辑删除")
    void windowSummaryMustBeExactSimpleAggregate() throws IOException {
        String sql = outageMapperSql("summarizeInWindow");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("必须一次聚合出精确值（明细截断不影响汇总）").contains("COUNT(*)")
            .contains("SUM(GREATEST(0, TIMESTAMPDIFF").contains("MAX(GREATEST(0, TIMESTAMPDIFF");
        // ⚠️ 这条是**回归防线**：本 PR 曾用「派生表 + 相关子查询」把维护剔除写进这条 SQL，CI 真库实测
        // ① MP 的 JSqlParser 解析失败（拦截器改写不了就拒绝执行）② 即便关掉拦截器 MySQL 也报语法错误。
        // 复杂剔除因此改到 Java 侧（两段 SQL 都保持简单可解析）。
        assertThat(sql).as("不得引入派生表/相关子查询（CI 真库实测被 JSqlParser 与 MySQL 双重拒绝）")
            .doesNotContain("(SELECT").doesNotContain("per_row");
        assertThat(sql).as("tenant_id 与 is_deleted 都必须显式写：前者是纵深防御（插件会追加），后者是必需（逻辑删除不会被追加）")
            .contains("tenant_id = #{tenantId}").contains("is_deleted = 0");
        assertThat(sql).as("窗口裁剪两端 + 单条不得为负").contains("GREATEST(start_ts, #{from,jdbcType=TIMESTAMP})")
            .contains("LEAST(COALESCE(end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), "
                + "#{to,jdbcType=TIMESTAMP})");
        assertThat(sql).as("窗口谓词不得被删掉").contains("start_ts < #{to,jdbcType=TIMESTAMP}")
            .contains("(end_ts IS NULL OR end_ts > #{from,jdbcType=TIMESTAMP})");
        assertThat(sql).as("可空时间参数必须带 jdbcType").contains("jdbcType=TIMESTAMP");
    }

    @Test
    @DisplayName("★ A3：维护聚合必须简单可解析、显式收敛租户与设备范围，并覆盖「断档∩维护」")
    void maintenanceAggregatesMustScopeTenantAndDevice() throws IOException {
        Path mapper = REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/MaintenanceWindowMapper.java");
        String maintenance = methodSql(mapper, "sumMaintenanceSecondsInWindow", "@Select");
        String overlap = methodSql(mapper, "sumOutageInMaintenanceSeconds", "@Select");

        assertThat(maintenance).as("分母的维护时长：设备范围含 NULL（租户级窗口）")
            .contains("(device_id IS NULL OR device_id = #{deviceId})");
        assertThat(maintenance).as("显式租户/逻辑删除 + 窗口裁剪 + 不得为负")
            .contains("tenant_id = #{tenantId}").contains("is_deleted = 0")
            .contains("GREATEST(start_ts, #{from,jdbcType=TIMESTAMP})")
            .contains("GREATEST(0, TIMESTAMPDIFF").contains("jdbcType=TIMESTAMP");
        assertThat(overlap).as("分子的「断档∩维护」：必须真的与维护窗口求交（JOIN + 双端窗口裁剪）")
            .contains("JOIN maintenance_window w").contains("(w.device_id IS NULL OR w.device_id = o.device_id)")
            .contains("GREATEST(o.start_ts, w.start_ts, #{from,jdbcType=TIMESTAMP})")
            .contains("LEAST(COALESCE(o.end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), "
                + "COALESCE(w.end_ts, #{to,jdbcType=TIMESTAMP}), #{to,jdbcType=TIMESTAMP})");
        assertThat(overlap).as("显式租户/逻辑删除 + 简单可解析")
            .contains("w.tenant_id = o.tenant_id").contains("o.tenant_id = #{tenantId}")
            .contains("o.is_deleted = 0").contains("w.is_deleted = 0")
            .doesNotContain("(SELECT");
    }

    @Test
    @DisplayName("★ A6/A10：交接窗口的批量 SQL 必须显式带租户/逻辑删除、可解析，且关闭只缩短不延长")
    void handoverWindowSqlMustBeScopedAndShortenOnly() throws IOException {
        Path mapper = REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/MaintenanceWindowMapper.java");
        String overlapping = methodSql(mapper, "findTenantsWithOverlappingWindow", "@Select");
        String insertBatch = methodSql(mapper, "insertBatch", "@Insert");
        String close = methodSql(mapper, "closeOpenWindows", "@Update");

        assertThat(overlapping).as("重叠判定必须显式带租户与逻辑删除 + 双端区间裁剪，且不得用派生表")
            .contains("tenant_id IN").contains("is_deleted = 0")
            .contains("start_ts &lt; #{to,jdbcType=TIMESTAMP}")
            .contains("end_ts IS NULL OR end_ts &gt; #{from,jdbcType=TIMESTAMP}")
            .doesNotContain("(SELECT");
        assertThat(insertBatch).as("批量插入必须带 tenant_id 与 end_ts（上界，防无人接管时窗口永久开）")
            .contains("tenant_id").contains("#{item.endTs}").contains("#{item.startTs}");
        assertThat(close).as("关闭必须**只缩短**（LEAST）：延长会把接管后的真实采集时间也排除掉")
            .contains("LEAST(COALESCE(end_ts").contains("tenant_id IN");
    }

    @Test
    @DisplayName("★ A6/A10：租约侧对租户表的跨租户读写必须包在 executeIgnore 里（该路径无租户上下文）")
    void handoverCallsMustBeWrappedInExecuteIgnore() throws IOException {
        // 依据（复核实测）：/internal/lease/** 与定时扫描线程没有租户上下文，而 maintenance_window 是租户表
        // ⇒ 租户插件 fail-closed 会直接抛；同仓可用率扫描早就用 executeIgnore 处理平台级访问。
        String source = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/service/impl/LeaseServiceImpl.java"),
            StandardCharsets.UTF_8);
        for (String call : List.of("findTenantsWithOverlappingWindow", "insertBatch", "closeOpenWindows")) {
            int index = source.indexOf("maintenanceWindowMapper." + call);
            assertThat(index).as("LeaseServiceImpl 里应存在对 %s 的调用", call).isPositive();
            int windowStart = Math.max(0, index - 400);
            assertThat(source.substring(windowStart, index)).as(
                "%s 必须包在 TenantContext.executeIgnore(...) 里（无租户上下文时插件 fail-closed）", call)
                .contains("executeIgnore");
        }
    }

    @Test
    @DisplayName("★ D0.8：保留清理的 DELETE 只能删**已闭合**的行（删进行中的断档会让可用率 fail-open）")
    void retentionDeleteMustOnlyRemoveClosedRows() throws IOException {
        String outage = methodSql(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/OutageEventMapper.java"),
            "deleteStartedBefore", "@Delete");
        String window = methodSql(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/MaintenanceWindowMapper.java"),
            "deleteStartedBefore", "@Delete");
        for (String sql : List.of(outage, window)) {
            // 注意：@Delete（非 <script>）里是裸 `<`，不是 XML 转义后的 `&lt;` ⇒ 断言别绑死转义形态
            assertThat(sql).as("清理 SQL 必须带 end_ts IS NOT NULL，且按 start_ts 截止")
                .contains("end_ts IS NOT NULL").contains("start_ts").contains("cutoff");
        }
    }

    @Test
    @DisplayName("★ D0.8：保留清理的跨租户调用必须包在 executeIgnore 里（无租户上下文的定时任务）")
    void retentionCallsMustBeWrappedInExecuteIgnore() throws IOException {
        // 依据（复核实测）：去掉包裹后 5 例单测仍全绿 ⇒ 需要源码级门禁兜住这个关键安全属性
        String source = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/retention"
                + "/RetentionCleanupServiceImpl.java"), StandardCharsets.UTF_8);
        for (String call : List.of("outageEventMapper.deleteStartedBefore",
                "maintenanceWindowMapper.deleteStartedBefore")) {
            int index = source.indexOf(call);
            assertThat(index).as("RetentionCleanupServiceImpl 里应存在对 %s 的调用", call).isPositive();
            int windowStart = Math.max(0, index - 300);
            assertThat(source.substring(windowStart, index)).as(
                "%s 必须包在 TenantContext.executeIgnore(...) 里（清理没有租户身份，插件会 fail-closed）", call)
                .contains("executeIgnore");
        }
    }

    /** 剥离行注释与块注释（文本门禁必须作用在代码上，否则注释里的示例会制造假绿）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
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
        // 把「多行字符串拼接」还原成一行 SQL：去掉加号/引号/换行，并**剥离注释**
        // （字符串字面量之间可能夹注释；门禁断言必须作用在代码上，本仓教训二十三）
        return stripComments(block.substring(0, lastQuote + 1)
            .replace("+", " ")
            .replace("\"", ""))
            .replaceAll("\\s+", " ")
            .trim();
    }
}
