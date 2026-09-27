/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.it;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 真库 IT 的共享建表助手：**幂等**执行 {@code deploy/sql/006-iot-schema.sql}。
 *
 * <p><b>为什么需要它</b>：同一 MySQL 实例会被多个 IT 类共用（CI 用 service 容器、本机 Testcontainers 复用），
 * 而 006 是「全新安装」脚本、不含 {@code IF NOT EXISTS}。谁先跑谁建表，后跑的那个若无条件重跑就会
 * 以「表已存在」直接 initializationError——本仓实测发生过（新增 IT 名字排序在前导致既有 IT 转红）。
 * 判定「表是否已存在」后只建一次，既保持各 IT 可独立运行，也不依赖执行顺序。</p>

 * <p><b>为什么还要再跑一个 {@code -schema} 迁移</b>：006 只到 M-1 为止的结构，之后各功能新增的
 * <b>结构</b>变更落在 {@code deploy/sql/migration/*-iot-*-schema.sql}（迁移必须排在 006+007 之后，
 * 见 007 末尾的顺序约束）。而 MyBatis-Plus 的 SELECT 会带出实体**全部列** ⇒ 只建 006 的话，
 * 读 {@code iot_device} 就会撞上 {@code Unknown column 'credential_version'}（2026-09-27 引入凭据三列时实测）。
 * 因此这里把本功能的结构迁移也执行一遍；它只含 DDL、且只在「库还没建」时执行一次。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
final class ItSchema {

    /** 用于判定「库是否已初始化」的代表表。 */
    private static final String PROBE_TABLE = "iot_device";

    /**
     * 006 之后还需要执行的**结构**迁移（纯 DDL；006 不含这些结构）。
     *
     * <p>新增功能的结构迁移要加到这里，否则任何「按实体读该表」的 IT 都会以
     * {@code Unknown column} 失败。顺序按文件名（与生产迁移顺序一致）。</p>
     */
    private static final List<String> EXTRA_SCHEMA_SCRIPTS = List.of(
        "deploy/sql/migration/2026-09-30-iot-credential-schema.sql",
        // 告警与阈值（2026-10-03）：四张表只在 007 的追加段与这个 `-schema` 迁移里，
        // 不加入本清单 ⇒ 真库 IT 里这四张表根本不建（独立复核 2026-10-03 指出的覆盖真空）
        "deploy/sql/migration/2026-10-03-iot-alert-schema.sql");

    private ItSchema() {
    }

    /**
     * 确保 IoT 表结构存在（已存在则跳过）。
     *
     * @param dataSource 数据源
     * @param repoRoot   仓库根目录（用于定位 006 脚本）
     * @throws SQLException 建表或探测失败
     */
    static void ensure(DataSource dataSource, Path repoRoot) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (exists(connection, PROBE_TABLE)) {
                return;
            }
            ScriptUtils.executeSqlScript(connection,
                new FileSystemResource(repoRoot.resolve("deploy/sql/006-iot-schema.sql")));
            for (String script : EXTRA_SCHEMA_SCRIPTS) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(repoRoot.resolve(script)));
            }
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                    + "AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }
}
