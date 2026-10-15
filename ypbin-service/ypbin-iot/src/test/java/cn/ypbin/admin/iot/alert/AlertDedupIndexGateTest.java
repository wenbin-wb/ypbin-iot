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
import cn.ypbin.admin.iot.enums.AlertReason;
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

    /**
     * 「告警表结构」的两份源：007（全新安装）+ 2026-10-03（存量库建表，**已发布、严禁原地改写**）。
     *
     * <p>2026-10-03 保持 HEAD 原样（零 {@code condition_snapshot}）；新列只由**晚于建表**的迁移引入
     * ——见 {@link #newColumnIntroducedByAlterNotCreate()}。</p>
     */
    private static final List<Path> DDL_SOURCES = List.of(
        REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"),
        REPO_ROOT.resolve("deploy/sql/migration/2026-10-03-iot-alert-schema.sql"));

    /** **已发布**的告警结构迁移（存量库按 sha256 判「已应用」⇒ 严禁原地改写）。 */
    private static final Path AUTHORED_MIGRATION =
        REPO_ROOT.resolve("deploy/sql/migration/2026-10-03-iot-alert-schema.sql");

    /**
     * **声明 {@code condition_snapshot} 列**的两份源：007 末尾的追加 ALTER + 2026-10-15 的数据迁移 ALTER
     * （两者由等价性门禁保证同形）。注意 {@link #AUTHORED_MIGRATION}（2026-10-03）**不在**此列——
     * 它必须保持「零声明」（那正是 D2 的修复要点：已发布迁移不得原地加新列）。
     */
    private static final List<Path> COLUMN_SOURCES = List.of(
        REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"),
        REPO_ROOT.resolve("deploy/sql/migration/2026-10-15-iot-alert-condition-group.sql"));

    /** 真 AND 语义的数据迁移（条件组去重键 + condition_snapshot 列）。 */
    private static final Path GROUP_MIGRATION =
        REPO_ROOT.resolve("deploy/sql/migration/2026-10-15-iot-alert-condition-group.sql");

    /** 对应的回滚脚本。 */
    private static final Path GROUP_ROLLBACK =
        REPO_ROOT.resolve("deploy/sql/rollback/2026-10-15-iot-alert-condition-group-rollback.sql");

    /** 迁移清单（新增迁移必须同步，否则远端兜底安装会缺这一份）。 */
    private static final Path MIGRATION_LIST = REPO_ROOT.resolve("deploy/sql/migration/iot-migrations.list");

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
    @DisplayName("★ condition_snapshot 列必须存在且为 VARCHAR(4000) 可空（真 AND 的可解释性前提）")
    void conditionSnapshotColumnMustExist() throws IOException {
        for (Path source : COLUMN_SOURCES) {
            String sql = Files.readString(source, StandardCharsets.UTF_8);
            Matcher column = Pattern.compile(
                "condition_snapshot\\s+VARCHAR\\((\\d+)\\)\\s+(NOT\\s+)?NULL", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
            assertThat(column.find()).as("%s：缺少 condition_snapshot 列定义"
                + "（真 AND 之后一条实例代表整组，逐条件取值只能落在这一列）", source.getFileName())
                .isTrue();
            assertThat(column.group(1)).as("%s：condition_snapshot 列宽", source.getFileName())
                .isEqualTo(String.valueOf(AlertConditionSnapshot.MAX_LENGTH));
            assertThat(column.group(2)).as("%s：condition_snapshot 必须可空"
                + "（历史行与断档行没有条件组）", source.getFileName()).isNull();
        }
    }

    @Test
    @DisplayName("★★ 同一份安装/迁移源里 condition_snapshot 只能声明一次（防 1060 重复列的回归哨兵）")
    void conditionSnapshotDeclaredExactlyOncePerSource() throws IOException {
        // 背景（2026-10-15 首次交付的真实缺陷）：007 的 CREATE TABLE 与文件末尾的追加 ALTER **各声明一次**
        //   ⇒ 全新安装 007 直接 ERROR 1060 Duplicate column，install.sh 在 set -euo pipefail 下整条中断，
        //   中断点之后的 board-config / dashboard-snapshot 等段全部不建。
        //   ⚠️ 等价性门禁**咬不到**这类问题（两边都是「多了一次」，拼接比对结果恰好相同），故必须单独钉住。
        //   声明源 = 007 的追加 ALTER + 新的数据迁移 ALTER（2026-10-03 必须保持零声明）。
        Pattern declare = Pattern.compile("condition_snapshot\\s+VARCHAR", Pattern.CASE_INSENSITIVE);
        for (Path source : COLUMN_SOURCES) {
            long count = declare.matcher(Files.readString(source, StandardCharsets.UTF_8)).results().count();
            assertThat(count).as("%s：condition_snapshot 被声明了 %d 次（必须恰好 1 次；2 次会在导入时 "
                + "ERROR 1060 Duplicate column，并中断后续所有段）", source.getFileName(), count)
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("★★ 新增列只由「晚于建表」的迁移引入 + 已发布迁移文件不得原地改写（照 mqtt 先例）")
    void newColumnIntroducedByAlterNotCreate() throws IOException {
        // 仓内正确先例：iot_mqtt_ingest_receipt 的 receipt_status 等 6 列在 007 里**只由 ALTER 引入**，
        // CREATE 段里 0 处。迁移侧（migration/*.sql 拼接）天然是「CREATE TABLE → ALTER ADD COLUMN」，
        // 007 必须与之同序（等价性脚本按语句顺序比对，两边顺序必须一致）。
        String fresh = Files.readString(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"),
            StandardCharsets.UTF_8);
        assertThat(createTableOf(fresh, "iot_alert_instance"))
            .as("007 的 CREATE TABLE iot_alert_instance 不得含 condition_snapshot"
                + "（它只应由文件末尾的追加 ALTER 引入，否则同列声明两次）")
            .doesNotContain("condition_snapshot");
        assertThat(fresh).as("007 必须用 ALTER 引入该列").contains("ADD COLUMN condition_snapshot");
        // 且**不得**把新列塞回已发布的 2026-10-03 迁移文件：migrate.sh 按文件 sha256 判「已应用」，
        // 改了会让存量库 --status 报校验和不一致并中止升级。
        assertThat(Files.readString(AUTHORED_MIGRATION, StandardCharsets.UTF_8))
            .as("已发布的迁移文件不得被原地改写（否则存量库 migrate.sh 判 sha256 不一致而中止升级）")
            .doesNotContain("condition_snapshot");
        // 断言聚焦：AUTHORED_MIGRATION 必须是被比对的结构源之一，且两份声明源都真实存在
        assertThat(AUTHORED_MIGRATION).as("AUTHORED_MIGRATION 必须是 DDL_SOURCES 成员").isIn(DDL_SOURCES);
        assertThat(COLUMN_SOURCES).as("声明源必须真实存在").allSatisfy(source ->
            assertThat(Files.exists(source)).isTrue());
    }

    @Test
    @DisplayName("★★ 回滚脚本必须幂等：DROP COLUMN 需先查 information_schema（MySQL 8.4 无 DROP COLUMN IF EXISTS）")
    void rollbackDropMustBeConditional() throws IOException {
        String rollback = Files.readString(GROUP_ROLLBACK, StandardCharsets.UTF_8);
        // 直接 DROP 会在第二次执行时 ERROR 1091；必须条件执行
        assertThat(rollback).as("回滚删列前必须先查 information_schema.columns")
            .contains("information_schema.columns");
        assertThat(rollback).as("回滚删列必须用动态 SQL 条件执行").contains("PREPARE");
        assertThat(rollback).as("还原键的 UPDATE 必须带 LIKE 'R:%' 过滤（重跑无命中）")
            .contains("LIKE 'R:%'");
        assertThat(rollback).as("不得出现无条件的 DROP COLUMN（第二次执行会 ERROR 1091）")
            .doesNotContain("DROP COLUMN condition_snapshot;");
    }

    @Test
    @DisplayName("★ 条件组键 R:ruleId:deviceId 与断档键结构性隔离、且不含 tenantId（迁移爆炸半径最小化的前提）")
    void groupDedupKeyNamespace() {
        // 形态：id 与 property 无关、只随 (规则, 设备) 变化 ⇒ 同规则同设备的多条件只有一条实例
        String first = AlertRules.groupDedupKey(7L, 9L);
        assertThat(first).isEqualTo("R:7:9");
        assertThat(AlertRules.groupDedupKey(7L, 9L)).isEqualTo(first);
        assertThat(AlertRules.groupDedupKey(8L, 9L)).isNotEqualTo(first);
        assertThat(AlertRules.groupDedupKey(7L, 10L)).isNotEqualTo(first);
        // 与断档键永不可能碰撞（首段一为固定字母 R、一为 OUTAGE；rule_id 是 BIGINT 自增）
        for (long ruleId = 0L; ruleId < 5L; ruleId++) {
            assertThat(AlertRules.groupDedupKey(ruleId, 9L))
                .isNotEqualTo(AlertRules.outageDedupKey(9L, 42L));
        }
        // 不含 tenantId：库唯一键 uk_alert_active(tenant_id, active_dedup_key) 已承载租户维度
        assertThat(first).doesNotContain("1");
    }

    @Test
    @DisplayName("★ 数据迁移与回滚必须存在、进清单、且真的把旧键改写成 R: 形态并收口重复活动实例")
    void migrationRewritesLegacyKeysAndRollbackRestores() throws IOException {
        assertThat(Files.isRegularFile(GROUP_MIGRATION)).as("缺少迁移脚本：%s", GROUP_MIGRATION).isTrue();
        assertThat(Files.isRegularFile(GROUP_ROLLBACK)).as("缺少回滚脚本：%s", GROUP_ROLLBACK).isTrue();
        String migration = Files.readString(GROUP_MIGRATION, StandardCharsets.UTF_8);
        String rollback = Files.readString(GROUP_ROLLBACK, StandardCharsets.UTF_8);
        // ① 收口同一 (规则, 设备) 的多条旧活动实例（否则新键下会撞唯一键）
        assertThat(migration).contains("ROW_NUMBER() OVER (PARTITION BY tenant_id, rule_id, device_id");
        assertThat(migration).contains("CONDITION_GROUP_MERGED");
        // ② 把剩余活动键规范化成 R:ruleId:deviceId
        assertThat(migration).contains("CONCAT('R:', rule_id, ':', device_id)");
        // ③ 断档类实例必须被显式排除在射程外
        assertThat(migration).contains("rule_id <> 0");
        // ④ 回滚：把 R: 形态还原成旧形态并删列
        assertThat(rollback).contains("active_dedup_key LIKE 'R:%'");
        assertThat(rollback).contains("DROP COLUMN condition_snapshot");
        assertThat(rollback).doesNotContain("DELETE FROM iot_alert_instance");
        // ⑤ 清单同步（check-iot-sql-equivalence.sh 也校验，这里给出更直接的失败信息）
        String list = Files.readString(MIGRATION_LIST, StandardCharsets.UTF_8);
        assertThat(list).contains(GROUP_MIGRATION.getFileName().toString());
        assertThat(AlertReason.of("CONDITION_GROUP_MERGED"))
            .as("迁移写入的原因码必须在 AlertReason 里有对应枚举（否则页面/导出显示原始码）")
            .isNotNull();
    }

    @Test
    @DisplayName("★ 迁移语句的收口范围：只作用于规则类活动实例（rule_id<>0），断档类一行都不改")
    void migrationOnlyTouchesRuleAlerts() throws IOException {
        String migration = Files.readString(GROUP_MIGRATION, StandardCharsets.UTF_8);
        // 两条数据语句都必须带 rule_id <> 0 的界；断档键形态（OUTAGE）不得出现在迁移脚本任何地方
        // 说明：这里按「分号切分语句」而不是嵌套正则——SQL 语句是平台自产文本，按 ; 切分足以逐条断言
        // 先去掉全部 -- 行注释，再按 ; 切分——否则语句前面挂着的注释会让 startsWith("UPDATE") 失配
        String executable = migration.replaceAll("--[^\\n]*", "");
        List<String> statements = java.util.Arrays.stream(executable.split(";"))
            .map(String::trim)
            .filter(statement -> statement.toUpperCase(java.util.Locale.ROOT).startsWith("UPDATE"))
            .toList();
        assertThat(statements).as("应当有两条数据迁移语句（收口重复 + 规范化键）").hasSize(2);
        for (String statement : statements) {
            assertThat(statement).as("每条数据迁移语句都必须把断档实例（rule_id=0）排除在射程外：%s",
                statement.substring(0, Math.min(60, statement.length()))).contains("rule_id <> 0");
        }
        // 断档键前缀只在注释里被解释，不得出现在任何 SQL 里（它不在本次射程内）
        assertThat(migration.replaceAll("--[^\\n]*", "")).doesNotContain("OUTAGE");
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
