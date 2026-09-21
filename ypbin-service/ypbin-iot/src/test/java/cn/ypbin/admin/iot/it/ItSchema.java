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
 *
 * @author wenbin
 * @since 2026-09-21
 */
final class ItSchema {

    /** 用于判定「库是否已初始化」的代表表。 */
    private static final String PROBE_TABLE = "iot_device";

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
