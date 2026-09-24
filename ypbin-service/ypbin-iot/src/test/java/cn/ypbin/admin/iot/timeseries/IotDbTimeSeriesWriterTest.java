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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 写入器的 SQL 文本、分块、列绑定与失败语义（§5.2.1 写入路径）。
 *
 * <p><b>为什么拦 {@link DriverManager} 而不是连真库</b>：写入器只用 {@code java.sql.*}（IoTDB 驱动是
 * runtime 依赖），单测环境没有真库也不该依赖真库。拦连接工厂即可断言「连了几次、发了什么语句、
 * 绑了什么值、失败怎么处理」；真库往返与 TTL 由容器 IT（{@code IotDbTimeSeriesIT}）覆盖。</p>
 *
 * <p><b>断言的口径</b>：SQL 文本与列顺序必须与 §5.2.1 的 DDL 逐字一致（列清单↔绑定下标靠位置对应，
 * 顺序错了不会编译报错、只会静默写错列）；「永不双写」（数值行文本列显式 NULL、反之亦然）是
 * 查询侧「数值列优先、文本列兜底」能成立的前提。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotDbTimeSeriesWriterTest {

    /** 表模型连接串必须带 {@code sql_dialect=table}（官方 JDBC 文档）。 */
    private static final String URL = "jdbc:iotdb://127.0.0.1:6667?sql_dialect=table";

    /** 固定读数时刻（epoch 毫秒），避免用「当前时间」导致断言不稳定。 */
    private static final long TS = 1_700_000_000_000L;

    /** 期望的 INSERT 文本（表名默认 {@code reading}，列顺序与 DDL 一致）。 */
    private static final String EXPECTED_SQL =
        "INSERT INTO reading(tenant_id, device_id, property_id, time, value_double, value_text, quality)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static TimeSeriesProperties properties(int batchSize) {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        properties.setUrl(URL);
        properties.setUsername("it_user");
        properties.setPassword("it_pass");
        properties.setBatchSize(batchSize);
        return properties;
    }

    private static TimeSeriesPoint point(String value, long ts) {
        return new TimeSeriesPoint(9L, 100L, "temp", value, "GOOD", ts);
    }

    private static List<TimeSeriesPoint> points(int count) {
        List<TimeSeriesPoint> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(point(String.valueOf(index), TS + index));
        }
        return points;
    }

    @Test
    @DisplayName("★ SQL 文本固定：带列清单的多行 INSERT，表名来自配置（列顺序与 DDL 一致）")
    void mustBuildInsertSqlWithDeclaredColumnOrder() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(List.of(point("23.5", TS)));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sql.capture());
            assertThat(sql.getValue())
                .as("列顺序决定了 ? 下标与列的对应关系，错位不会编译报错、只会静默写错列")
                .isEqualTo(EXPECTED_SQL);
        }
    }

    @Test
    @DisplayName("★ 表名可配：INSERT 拼上配置的表名（白名单校验在装配侧）")
    void mustUseConfiguredTableName() throws SQLException {
        TimeSeriesProperties properties = properties(10);
        properties.setTableName("reading_v2");
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties, new SimpleMeterRegistry())
                .writeAll(List.of(point("1", TS)));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sql.capture());
            assertThat(sql.getValue()).startsWith("INSERT INTO reading_v2(");
        }
    }

    @Test
    @DisplayName("★ 连接参数：url 与 user/password 全部来自配置（不硬编码）")
    void mustPassConfiguredCredentials() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(List.of(point("1", TS)));

            ArgumentCaptor<Properties> credentials = ArgumentCaptor.forClass(Properties.class);
            driverManager.verify(() -> DriverManager.getConnection(eq(URL), credentials.capture()));
            assertThat(credentials.getValue())
                .containsEntry("user", "it_user")
                .containsEntry("password", "it_pass");
        }
    }

    @Test
    @DisplayName("★ 按 batch-size 分块：5 条 + 批量 2 ⇒ 3 次 executeBatch、5 次 addBatch、3 次连接")
    void mustChunkByBatchSize() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(2), new SimpleMeterRegistry()).writeAll(points(5));

            verify(statement, times(5)).addBatch();
            verify(statement, times(3)).executeBatch();
            verify(connection, times(3)).prepareStatement(anyString());
            driverManager.verify(() -> DriverManager.getConnection(anyString(), any(Properties.class)),
                times(3));
        }
    }

    @Test
    @DisplayName("★ 数值行只写 value_double（文本列显式 NULL）、文本行反之：永不双写")
    void mustBindExactlyOneValueColumn() throws SQLException {
        PreparedStatement numeric = mock(PreparedStatement.class);
        IotDbTimeSeriesWriter.bind(numeric, point("23.5", TS));
        verify(numeric).setDouble(5, 23.5d);
        verify(numeric).setNull(6, Types.VARCHAR);
        verify(numeric, never()).setString(eq(6), anyString());
        verify(numeric, never()).setNull(eq(5), anyInt());
        verify(numeric, never()).setDouble(eq(6), anyDouble());

        PreparedStatement text = mock(PreparedStatement.class);
        IotDbTimeSeriesWriter.bind(text, point("COOL", TS));
        verify(text).setNull(5, Types.DOUBLE);
        verify(text).setString(6, "COOL");
        verify(text, never()).setDouble(eq(5), anyDouble());
        verify(text, never()).setString(eq(5), anyString());
        verify(text, never()).setNull(eq(6), anyInt());
    }

    @Test
    @DisplayName("★ 绑定：TAG 列转字符串、time 用 Timestamp、quality 透传")
    void mustBindTagsTimestampAndQuality() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        IotDbTimeSeriesWriter.bind(statement, point("23.5", TS));

        verify(statement).setString(1, "9");
        verify(statement).setString(2, "100");
        verify(statement).setString(3, "temp");
        verify(statement).setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(TS)));
        verify(statement).setString(7, "GOOD");
    }

    @Test
    @DisplayName("★ 一条文本行、一条数值行混批时各自绑各自的列（不串列）")
    void mustBindMixedBatchByRow() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(List.of(point("23.5", TS), point("COOL", TS + 1)));

            verify(statement).setDouble(5, 23.5d);
            verify(statement).setNull(5, Types.DOUBLE);
            verify(statement).setString(6, "COOL");
            verify(statement).setNull(6, Types.VARCHAR);
        }
    }

    @Test
    @DisplayName("★ executeBatch 抛 SQLException 时只计数不抛（绝不让上报事务回滚）")
    void mustCountFailureWithoutThrowing() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 异常必须在静态 mock 之外构造：SQLException 的构造器会调 DriverManager.getLogWriter()，
        // 在 mock 作用域内、且处在 when(...) 的插桩过程中构造它会被当成「未完成的 stubbing」
        SQLException failure = new SQLException("IoTDB 不可用");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenThrow(failure);

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(List.of(point("23.5", TS))))
                .as("写入失败只影响历史曲线，不得让上报事务回滚")
                .doesNotThrowAnyException();

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("失败必须被计数（否则监控看不到静默丢数据）")
                .isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("★ 前一个分块失败不影响后续分块（失败只按分块计数）")
    void mustKeepWritingFollowingChunksAfterFailure() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SQLException failure = new SQLException("第一批失败");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch())
                .thenThrow(failure)
                .thenReturn(new int[] {1});

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(1), registry).writeAll(points(2)))
                .doesNotThrowAnyException();

            verify(statement, times(2)).executeBatch();
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("只有失败的那个分块计数")
                .isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("★ 连接建立失败也只计数不抛（连不上库不得影响上报）")
    void mustCountConnectionFailureWithoutThrowing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SQLException failure = new SQLException("连不上");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(failure);

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(List.of(point("23.5", TS))))
                .doesNotThrowAnyException();

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("★ 空集合不连库、不产生任何批量调用（零开销短路）")
    void mustNotTouchDatabaseForEmptyInput() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            new IotDbTimeSeriesWriter(properties(10), registry).writeAll(List.of());

            driverManager.verifyNoInteractions();
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("空集合不是失败：不得计数")
                .isZero();
        }
    }
}
