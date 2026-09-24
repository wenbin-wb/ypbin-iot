/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB 表模型写入器（§5.2.1；语法按官方一手文档核实，2026-09-24）。
 *
 * <p>为什么用 JDBC 而不引 IoTDB 客户端类：只用 {@code java.sql.*} + {@link DriverManager}，
 * 驱动以 **runtime 依赖**提供 ⇒ 平台代码与 IoTDB 版本解耦（官方也提示"不要用更新的客户端连更旧的服务端"，
 * 运行时由部署决定版本更稳）。官方同时提示 JDBC 插入**可能达不到高吞吐**，写入成为瓶颈时应切
 * Native API 的 {@code TableSession + Tablet}（登记为后续优化）。</p>
 *
 * <p>语句：{@code INSERT INTO <表>(cols...) VALUES (?,...)}，多行由 {@code addBatch/executeBatch} 组装
 * （官方支持多行 VALUES）；<b>同刻重复写会更新原值</b>，因此不做批内去重。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class IotDbTimeSeriesWriter implements TimeSeriesWriter {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesWriter.class);

    /** 写入失败计数（按批次计）。 */
    public static final String METRIC_FAILED = "iot.timeseries.write.failed";

    /** 列顺序必须与建表一致（§5.2.1 的 DDL）。 */
    private static final String COLUMNS = "(tenant_id, device_id, property_id, time, value_double, value_text, "
        + "quality)";

    private final TimeSeriesProperties properties;
    private final Counter failedCounter;

    public IotDbTimeSeriesWriter(TimeSeriesProperties properties, MeterRegistry meterRegistry) {
        // 连接前先确保驱动已注册：本类可被直接构造（绕过 IotTimeSeriesConfiguration），
        // 漏注册就会重现 CI 上的 No suitable driver found
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("时序写入失败的批次数").register(meterRegistry);
    }

    @Override
    public void writeAll(List<TimeSeriesPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + properties.getTableName() + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        int batchSize = properties.getBatchSize();
        for (int start = 0; start < points.size(); start += batchSize) {
            List<TimeSeriesPoint> chunk = points.subList(start, Math.min(points.size(), start + batchSize));
            writeChunk(sql, chunk);
        }
    }

    /**
     * 写一个分块（一次连接 + 一次 executeBatch）。
     *
     * @param sql   语句
     * @param chunk 分块数据
     */
    private void writeChunk(String sql, List<TimeSeriesPoint> chunk) {
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = DriverManager.getConnection(properties.getUrl(), credentials);
            statement = connection.prepareStatement(sql);
            for (TimeSeriesPoint point : chunk) {
                bind(statement, point);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException ex) {
            failedCounter.increment();
            log.error("[iot] 时序写入失败（已计数，不影响上报落库）：表={} 条数={}",
                LogSanitizer.sanitize(properties.getTableName()), chunk.size(), ex);
        } finally {
            closeQuietly(statement);
            closeQuietly(connection);
        }
    }

    /**
     * 绑定一行（列顺序：tenant/device/property/time/value_double/value_text/quality）。
     *
     * <p>「永不双写」由 {@link ReadingValueMapper} 保证：数值行只写 {@code value_double}（文本列显式 NULL），
     * 文本行只写 {@code value_text}（数值列显式 NULL）——避免查询侧"按列非空取值"取到 0.0 这种假真值。</p>
     *
     * @param statement 语句
     * @param point     读数
     * @throws SQLException 绑定失败
     */
    static void bind(PreparedStatement statement, TimeSeriesPoint point) throws SQLException {
        ReadingValueMapper.MappedValue mapped = ReadingValueMapper.map(point.value());
        statement.setString(1, String.valueOf(point.tenantId()));
        statement.setString(2, String.valueOf(point.deviceId()));
        statement.setString(3, point.propertyId());
        statement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(point.ts())));
        if (mapped.numeric()) {
            statement.setDouble(5, mapped.numericValue());
            statement.setNull(6, Types.VARCHAR);
        } else {
            statement.setNull(5, Types.DOUBLE);
            statement.setString(6, mapped.text());
        }
        statement.setString(7, point.quality());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            // 关闭失败不掩盖主流程结果：记录后继续（不吞异常信息）
            log.warn("[iot] 关闭时序写入资源失败：{}", ex.getMessage());
        }
    }
}
