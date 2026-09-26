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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 「库内真值对账」探针（{@link IotDbRowCountProbe}）用例。
 *
 * <p><b>为什么这是本轮最关键的一条指标</b>：写入侧的 attempted/rows 全部来自驱动回执，
 * 而回执 {@code SUCCESS_NO_INFO} 只表示「受影响行数未知」⇒ 全 {@code SUCCESS_NO_INFO} 时
 * {@code rows == attempted}、缺口恒为 0，「ack 了但一行没落库」在写入侧指标上**不可见**。
 * 唯一能测到库内真值的就是本探针的 {@code SELECT COUNT(*)}。</p>
 *
 * <p><b>断言取向</b>：① 取值必须来自查询结果（不是常量）——同一实例第二次探测换成别的数字必须跟着变；
 * ② 未测到之前必须是哨兵 {@code -1} 而**不是 0**（0 是合法真值，会把「没测」误读成「空库」）；
 * ③ 失败/空结果集只计数不抛，且**绝不**把失败写成 0。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
class IotDbRowCountProbeTest {

    private static final String URL = "jdbc:iotdb://127.0.0.1:6667/iot?sql_dialect=table";

    private static final String EXPECTED_SQL = "SELECT COUNT(*) FROM reading";

    private static TimeSeriesProperties properties() {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        properties.setUrl(URL);
        properties.setUsername("it_user");
        properties.setPassword("it_pass");
        properties.setEnabled(true);
        return properties;
    }

    private static double gauge(SimpleMeterRegistry registry) {
        return registry.get(IotDbRowCountProbe.METRIC_DB_ROWS).gauge().value();
    }

    private static double probeFailures(SimpleMeterRegistry registry) {
        return registry.get(IotDbRowCountProbe.METRIC_PROBE_FAILED).counter().count();
    }

    @Test
    @DisplayName("★ 真值来自查询结果：SELECT COUNT(*) 的返回值进 gauge，SQL 与超时都按配置（非空实现）")
    void mustExposeRealCountFromQuery() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getLong(1)).thenReturn(4991L);

            new IotDbRowCountProbe(properties(), registry).probeOnce();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(statement).executeQuery(sql.capture());
            assertThat(sql.getValue())
                .as("对账语句必须是全表计数（表名来自配置）").isEqualTo(EXPECTED_SQL);
            verify(statement).setQueryTimeout(properties().getDbRowsProbeQueryTimeoutSeconds());
            assertThat(gauge(registry)).as("库内真值").isEqualTo(4991d);
            assertThat(probeFailures(registry)).isZero();
        }
    }

    @Test
    @DisplayName("★ 取值不是常量：第二次探测换成别的数字，gauge 必须跟着变")
    void mustRefreshGaugeOnEveryProbe() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(first, second);
            when(first.next()).thenReturn(true);
            when(first.getLong(1)).thenReturn(4991L);
            when(second.next()).thenReturn(true);
            when(second.getLong(1)).thenReturn(5002L);

            IotDbRowCountProbe probe = new IotDbRowCountProbe(properties(), registry);
            probe.probeOnce();
            assertThat(gauge(registry)).isEqualTo(4991d);
            probe.probeOnce();
            assertThat(gauge(registry)).as("gauge 必须反映最新一次真实查询结果").isEqualTo(5002d);
            verify(statement, times(2)).executeQuery(anyString());
        }
    }

    @Test
    @DisplayName("★ 未测得之前是哨兵 -1（不是 0）：0 是合法真值，不能拿它当「没测」")
    void mustStartWithUnknownSentinel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new IotDbRowCountProbe(properties(), registry);

        assertThat(IotDbRowCountProbe.ROW_COUNT_UNKNOWN).isEqualTo(-1L);
        assertThat(gauge(registry)).isEqualTo(-1d);
    }

    @Test
    @DisplayName("★ 查询失败只计数不抛，且保留上一次取值（绝不把失败写成 0）")
    void mustCountFailureWithoutThrowingAndKeepLastValue() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString()))
                .thenReturn(resultSet)
                .thenThrow(new SQLException("IoTDB 不可用"));
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getLong(1)).thenReturn(4991L);

            IotDbRowCountProbe probe = new IotDbRowCountProbe(properties(), registry);
            probe.probeOnce();
            assertThat(gauge(registry)).isEqualTo(4991d);

            assertThatCode(probe::probeOnce).doesNotThrowAnyException();
            assertThat(probeFailures(registry)).as("失败必须被计数（否则对账失效无人知）").isEqualTo(1d);
            assertThat(gauge(registry))
                .as("失败不得把真值写成 0：保留上一次成功取值").isEqualTo(4991d);
        }
    }

    @Test
    @DisplayName("★ 连接失败也只计数不抛，gauge 停在哨兵值")
    void mustCountConnectionFailureWithoutThrowing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 异常必须先建好：SQLException 的构造函数会碰 DriverManager（被静态 mock 了），
        // 在 thenThrow(...) 的参数里 new 会让 Mockito 判成「stubbing 未完成」
        SQLException failure = new SQLException("连不上");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(failure);

            assertThatCode(() -> new IotDbRowCountProbe(properties(), registry).probeOnce())
                .doesNotThrowAnyException();
            assertThat(probeFailures(registry)).isEqualTo(1d);
            assertThat(gauge(registry)).isEqualTo((double) IotDbRowCountProbe.ROW_COUNT_UNKNOWN);
        }
    }

    @Test
    @DisplayName("★ 结果集为空按失败处理（COUNT 必回一行；没读到 ≠ 库里 0 行）")
    void mustTreatEmptyResultSetAsFailure() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);

            assertThatCode(() -> new IotDbRowCountProbe(properties(), registry).probeOnce())
                .doesNotThrowAnyException();
            assertThat(probeFailures(registry)).isEqualTo(1d);
            assertThat(gauge(registry))
                .as("绝不能把「没读到」当成 0 行").isEqualTo((double) IotDbRowCountProbe.ROW_COUNT_UNKNOWN);
            verify(resultSet, never()).getLong(anyInt());
        }
    }

    @Test
    @DisplayName("★ 表名可配：对账语句用的是配置的表名")
    void mustUseConfiguredTableName() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeSeriesProperties properties = properties();
        properties.setTableName("reading_v2");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getLong(1)).thenReturn(7L);

            new IotDbRowCountProbe(properties, registry).probeOnce();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(statement).executeQuery(sql.capture());
            assertThat(sql.getValue()).isEqualTo("SELECT COUNT(*) FROM reading_v2");
        }
    }

    @Test
    @DisplayName("★ 低频：@Scheduled 的默认间隔/首测延迟必须等于具名常量（防「注解默认值」与常量两处漂移）")
    void scheduledDefaultsMustMatchConstants() throws NoSuchMethodException {
        Scheduled scheduled = IotDbRowCountProbe.class.getMethod("probeOnce")
            .getAnnotation(Scheduled.class);
        assertThat(scheduled).as("探针必须由 @Scheduled 驱动").isNotNull();
        // 必须**精确相等**：只用 contains(...) 时把 600000 改成 6000000 / 把 60000 改成 600001 仍「包含」
        // 子串而假绿（2026-09-26 L2 复核用这两个变异实证过 —— 这条断言当时是假门禁）
        assertThat(scheduled.fixedDelayString()).isEqualTo(
            "${ypbin.timeseries.db-rows-probe-interval-ms:" + IotDbRowCountProbe.DEFAULT_PROBE_INTERVAL_MS + "}");
        assertThat(scheduled.initialDelayString()).isEqualTo(
            "${ypbin.timeseries.db-rows-probe-initial-delay-ms:"
                + IotDbRowCountProbe.DEFAULT_PROBE_INITIAL_DELAY_MS + "}");
        assertThat(new TimeSeriesProperties().getDbRowsProbeIntervalMs())
            .as("配置默认值也必须等于同一常量").isEqualTo(IotDbRowCountProbe.DEFAULT_PROBE_INTERVAL_MS);
        assertThat(IotDbRowCountProbe.DEFAULT_PROBE_INTERVAL_MS)
            .as("真值对账必须低频（>=5 分钟）").isGreaterThanOrEqualTo(300_000L);
    }

    @Test
    @DisplayName("★ 驱动对 setQueryTimeout 抛 RuntimeException（不支持超时）时也只计数不抛、保留旧值")
    void mustCountRuntimeFailureOnUnsupportedTimeout() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        doThrow(new UnsupportedOperationException("driver 不支持查询超时"))
            .when(statement).setQueryTimeout(anyInt());
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);

            // 本仓钉死的驱动实现了 setQueryTimeout，但「换驱动后不支持」这条路径必须有覆盖——
            // 否则升级驱动后会以「每轮对账都失败」的形式出现，而单测全绿
            assertThatCode(() -> new IotDbRowCountProbe(properties(), registry).probeOnce())
                .doesNotThrowAnyException();
            assertThat(probeFailures(registry)).isEqualTo(1d);
            assertThat(gauge(registry)).isEqualTo((double) IotDbRowCountProbe.ROW_COUNT_UNKNOWN);
            verify(statement, never()).executeQuery(anyString());
        }
    }
}
