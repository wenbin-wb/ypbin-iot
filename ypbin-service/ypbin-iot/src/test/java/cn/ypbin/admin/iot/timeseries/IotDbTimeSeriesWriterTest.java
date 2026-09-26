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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

/**
 * 写入器的 SQL 文本、分块、列绑定与失败语义（§5.2.1 写入路径）。
 *
 * <p><b>为什么拦 {@link DriverManager} 而不是连真库</b>：写入器只用 {@code java.sql.*}（IoTDB 驱动是
 * runtime 依赖），单测环境不该依赖真库。拦连接工厂即可断言「连了几次、发了什么语句、绑了什么值、
 * 失败怎么处理」；真库往返与 TTL 由容器 IT（{@code IotDbTimeSeriesIT}）覆盖。</p>
 *
 * <p><b>本类锁死的两条真库结论</b>（本机真库实测，2026-09-24，见 {@code IotDbTimeSeriesWriter} 类注释）：
 * ① 未用的值列**必须不出现在语句里**——显式 {@code setNull} 会被驱动拒绝
 * （{@code SQLException: The parameter cannot be null}），第一版因此真库全丢数据而单测全绿；
 * ② 插入路径的 {@code ?} 参数在真库可用，故写入侧仍用 {@link PreparedStatement}（只有查询侧不能用）。</p>
 *
 * <p><b>WARN 必须被断言</b>（2026-09-26 L2 复核的整改点）：上一轮的用例只断言指标，删掉两处 WARN 后
 * 18 个用例仍然全绿——「必须告警而不是静默」这条契约等于没有门禁。本类现在用 logback
 * {@link ListAppender} 捕获 WARN 并逐条断言：**删掉任一 WARN，对应用例必然转红**。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotDbTimeSeriesWriterTest {

    /** 表模型连接串必须带 {@code sql_dialect=table}（官方 JDBC 文档），并带库名（非限定表名的前提）。 */
    private static final String URL = "jdbc:iotdb://127.0.0.1:6667/ypbin_it?sql_dialect=table";

    /** 固定读数时刻（epoch 毫秒），避免用「当前时间」导致断言不稳定。 */
    private static final long TS = 1_700_000_000_000L;

    private ListAppender<ILoggingEvent> logAppender;

    private Logger writerLogger;

    @BeforeEach
    void attachLogCapture() {
        writerLogger = (Logger) LoggerFactory.getLogger(IotDbTimeSeriesWriter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        writerLogger.detachAppender(logAppender);
    }

    /** 捕获到的 WARN 文本（只要 WARN，避免「首次落库成功」的 INFO 干扰）。 */
    private List<String> warnMessages() {
        return logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    /** 数值行语句：只带 value_double（不含 value_text）。 */
    private static final String NUMERIC_SQL =
        "INSERT INTO reading(tenant_id, device_id, property_id, time, value_double, quality)"
            + " VALUES (?, ?, ?, ?, ?, ?)";

    /** 文本行语句：只带 value_text（不含 value_double）。 */
    private static final String TEXT_SQL =
        "INSERT INTO reading(tenant_id, device_id, property_id, time, value_text, quality)"
            + " VALUES (?, ?, ?, ?, ?, ?)";

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

    private static List<TimeSeriesPoint> points(int count, String value) {
        List<TimeSeriesPoint> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(point(value, TS + index));
        }
        return points;
    }

    @Test
    @DisplayName("★ 数值行语句只带 value_double（不含 value_text），TAG 转字符串、time 用 Timestamp")
    void mustUseNumericSqlWithoutTextColumn() throws SQLException {
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
            assertThat(sql.getValue()).isEqualTo(NUMERIC_SQL);
            assertThat(sql.getValue()).as("未用的值列必须不出现（真库 setNull 会被拒）").doesNotContain("value_text");

            verify(statement).setString(1, "9");
            verify(statement).setString(2, "100");
            verify(statement).setString(3, "temp");
            verify(statement).setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(TS)));
            verify(statement).setDouble(5, 23.5d);
            verify(statement).setString(6, "GOOD");
            verify(statement, never()).setString(eq(5), anyString());
            verify(statement, never()).setNull(anyInt(), anyInt());
        }
    }

    @Test
    @DisplayName("★ 文本行语句只带 value_text（不含 value_double），值走第 5 个参数")
    void mustUseTextSqlWithoutDoubleColumn() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(List.of(point("COOL", TS)));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sql.capture());
            assertThat(sql.getValue()).isEqualTo(TEXT_SQL);
            assertThat(sql.getValue()).as("未用的值列必须不出现").doesNotContain("value_double");

            verify(statement).setString(5, "COOL");
            verify(statement).setString(6, "GOOD");
            verify(statement, never()).setDouble(eq(5), anyDouble());
            verify(statement, never()).setNull(anyInt(), anyInt());
        }
    }

    @Test
    @DisplayName("★ bind 直接绑定：数值行写 setDouble(5)、文本行写 setString(5)，都不得 setNull")
    void mustBindExactlyOneValueColumn() throws SQLException {
        PreparedStatement numeric = mock(PreparedStatement.class);
        IotDbTimeSeriesWriter.bind(numeric, point("23.5", TS));
        verify(numeric).setDouble(5, 23.5d);
        verify(numeric, never()).setString(eq(5), anyString());
        verify(numeric, never()).setNull(anyInt(), anyInt());

        PreparedStatement text = mock(PreparedStatement.class);
        IotDbTimeSeriesWriter.bind(text, point("COOL", TS));
        verify(text).setString(5, "COOL");
        verify(text, never()).setDouble(eq(5), anyDouble());
        verify(text, never()).setNull(anyInt(), anyInt());
    }

    @Test
    @DisplayName("★ 混批：数值行与文本行各走各自的语句（顺序：先数值后文本），两次 executeBatch")
    void mustSplitMixedBatchIntoTwoStatements() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(List.of(point("23.5", TS), point("COOL", TS + 1)));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection, times(2)).prepareStatement(sql.capture());
            assertThat(sql.getAllValues()).containsExactly(NUMERIC_SQL, TEXT_SQL);
            verify(statement).setDouble(5, 23.5d);
            verify(statement).setString(5, "COOL");
            verify(statement, times(2)).addBatch();
            verify(statement, times(2)).executeBatch();
            verify(statement, never()).setNull(anyInt(), anyInt());
        }
    }

    @Test
    @DisplayName("★ 只有一类点位时不为空的那类建连接（空类不连库、不产生批量调用）")
    void mustNotConnectForEmptyKind() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(10), new SimpleMeterRegistry())
                .writeAll(points(3, "1"));

            verify(connection, times(1)).prepareStatement(anyString());
            verify(statement, times(1)).executeBatch();
        }
    }

    @Test
    @DisplayName("★ 按 batch-size 分块：5 条数值 + 批量 2 ⇒ 3 次 executeBatch、5 次 addBatch、3 次连接")
    void mustChunkByBatchSize() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(2), new SimpleMeterRegistry()).writeAll(points(5, "1"));

            verify(statement, times(5)).addBatch();
            verify(statement, times(3)).executeBatch();
            verify(connection, times(3)).prepareStatement(anyString());
            driverManager.verify(() -> DriverManager.getConnection(anyString(), any(Properties.class)),
                times(3));
        }
    }

    @Test
    @DisplayName("★ 两类混批按批大小分块：2 数值 + 2 文本 + 批量 1 ⇒ 4 次 executeBatch")
    void mustChunkEachKindIndependently() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            new IotDbTimeSeriesWriter(properties(1), new SimpleMeterRegistry()).writeAll(List.of(
                point("1", TS), point("COOL", TS + 1), point("2", TS + 2), point("HOT", TS + 3)));

            verify(statement, times(4)).addBatch();
            verify(statement, times(4)).executeBatch();
        }
    }

    @Test
    @DisplayName("★ batch-size 非法（<=0）归一到 1：守卫「按批切分」退化（0 会让分块循环死循环）")
    void mustNormalizeInvalidBatchSize() {
        // 直接断言归一结果而不是「跑 writeAll 看会不会挂」：后者一旦回归会让构建挂死（比红更难查）
        assertThat(new IotDbTimeSeriesWriter(properties(0), new SimpleMeterRegistry()).effectiveBatchSize())
            .isEqualTo(1);
        assertThat(new IotDbTimeSeriesWriter(properties(-5), new SimpleMeterRegistry()).effectiveBatchSize())
            .isEqualTo(1);
        assertThat(new IotDbTimeSeriesWriter(properties(7), new SimpleMeterRegistry()).effectiveBatchSize())
            .isEqualTo(7);
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
    @DisplayName("★ executeBatch 抛 SQLException 时只计数不抛（绝不让上报事务回滚）")
    void mustCountFailureWithoutThrowing() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SQLException failure = new SQLException("IoTDB 不可用");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenThrow(failure);

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(List.of(point("23.5", TS))))
                .doesNotThrowAnyException();

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("失败必须被计数（否则监控看不到静默丢数据）")
                .isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("★ 绑定期抛 RuntimeException（真驱动对 null 参数即抛 NPE）也只计数不抛")
    void mustCountRuntimeFailureWithoutThrowing() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            // 真库实测：IoTDBPreparedStatement.setString(i, null) 抛
            // NullPointerException: Cannot invoke "String.startsWith(String)" because "x" is null
            doThrow(new NullPointerException("driver 拒绝 null 参数"))
                .when(statement).setString(anyInt(), anyString());

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(List.of(point("COOL", TS))))
                .doesNotThrowAnyException();

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .isEqualTo(1.0d);
            verify(statement, never()).executeBatch();
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
            when(statement.executeBatch()).thenThrow(failure).thenReturn(new int[] {1});

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(1), registry).writeAll(points(2, "1")))
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

    @Test
    @DisplayName("★ 成功侧观测：attempted 与 rows 同时计数，实际写入行数可见")
    void mustCountAttemptedAndActualRows() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[] {1, 1});

            new IotDbTimeSeriesWriter(properties(10), registry).writeAll(points(2, "23.5"));

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ATTEMPTED).counter().count())
                .as("待写行数").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count())
                .as("实际写入行数").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("未失败").isZero();
        }
    }

    @Test
    @DisplayName("★★ 生产缺陷形状：驱动不抛异常却吞行 ⇒ rows < attempted（此前这种沉默完全不可见）")
    void mustExposeGapWhenDriverSwallowsRows() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            // 回执说明：一条明确失败、一条成功——驱动**没有抛异常**
            when(statement.executeBatch())
                .thenReturn(new int[] {Statement.EXECUTE_FAILED, Statement.SUCCESS_NO_INFO});

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(points(2, "23.5")))
                .as("契约：失败只计数不抛，绝不让上报事务回滚")
                .doesNotThrowAnyException();

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ATTEMPTED).counter().count())
                .as("待写").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count())
                .as("实际只写进 1 行——缺口必须从指标上看得见").isEqualTo(1d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
                .as("失败计数是**批次级**语义，行级吞行不得改它的口径").isZero();
            assertThat(warnMessages())
                .as("回执与提交数不符必须 WARN：删掉这条 WARN 本用例必须转红（上一轮的整改点）")
                .anySatisfy(message -> assertThat(message).contains("回执与提交数不符"));
        }
    }

    @Test
    @DisplayName("★★ 全 SUCCESS_NO_INFO：rows == attempted、缺口恒为 0 —— 故 rows 不能声称「实际落库」，真值只认库内对账")
    void mustCountNoInfoReceiptsAndExposeTheBlindSpot() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            // 探针实证的生产形状：驱动全部回 SUCCESS_NO_INFO（「语句成功、受影响行数未知」）
            when(statement.executeBatch())
                .thenReturn(new int[] {Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});

            new IotDbTimeSeriesWriter(properties(10), registry).writeAll(points(2, "23.5"));

            double rows = registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count();
            double noInfo = registry.get(IotDbTimeSeriesWriter.METRIC_ROWS_NO_INFO).counter().count();
            assertThat(rows).as("回执里非失败的行数").isEqualTo(2d);
            assertThat(noInfo).as("两行全是「受影响行数未知」").isEqualTo(2d);
            assertThat(rows - noInfo)
                .as("确认了行数的部分为 0：所以 rows 只说「驱动认了」，不等于「库里真有」")
                .isZero();
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS_UNKNOWN).counter().count())
                .as("不是 null 回执，未知行计数不得被计入").isZero();
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count()).isZero();
            assertThat(warnMessages())
                .as("全 SUCCESS_NO_INFO 不是「回执与提交数不符」（回执行数相同），不得误报")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("★ rows 上界钳制：回执条数多于本批条数时不得放大（2 行批 + 4 条回执 ⇒ rows=2）并告警")
    void mustClampRowsToBatchSize() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[] {1, 1, 1, 1});

            new IotDbTimeSeriesWriter(properties(10), registry).writeAll(points(2, "23.5"));

            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ATTEMPTED).counter().count())
                .as("待写 2 行").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count())
                .as("2 行的一批不可能写进 4 行：上界钳制到本批条数").isEqualTo(2d);
            assertThat(warnMessages())
                .as("回执长度 > 提交数同属「回执与提交数不符」，必须 WARN")
                .anySatisfy(message -> assertThat(message).contains("回执与提交数不符"));
        }
    }

    @Test
    @DisplayName("★ rows.noinfo 同样受上界钳制：不得出现 noinfo > rows（2 行批 + 3 条 SUCCESS_NO_INFO）")
    void mustClampNoInfoToAccountedRows() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(new int[] {Statement.SUCCESS_NO_INFO,
                Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});

            new IotDbTimeSeriesWriter(properties(10), registry).writeAll(points(2, "23.5"));

            double rows = registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count();
            double noInfo = registry.get(IotDbTimeSeriesWriter.METRIC_ROWS_NO_INFO).counter().count();
            assertThat(rows).isEqualTo(2d);
            assertThat(noInfo).as("noinfo 不得超过 rows").isEqualTo(2d);
        }
    }

    @Test
    @DisplayName("★ WARN 限流：窗口内只放行一条并累计被抑制数（长期违约 ≈30 条/分，不刷屏也不丢账）")
    void mustThrottleWarnWithinWindow() {
        AtomicLong clock = new AtomicLong(0L);
        IotDbTimeSeriesWriter.LogThrottle throttle = new IotDbTimeSeriesWriter.LogThrottle(
            IotDbTimeSeriesWriter.WARN_MIN_INTERVAL_MS, clock::get);

        assertThat(throttle.tryAcquire()).as("首条放行").isTrue();
        assertThat(throttle.tryAcquire()).as("窗口内第二条被抑制").isFalse();
        clock.addAndGet(Duration.ofMillis(IotDbTimeSeriesWriter.WARN_MIN_INTERVAL_MS).toNanos() - 1L);
        assertThat(throttle.tryAcquire()).as("窗口未满仍抑制").isFalse();
        assertThat(throttle.drainSuppressed()).as("被抑制的条数不得丢失").isEqualTo(2L);

        clock.addAndGet(1L);
        assertThat(throttle.tryAcquire()).as("窗口到期放行").isTrue();
        assertThat(throttle.drainSuppressed()).as("刚放行过，无新抑制").isZero();
    }

    @Test
    @DisplayName("★ 驱动违约返回 null 回执：不抛、按整批计数，但必须告警而非静默")
    void mustNotThrowOnNullReceipt() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeBatch()).thenReturn(null);

            assertThatCode(() -> new IotDbTimeSeriesWriter(properties(10), registry)
                .writeAll(points(2, "23.5")))
                .doesNotThrowAnyException();
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS).counter().count())
                .as("驱动违约时保持既有的「未抛即成功」语义").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS_UNKNOWN).counter().count())
                .as("但这两行必须单列为「落库情况未知」，不得与驱动确认过的行混为一谈").isEqualTo(2d);
            assertThat(registry.get(IotDbTimeSeriesWriter.METRIC_ROWS_NO_INFO).counter().count())
                .as("null 回执不是 SUCCESS_NO_INFO，不得计入 noinfo").isZero();
            assertThat(warnMessages())
                .as("驱动违约必须 WARN：删掉这条 WARN 本用例必须转红（上一轮的整改点）")
                .anySatisfy(message -> assertThat(message).contains("未按 JDBC 契约返回"));
        }
    }
}
