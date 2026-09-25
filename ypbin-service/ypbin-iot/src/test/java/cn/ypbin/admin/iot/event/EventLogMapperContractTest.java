/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件实例 Mapper 与 DDL 的**源码级契约门禁**（G6）。
 *
 * <p>为什么必须是源码级：幂等的唯一防线（唯一键 + {@code ON DUPLICATE KEY UPDATE}）写在 SQL 文本与 DDL 里，
 * 而单测只 mock Mapper、真库 IT 只在 CI 跑。上一轮外委复核正是因此漏过「文档说有多副本安全、SQL 里却没有
 * 对应谓词」的真缺陷（见 {@code AvailabilityMapperContractTest} 的教训）。本类把这两处文本约束钉在本地，
 * 并可按「删掉幂等键」做变异验证（用例必须转红）。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
class EventLogMapperContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path MAPPER = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/mapper/IotEventLogMapper.java");

    private static final Path SCHEMA = REPO_ROOT.resolve("deploy/sql/006-iot-schema.sql");

    private static final Path MIGRATION = REPO_ROOT.resolve(
        "deploy/sql/migration/2026-09-19-iot-m2-event-log-schema.sql");

    /** 幂等唯一键：租户 + 设备 + 幂等键（三者缺一，重投就可能落重复行）。 */
    private static final Pattern IDEMPOTENCY_UNIQUE_KEY = Pattern.compile(
        "UNIQUE KEY uk_iot_event_log_idem\\s*\\(\\s*tenant_id\\s*,\\s*device_id\\s*,\\s*idempotent_key\\s*\\)");

    @Test
    @DisplayName("★ 批量插入必须幂等：命中唯一键时不得抛异常、不得改已有行")
    void insertBatchMustBeIdempotentOnDuplicateKey() throws IOException {
        String sql = methodSql("insertBatch", "@Insert");

        assertThat(sql).as("抽取到的 SQL 不能为空，否则本门禁恒真（空跑）").isNotBlank();
        assertThat(sql).as("没有 ON DUPLICATE KEY UPDATE：并发重投会撞唯一键抛异常，整批上报失败")
            .contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql).as("必须是 INSERT INTO iot_event_log 的批量语句")
            .contains("INSERT INTO iot_event_log").contains("<foreach");
        assertThat(sql).as("批量插入必须逐行带 tenant_id（多设备一条语句）与幂等键")
            .contains("#{item.tenantId}").contains("#{item.idempotentKey}");
        assertThat(sql).as("显式 is_deleted = 0：原生 SQL 不会被追加逻辑删除条件")
            .contains("is_deleted");
    }

    @Test
    @DisplayName("★ 幂等预查必须按设备 + 批量 IN，且显式带逻辑删除条件")
    void selectExistingKeysMustBeScopedAndBatched() throws IOException {
        String sql = methodSql("selectExistingKeys", "@Select");

        assertThat(sql).isNotBlank();
        assertThat(sql).as("必须按设备限定：同租户不同设备可以有相同幂等键，跨设备预查会误判为已存在")
            .contains("device_id = #{deviceId}");
        assertThat(sql).as("必须批量 IN（调用方保证非空）：循环单查是 N+1")
            .contains("idempotent_key IN").contains("<foreach");
        assertThat(sql).as("显式 is_deleted = 0：原生 SQL 不会被追加逻辑删除条件")
            .contains("is_deleted = 0");
    }

    @Test
    @DisplayName("★ DDL：iot_event_log 必须声明三列幂等唯一键（删掉它 ⇒ 幂等只剩应用层竞态）")
    void schemaMustDeclareIdempotencyUniqueKey() throws IOException {
        String ddl = Files.readString(SCHEMA, StandardCharsets.UTF_8);

        assertThat(IDEMPOTENCY_UNIQUE_KEY.matcher(ddl).find())
            .as("006-iot-schema.sql 里找不到 uk_iot_event_log_idem(tenant_id, device_id, idempotent_key)："
                + "并发重复投递将落重复行")
            .isTrue();
    }

    @Test
    @DisplayName("★ 迁移脚本必须与全新安装脚本同构（表名、三列幂等唯一键、关键列都在）")
    void migrationMustMirrorSchema() throws IOException {
        assertThat(Files.exists(MIGRATION)).as("找不到迁移文件 %s", MIGRATION).isTrue();
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(migration).contains("CREATE TABLE iot_event_log");
        assertThat(IDEMPOTENCY_UNIQUE_KEY.matcher(migration).find())
            .as("迁移脚本缺幂等唯一键 ⇒ 已上线库升级后没有幂等防线（半同步）")
            .isTrue();
        for (String column : new String[] {"idempotent_key", "event_code", "event_ts", "level"}) {
            assertThat(migration).as("迁移脚本缺列 %s", column).contains(column);
        }
    }

    /**
     * 抽取某个 Mapper 方法注解里的 SQL 文本（把 Java 字符串拼接还原成一行）。
     *
     * @param methodName Mapper 方法名
     * @param annotation 注解名（{@code @Insert} / {@code @Select}）
     * @return 单行 SQL
     * @throws IOException 读文件失败
     */
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

    /** 剥离注释：门禁断言必须作用在代码上（注释里举例写一个唯一键不能被当成声明）。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
