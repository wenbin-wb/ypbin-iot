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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.starter.core.exception.BusinessException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 查询存储的 SQL 文本、列取值、注入防护与失败语义（§5.2.1 查询路径）。
 *
 * <p>三条口径在这里被钉住：① <b>数值列优先、文本列兜底</b>（写入侧「永不双写」的配套读法，
 * 若拿 0.0 当「无值」就会读出假真值）；② 时间范围缺省用哨兵（0 / {@link Long#MAX_VALUE}）；
 * ③ <b>失败必须抛</b>（{@link BusinessException}），绝不能返回空列表——空列表会被读成「这段时间没数据」。</p>
 *
 * <p><b>为什么是字面量 SQL 而不是 {@code ?}</b>（真库实测，见 {@code IotDbTimeSeriesStore} 类注释）：
 * 驱动对 {@code ?} 做无引号文本替换 ⇒ {@code tenant_id = ?} 报 {@code 701 STRING = INT32}、
 * {@code property_id = ?} 报 {@code 616 Column 'x' cannot be resolved}。因此本测试断言的是
 * <b>拼出来的完整 SQL 文本</b>，并额外覆盖 {@link IotDbTimeSeriesStore#propertyIdLiteral(String)}
 * 的白名单拒绝（注入防护的第一道门）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotDbTimeSeriesStoreTest {

    /** 表模型连接串必须带 {@code sql_dialect=table}（官方 JDBC 文档），并带库名（非限定表名的前提）。 */
    private static final String URL = "jdbc:iotdb://127.0.0.1:6667/ypbin_it?sql_dialect=table";

    /** 读数时刻（epoch 毫秒）。 */
    private static final long TS = 1_700_000_000_000L;

    private static final Long TENANT_ID = 9L;

    private static final Long DEVICE_ID = 100L;

    /** 一条查询链路上的三个 mock（每用例一份，避免跨用例串味）。 */
    private record Stubs(Connection connection, Statement statement) {
    }

    private static TimeSeriesProperties properties() {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        properties.setUrl(URL);
        return properties;
    }

    private static Stubs stubs(ResultSet resultSet) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        return new Stubs(connection, statement);
    }

    /** 期望的 SQL（列名/顺序 + 字面量引号 + LIMIT 全在这里） */
    private static String expectedSql(String propertyIdLiteral, long from, long to, int limit) {
        return "SELECT time, value_double, value_text, quality FROM reading"
            + " WHERE tenant_id = '9' AND device_id = '100' AND property_id = " + propertyIdLiteral
            + " AND time >= " + from + " AND time <= " + to
            + " ORDER BY time ASC LIMIT " + limit;
    }

    /** 一行结果：数值行（numeric=true，text 忽略）或文本行（numeric=false）。 */
    private static ResultSet singleRow(boolean numeric, String text) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getTimestamp("time")).thenReturn(Timestamp.from(Instant.ofEpochMilli(TS)));
        when(resultSet.getDouble("value_double")).thenReturn(numeric ? 23.5d : 0.0d);
        when(resultSet.wasNull()).thenReturn(!numeric);
        when(resultSet.getString("value_text")).thenReturn(text);
        when(resultSet.getString("quality")).thenReturn("GOOD");
        when(resultSet.next()).thenReturn(true, false);
        return resultSet;
    }

    @Test
    @DisplayName("★ SQL 文本：TAG 字面量带单引号、time 用 epoch 毫秒、LIMIT 内联（真驱动不吃 ? ）")
    void mustBuildLiteralSqlForNumericRow() throws SQLException {
        Stubs stubs = stubs(singleRow(true, null));
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(stubs.connection());

            List<TimeSeriesPointResp> points =
                new IotDbTimeSeriesStore(properties()).query(TENANT_ID, DEVICE_ID, "temp", TS, TS, 500);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(stubs.statement()).executeQuery(sql.capture());
            assertThat(sql.getValue()).isEqualTo(expectedSql("'temp'", TS, TS, 500));
            assertThat(points).hasSize(1);
            assertThat(points.get(0).ts()).as("时间戳必须还原为 epoch 毫秒").isEqualTo(TS);
            assertThat(points.get(0).value()).isEqualTo("23.5");
            assertThat(points.get(0).quality()).isEqualTo("GOOD");
        }
    }

    @Test
    @DisplayName("★ 文本列兜底：value_double 为 NULL 时取 value_text（不把 0.0 当读数）")
    void mustFallBackToTextColumn() throws SQLException {
        Stubs stubs = stubs(singleRow(false, "COOL"));
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(stubs.connection());

            List<TimeSeriesPointResp> points =
                new IotDbTimeSeriesStore(properties()).query(TENANT_ID, DEVICE_ID, "mode", TS, TS, 10);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value())
                .as("wasNull=true ⇒ 必须走文本列，否则会读出 0.0 这种假真值")
                .isEqualTo("COOL");
        }
    }

    @Test
    @DisplayName("★ from/to 为 null 时用哨兵（0 / Long.MAX_VALUE）")
    void mustUseSentinelsForMissingRange() throws SQLException {
        Stubs stubs = stubs(mock(ResultSet.class));
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(stubs.connection());

            new IotDbTimeSeriesStore(properties()).query(TENANT_ID, DEVICE_ID, "temp", null, null, 10);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(stubs.statement()).executeQuery(sql.capture());
            assertThat(sql.getValue())
                .isEqualTo(expectedSql("'temp'", IotDbTimeSeriesStore.MIN_TS, Long.MAX_VALUE, 10));
        }
    }

    @Test
    @DisplayName("★ 点位标识字面量：白名单内原样加引号，单引号按字面量规则转义")
    void mustQuotePropertyIdLiteral() {
        assertThat(IotDbTimeSeriesStore.propertyIdLiteral("temp")).isEqualTo("'temp'");
        assertThat(IotDbTimeSeriesStore.propertyIdLiteral("a.b:c-1_2")).isEqualTo("'a.b:c-1_2'");
        // 白名单已排除单引号；转义是纵深防御，这里直接验证「校验 + 转义」两道都不放行注入形态
        assertThatThrownBy(() -> IotDbTimeSeriesStore.propertyIdLiteral("temp' OR '1'='1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("点位标识不合法");
    }

    @Test
    @DisplayName("★ 非法点位标识（注入形态）在建连接之前就被拒（不静默过滤、不碰库）")
    void mustRejectUnsafePropertyIdBeforeTouchingDatabase() {
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            assertThatThrownBy(() -> new IotDbTimeSeriesStore(properties())
                .query(TENANT_ID, DEVICE_ID, "temp'; DROP TABLE reading; --", null, null, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("点位标识不合法");
            driverManager.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("★ 无数据返回空列表（绝不返回 null）")
    void mustReturnEmptyListWhenNoRows() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        Stubs stubs = stubs(empty);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(stubs.connection());

            List<TimeSeriesPointResp> points =
                new IotDbTimeSeriesStore(properties()).query(TENANT_ID, DEVICE_ID, "temp", null, null, 10);
            assertThat(points).isEmpty();
        }
    }

    @Test
    @DisplayName("★ 查询失败抛 BusinessException（不得返回空列表假装「这段时间没数据」）")
    void mustThrowBusinessExceptionOnSqlFailure() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("IoTDB 挂了"));
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);

            assertThatThrownBy(() -> new IotDbTimeSeriesStore(properties())
                .query(TENANT_ID, DEVICE_ID, "temp", null, null, 10))
                .as("查询失败必须如实报错：空列表会被读成「这段时间没数据」")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("历史时序查询失败");
            verify(statement, times(1)).executeQuery(anyString());
        }
    }

    @Test
    @DisplayName("★ 连接失败同样抛 BusinessException（不静默降级为空）")
    void mustThrowBusinessExceptionOnConnectFailure() {
        SQLException failure = new SQLException("连不上");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(failure);

            assertThatThrownBy(() -> new IotDbTimeSeriesStore(properties())
                .query(TENANT_ID, DEVICE_ID, "temp", null, null, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("历史时序查询失败");
        }
    }

    @Test
    @DisplayName("存储可用性：IoTDB 实现恒为可用（不可用语义由 UnavailableTimeSeriesStore 承担）")
    void mustBeAvailable() {
        assertThat(new IotDbTimeSeriesStore(properties()).available()).isTrue();
    }

    @Test
    @DisplayName("★ formatNumeric：整数不带小数点，超出 double 精确整数区间退回科学计数法")
    void formatNumericMustKeepIntegerForm() {
        assertThat(IotDbTimeSeriesStore.formatNumeric(23.0d)).isEqualTo("23");
        assertThat(IotDbTimeSeriesStore.formatNumeric(-7.0d)).isEqualTo("-7");
        assertThat(IotDbTimeSeriesStore.formatNumeric(23.5d)).isEqualTo("23.5");
        assertThat(IotDbTimeSeriesStore.formatNumeric(9.007199254740992E15d))
            .as("2^53 本身可精确表示 ⇒ 仍按整数值输出")
            .isEqualTo("9007199254740992");
        assertThat(IotDbTimeSeriesStore.formatNumeric(9.007199254740994E15d))
            .as("超过 2^53 不再假装是精确整数（退回科学计数法）")
            .isEqualTo("9.007199254740994E15");
        assertThat(IotDbTimeSeriesStore.formatNumeric(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(IotDbTimeSeriesStore.formatNumeric(Double.NaN)).isEqualTo("NaN");
    }

    @Test
    @DisplayName("★ 不再使用 PreparedStatement（? 在真库对 TAG 谓词不可用）")
    void mustNotPrepareStatements() throws SQLException {
        Stubs stubs = stubs(mock(ResultSet.class));
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(stubs.connection());

            new IotDbTimeSeriesStore(properties()).query(TENANT_ID, DEVICE_ID, "temp", TS, TS, 10);

            verify(stubs.connection(), never()).prepareStatement(anyString());
        }
    }
}
