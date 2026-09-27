/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 去重唯一索引的**SQL 文本门禁**（设计 §3.3-U5 的变异哨兵）。
 *
 * <p><b>为什么必须有一条这样的门禁</b>：MySQL 的唯一索引对**含 NULL 的行不做约束**。把
 * {@code resolved_ts} 放进唯一索引恰好把「活动告警」（{@code resolved_ts IS NULL}）这一批**放走**，
 * 去重完全失效——而它的表现是「重复告警刷屏」，**不会报错、不会编译失败、不会类型不符**。
 * 设计 §2.1 已在本机用 {@code mysql:8.4} 实测两个方向确认；本用例把那个结论钉进构建：
 * 谁把索引改回错误形态，CI 立刻转红。</p>
 *
 * <p>用例自带**合成反向样例**自检（{@link #checkerDetectsTheWrongShape()}）：证明本检查器真的能识别
 * 错误形态，而不是「因为正则匹配不到所以永远通过」的那种假绿。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertDedupIndexGateTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    /** 全新安装脚本（告警表追加在 007 的末尾，与迁移文件等价）。 */
    private static final List<Path> DDL_SOURCES = List.of(
        REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"),
        REPO_ROOT.resolve("deploy/sql/migration/2026-10-03-iot-alert-schema.sql"));

    /**
     * 提取某张表的 CREATE TABLE 语句（到第一个分号为止）。
     *
     * <p>刻意不在正则里断言 {@code ) COMMENT '...';} 的具体排版：仓库里的建表语句以
     * {@code ) COMMENT ...} 收尾（没有 {@code );}），把排版写进正则会得到一条「因为匹配不到所以永远通过」
     * 的假绿门禁——本仓已有过同类教训，故此处只锚定「CREATE TABLE <表名> ( ... ;」。</p>
     */
    private static String createTableOf(String sql, String table) {
        Matcher matcher = Pattern.compile("CREATE TABLE\\s+" + Pattern.quote(table) + "\\s*\\(.*?;",
            Pattern.DOTALL).matcher(sql);
        assertThat(matcher.find()).as("找不到 %s 的建表语句（门禁会变成空跑）", table).isTrue();
        return matcher.group();
    }

    /** 提取某条 UNIQUE KEY 的列清单（小写、去空白）。 */
    private static String uniqueKeyColumns(String tableDdl, String keyName) {
        Matcher matcher = Pattern.compile("UNIQUE KEY " + Pattern.quote(keyName) + "\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE).matcher(tableDdl);
        assertThat(matcher.find()).as("表里找不到唯一键 %s（去重的库级保证缺失）", keyName).isTrue();
        return matcher.group(1).replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 检查一份 DDL 文本的活动去重键形态。
     *
     * @param ddl 建表语句或整份脚本
     * @return 违规说明列表（空 = 合规）
     */
    private static List<String> violationsOf(String ddl) {
        String table = createTableOf(ddl, "iot_alert_instance");
        String columns = uniqueKeyColumns(table, "uk_alert_active");
        List<String> violations = new java.util.ArrayList<>();
        if (!"tenant_id,active_dedup_key".equals(columns)) {
            violations.add("uk_alert_active 的列必须是 (tenant_id, active_dedup_key)，实际是 (" + columns + ")");
        }
        // 注意：必须按「逗号分隔后的整列名」判断——`active_dedup_key` 自身含 `dedup_key` 子串，
        // 用 contains 判会把**正确形态**判成违规（本门禁第一版就踩了这个坑，用例当场转红）
        List<String> columnNames = Arrays.stream(columns.split(",")).toList();
        if (columnNames.contains("resolved_ts") || columnNames.contains("dedup_key")) {
            violations.add("uk_alert_active 不得包含 resolved_ts / dedup_key（含 NULL 的行不受唯一约束，"
                + "放进 resolved_ts 会把活动告警整批放走，去重静默失效）");
        }
        // 可空性是本设计成立的前提：恢复时必须能把去重键置 NULL，否则「同键至多一条活动告警」会退化成
        // 「同键永远只能有一条告警」，恢复之后再次越界将无法新建实例（设计 §3.3-U3 的另一半）
        Matcher column = Pattern.compile(
            "active_dedup_key\\s+VARCHAR\\((\\d+)\\)\\s+(NOT\\s+)?NULL", Pattern.CASE_INSENSITIVE)
            .matcher(table);
        if (!column.find()) {
            violations.add("active_dedup_key 必须是 VARCHAR(191) 可空列（活动期非 NULL、恢复时置 NULL）");
        } else {
            if (!"191".equals(column.group(1))) {
                violations.add("active_dedup_key 的列宽必须是 191（与 dedup_key 对齐，唯一索引长度可控）");
            }
            if (column.group(2) != null) {
                violations.add("active_dedup_key 不得为 NOT NULL（恢复时无值可置，去重键永不释放）");
            }
        }
        return violations;
    }

    @Test
    @DisplayName("★ 活动去重键必须是 uk_alert_active(tenant_id, active_dedup_key) 且不得含 resolved_ts")
    void activeDedupKeyMustBeTheAddingForm() throws IOException {
        for (Path source : DDL_SOURCES) {
            String sql = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(violationsOf(sql)).as("%s 的去重键形态不合规", source.getFileName()).isEmpty();
        }
    }

    @Test
    @DisplayName("★ 自检：把索引改成错误形态（含 resolved_ts）时，本检查器必须能报出来")
    void checkerDetectsTheWrongShape() {
        String correct = """
            CREATE TABLE iot_alert_instance
            (
                id               BIGINT       NOT NULL,
                tenant_id        BIGINT       NOT NULL,
                dedup_key        VARCHAR(191) NOT NULL,
                active_dedup_key VARCHAR(191) NULL,
                resolved_ts      DATETIME     NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_alert_active (tenant_id, active_dedup_key)
            );
            """;
        assertThat(violationsOf(correct)).isEmpty();

        // 🔴 设计 §2.1 明确列出的**错误写法**：必须被检查器识别（否则本门禁是假绿）
        String wrongResolvedTs = correct.replace(
            "UNIQUE KEY uk_alert_active (tenant_id, active_dedup_key)",
            "UNIQUE KEY uk_alert_active (tenant_id, dedup_key, resolved_ts)");
        assertThat(violationsOf(wrongResolvedTs)).isNotEmpty();

        // 另一种错误写法：退化成 (tenant_id, dedup_key) —— 恢复后的历史行会与活动的同键行冲突
        String wrongDedupOnly = correct.replace(
            "UNIQUE KEY uk_alert_active (tenant_id, active_dedup_key)",
            "UNIQUE KEY uk_alert_active (tenant_id, dedup_key)");
        assertThat(violationsOf(wrongDedupOnly)).isNotEmpty();

        // 第三种错误写法：active_dedup_key 做成 NOT NULL（恢复时无值可置，去重键永不释放）
        String wrongNotNull = correct.replace("active_dedup_key VARCHAR(191) NULL",
            "active_dedup_key VARCHAR(191) NOT NULL");
        assertThat(violationsOf(wrongNotNull)).isNotEmpty();
    }

    @Test
    @DisplayName("通知幂等键与实例去重键都必须带 tenant_id（隔离与幂等的双保险）")
    void uniqueKeysCarryTenantId() throws IOException {
        for (Path source : DDL_SOURCES) {
            String sql = Files.readString(source, StandardCharsets.UTF_8);
            String notification = createTableOf(sql, "iot_alert_notification");
            assertThat(uniqueKeyColumns(notification, "uk_alert_notification_idem"))
                .as("%s：通知幂等键必须含 tenant_id", source.getFileName())
                .isEqualTo("tenant_id,idempotent_key");
        }
    }

    @Test
    @DisplayName("trigger_mode 列宽必须容得下 CONSECUTIVE_COUNT（设计表 A 写的 VARCHAR(16) 装不下）")
    void triggerModeColumnFitsEnumCodes() throws IOException {
        // 设计 §2.1 表 A 把 trigger_mode 写成 VARCHAR(16)，而最长码 CONSECUTIVE_COUNT 是 17 个字符
        // ⇒ 照抄会在插入时被截断（非严格模式下静默截断，严格模式下报错）。此处钉住真实列宽。
        String longestCode = cn.ypbin.admin.iot.enums.AlertTriggerMode.CONSECUTIVE_COUNT.getCode();
        assertThat(longestCode.length()).isGreaterThan(16);
        for (Path source : DDL_SOURCES) {
            String sql = Files.readString(source, StandardCharsets.UTF_8);
            String ruleTable = createTableOf(sql, "iot_alert_rule");
            Matcher matcher = Pattern.compile("trigger_mode\\s+VARCHAR\\((\\d+)\\)").matcher(ruleTable);
            assertThat(matcher.find()).as("%s：找不到 trigger_mode 的列宽", source.getFileName()).isTrue();
            assertThat(Integer.parseInt(matcher.group(1)))
                .as("%s：trigger_mode 列宽必须 ≥ %d", source.getFileName(), longestCode.length())
                .isGreaterThanOrEqualTo(longestCode.length());
        }
    }

    @Test
    @DisplayName("四张告警表都必须有 is_deleted 与 tenant_id（租户隔离门禁与逻辑删除的前提）")
    void alertTablesCarryTenantAndLogicDelete() throws IOException {
        for (Path source : DDL_SOURCES) {
            String sql = Files.readString(source, StandardCharsets.UTF_8);
            for (String table : List.of("iot_alert_rule", "iot_alert_rule_point", "iot_alert_instance",
                "iot_alert_notification")) {
                String ddl = createTableOf(sql, table);
                assertThat(ddl).as("%s.%s 缺少 tenant_id", source.getFileName(), table)
                    .contains("tenant_id");
                assertThat(ddl).as("%s.%s 缺少 is_deleted", source.getFileName(), table)
                    .contains("is_deleted");
            }
        }
    }
}
