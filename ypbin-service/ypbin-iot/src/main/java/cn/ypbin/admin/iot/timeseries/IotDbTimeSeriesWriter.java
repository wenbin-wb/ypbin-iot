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
import java.time.Instant;
import java.util.ArrayList;
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
 * <p><b>为什么是「两条语句」而不是「一条语句 + 未用列写 NULL」</b>（本机真库实测，2026-09-24，
 * 容器 {@code apache/iotdb:2.0.11-standalone} + 驱动 {@code iotdb-jdbc:2.0.1-beta}）：
 * 官方语义是「INSERT 未指定的列自动填 {@code null}」，而显式 {@code setNull} 会被驱动直接拒绝
 * （{@code SQLException: The parameter cannot be null}，{@code IoTDBPreparedStatement.setNull}）——
 * 第一版按「显式 NULL 未用列」写，真库上**数值行/文本行全部写入失败**（只计数不抛，所以单测全绿、
 * 真库全丢，是最隐蔽的一类缺陷）。因此数值行只带 {@code value_double}、文本行只带 {@code value_text}，
 * 未用列**不出现在语句里**；两条语句各按 {@code batch-size} 分块，插入路径的 {@code ?} 参数在真库实测可用
 * （{@code executeUpdate} 正常，含 STRING TAG 列）。</p>
 *
 * <p><b>同刻重复写会更新原值</b>（官方 Write &amp; Update Data §1.1 第 6 条），因此不做批内去重。</p>
 *
 * <p><b>失败语义</b>：任何异常（含驱动对 null 参数抛出的 {@code NullPointerException}，
 * 实测 {@code setString(i, null)} 即如此）都**只计数 + error 日志，绝不外抛**——本类的契约是
 * 「时序写入绝不拖垮上报事务」（{@link TimeSeriesWriter} 类注释）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class IotDbTimeSeriesWriter implements TimeSeriesWriter {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesWriter.class);

    /** 写入失败计数（按批次计）。 */
    public static final String METRIC_FAILED = "iot.timeseries.write.failed";

    /** 数值行列清单：不含 {@code value_text}（未用列必须不出现，见类注释的实测依据）。 */
    private static final String COLUMNS_NUMERIC =
        "(tenant_id, device_id, property_id, time, value_double, quality)";

    /** 文本行列清单：不含 {@code value_double}。 */
    private static final String COLUMNS_TEXT = "(tenant_id, device_id, property_id, time, value_text, quality)";

    /** 六个占位符（两条语句列数相同：TAG×3 + time + 值列 + quality）。 */
    private static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?)";

    /** 批量下界（配置非法时的防御性归一：0/负数会让「按批切分」退化甚至死循环）。 */
    private static final int MIN_BATCH_SIZE = 1;

    private final TimeSeriesProperties properties;
    private final Counter failedCounter;

    public IotDbTimeSeriesWriter(TimeSeriesProperties properties, MeterRegistry meterRegistry) {
        // 本类可被直接构造（不经 IotTimeSeriesConfiguration）⇒ 构造时确保驱动已注册（幂等、无副作用）
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
        // 两条语句各写一类：数值行与文本行分属不同语句（列清单不同），互不影响
        writeKind(points, true, COLUMNS_NUMERIC);
        writeKind(points, false, COLUMNS_TEXT);
    }

    /**
     * 写一类（数值或文本）：按 {@code batch-size} 分块，每块一次 {@code executeBatch}。
     *
     * @param points   待写入读数（全量；本方法自行按类型过滤）
     * @param numeric  true 写数值行，false 写文本行
     * @param columns  该类的列清单
     */
    private void writeKind(List<TimeSeriesPoint> points, boolean numeric, String columns) {
        String sql = "INSERT INTO " + properties.getTableName() + columns + VALUES;
        int batchSize = effectiveBatchSize();
        List<TimeSeriesPoint> bucket = new ArrayList<>(batchSize);
        for (TimeSeriesPoint point : points) {
            if (ReadingValueMapper.map(point.value()).numeric() != numeric) {
                continue;
            }
            bucket.add(point);
            if (bucket.size() >= batchSize) {
                writeChunk(sql, bucket);
                bucket = new ArrayList<>(batchSize);
            }
        }
        if (!bucket.isEmpty()) {
            writeChunk(sql, bucket);
        }
    }

    /** 生效批量：配置非法时归一到 1 行一批（配置门禁在 {@code IotTimeSeriesConfiguration}，这里是防死循环兜底）。 */
    int effectiveBatchSize() {
        int configured = properties.getBatchSize();
        return configured < MIN_BATCH_SIZE ? MIN_BATCH_SIZE : configured;
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
        } catch (SQLException | RuntimeException ex) {
            // 失败只计数不抛：含 SQLException 与驱动对 null 参数抛的 RuntimeException
            // （实测 setString(i, null) → NPE）——契约是「绝不让上报事务回滚」
            failedCounter.increment();
            log.error("[iot] 时序写入失败（已计数，不影响上报落库）：表={} 条数={}",
                LogSanitizer.sanitize(properties.getTableName()), chunk.size(), ex);
        } finally {
            closeQuietly(statement);
            closeQuietly(connection);
        }
    }

    /**
     * 绑定一行（列清单由调用方选定的两条语句之一；下标 5 = 值列、6 = quality）。
     *
     * <p>「永不双写」由 {@link ReadingValueMapper} + 调用方选语句共同保证：数值行走
     * {@link #COLUMNS_NUMERIC}（第 5 个参数是 {@code value_double}），文本行走 {@link #COLUMNS_TEXT}
     * （第 5 个参数是 {@code value_text}）；未用的值列**不在语句里**，由 IoTDB 自动填 null。</p>
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
        } else {
            statement.setString(5, mapped.text());
        }
        statement.setString(6, point.quality());
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
