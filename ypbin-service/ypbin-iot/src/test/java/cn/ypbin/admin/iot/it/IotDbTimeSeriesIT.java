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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.ypbin.admin.iot.timeseries.IotDbDriverRegistrar;
import cn.ypbin.admin.iot.timeseries.IotDbTimeSeriesStore;
import cn.ypbin.admin.iot.timeseries.IotDbTimeSeriesWriter;
import cn.ypbin.admin.iot.timeseries.IotTimeSeriesConfiguration;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPoint;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPointResp;
import cn.ypbin.admin.iot.timeseries.TimeSeriesProperties;
import cn.ypbin.admin.iot.timeseries.TimeSeriesStore;
import cn.ypbin.admin.iot.timeseries.TimeSeriesWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB 表模型时序读写的**容器集成测试**（§5.2.1 的「需要实例/容器」验证计划）。
 *
 * <p><b>它补的洞</b>：单测只能证明「语句文本与绑定值是我们想要的」，证明不了「IoTDB 认这句话」——
 * 建表语法、{@code sql_dialect=table} 兼容性、TAG 过滤、同刻重写语义、表级 TTL 是否真的落库，
 * 四者都只能在真库上验证，也正是 {@code IotTimeSeriesConfiguration} 里
 * 「启用 ⇒ 装配 IoTDB 实现 + 驱动自检」这条断言为何被单测显式让给容器 IT 的原因。</p>
 *
 * <p><b>怎么跑</b>：默认由 {@link EnabledIfIotDbAvailable} 门控（外部实例优先、Docker 容器回退、
 * 都没有则跳过并打印原因）。容器模式用官方镜像
 * {@code apache/iotdb:2.0.11-standalone}（tag 的一手来源见
 * {@link IotDbIntegrationTestSupport} 的类注释），由
 * {@code mvn -Pit -pl ypbin-service/ypbin-iot -am verify -Dsurefire.skip=true} 触发。</p>
 *
 * <p><b>本机实测（2026-09-24，Docker + 官方镜像，非 CI）</b>：{@code Tests run: 5, Failures: 0, Errors: 0,
 * Skipped: 0}；观察到 {@code [iot-it] IoTDB 容器已启动：jdbc:iotdb://localhost:32852?sql_dialect=table}
 * 与 {@code [iot-it] 用例库已就绪：ypbin_it_…（表 reading，TTL 90 天）}。
 * 首次真跑暴露的两个**主源码缺陷**（{@code setNull} 被驱动拒绝、查询 {@code ?} 不可用）见
 * {@code IotDbTimeSeriesWriter}/{@code IotDbTimeSeriesStore} 类注释；容器侧还必须
 * {@code dn_rpc_address=0.0.0.0}，否则发布端口不通（见 {@link IotDbIntegrationTestSupport}）。</p>
 *
 * <p><b>已验证的语法依据</b>（Apache IoTDB 官方文档，访问 2026-09-24）：DDL
 * {@code CREATE TABLE <t>(... STRING TAG / TIMESTAMP TIME / DOUBLE FIELD ...) WITH (TTL=<毫秒>)}、
 * {@code CREATE DATABASE (IF NOT EXISTS)?}、{@code SHOW TABLES (DETAILS)? ((FROM|IN) db)?}、
 * 表模型 URL 必须带 {@code sql_dialect=table}、同刻重复写是**更新原值**。链接见
 * {@code docs/IOT-PLATFORM-DESIGN.md} §5.2.1 与 {@link IotDbIntegrationTestSupport}。</p>
 *
 * <p>⚠️ <b>本 IT 明确不覆盖（未能核实，如实声明）</b>：① <b>TTL 的过期行为</b>——官方 TTL 文档只说
 * 「超出生命周期的数据不可查询、不可写入」，<b>没有</b>给出写越界时刻时的异常类型/错误码，
 * 因此本 IT 只断言「表的 TTL 属性真的等于 90 天」（{@code SHOW TABLES} 的 {@code TTL(ms)} 列，
 * 官方有明确输出示例）；「越界写被拒/被丢弃」的强断言留待拿到确定语义后再补；
 * ② standalone 容器「启动到 JDBC 可用」的官方耗时仍无一手来源，故就绪判断是「6667 可连 + 建表有界重试」
 * （本机实测容器起来后建表一次成功，20×2s 的窗口没被用满）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@EnabledIfIotDbAvailable
class IotDbTimeSeriesIT {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesIT.class);

    /** 测试租户（高位值，避开真实数据）。 */
    private static final long TENANT_ID = 900001L;

    /** 表名（与 §5.2.1 的单表模型一致；也必须是装配白名单允许的标识符）。 */
    private static final String TABLE = "reading";

    /** 原始时序保留期（D0.8 / §5.2.1：90 天），建表时写入表级 TTL。 */
    private static final Duration TTL = Duration.ofDays(90);

    /** 数值点位。 */
    private static final String PROPERTY_NUMERIC = "temp";

    /** 文本点位。 */
    private static final String PROPERTY_TEXT = "mode";

    /**
     * 坐标统一之前的**历史存储形态**：属性主键字符串。
     *
     * <p>用于证明过渡期读侧「两种都认」在真库上确实生效（单测只能断言拼出来的 SQL 文本）。</p>
     */
    private static final String LEGACY_PROPERTY = "9130099";

    /** 质量码。 */
    private static final String QUALITY = "GOOD";

    /** 查询条数上限（本测试点位数远小于此，只为验证 LIMIT 不截断）。 */
    private static final int LIMIT = 10;

    /**
     * 建库建表的有界重试次数。
     *
     * <p>⚠️ 重试**不是**「连不上」的解药：第一版本机实测连不上 40s 从不自愈，真根因是镜像默认
     * {@code dn_rpc_address=127.0.0.1}（容器只监听 loopback，端口发布不可达）——已由启动参数修正
     * （见 {@code IotDbIntegrationTestSupport}）。这里保留小窗口只是为了覆盖「端口已监听但服务尚未就绪」
     * 这一未核实的中间态，不靠加长重试掩盖故障。</p>
     */
    private static final int SCHEMA_ATTEMPTS = 20;

    /** 每次重试的间隔。 */
    private static final Duration SCHEMA_RETRY_DELAY = Duration.ofSeconds(2);

    /** 本用例独占的库名（时间戳后缀 ⇒ 同一外部实例重复跑不会撞名）。 */
    private static String database;

    private static TimeSeriesProperties properties;

    private static TimeSeriesWriter writer;

    private static TimeSeriesStore store;

    /** 写入失败计数用（写入器失败只计数不抛，故必须显式断言计数为 0，否则「静默失败」会伪装成查询为空）。 */
    private static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void setUpSchemaAndClient() throws InterruptedException {
        // 驱动 jar 不含 META-INF/services/java.sql.Driver：与生产装配走同一入口显式加载，
        // 否则下面的 DriverManager.getConnection 恒报 No suitable driver found（本 IT 第一版即栽在这里）
        IotDbDriverRegistrar.ensureRegistered();
        database = "ypbin_it_" + System.currentTimeMillis();
        createSchemaWithRetry();

        properties = new TimeSeriesProperties();
        properties.setEnabled(true);
        properties.setUrl(IotDbIntegrationTestSupport.databaseUrl(database));
        properties.setUsername(IotDbIntegrationTestSupport.username());
        properties.setPassword(IotDbIntegrationTestSupport.password());
        properties.setTableName(TABLE);
        meterRegistry = new SimpleMeterRegistry();
        writer = new IotDbTimeSeriesWriter(properties, meterRegistry);
        store = new IotDbTimeSeriesStore(properties);
        log.info("[iot-it] 用例库已就绪：{}（表 {}，TTL {} 天）", database, TABLE, TTL.toDays());
    }

    @Test
    @DisplayName("★ 真库往返：一数值一文本写入 → 查询读回（时间戳/质量码/列语义都对）")
    void mustRoundTripWriteAndQuery() throws SQLException {
        long deviceId = 900001L;
        long ts = System.currentTimeMillis();
        writer.writeAll(List.of(
            new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_NUMERIC, "23.5", QUALITY, ts),
            new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_TEXT, "COOL", QUALITY, ts)));
        assertThat(meterRegistry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count())
            .as("写入器失败是「只计数不抛」，故必须显式核对计数为 0，否则失败会伪装成「查询为空」")
            .isZero();

        List<TimeSeriesPointResp> numeric = store.query(TENANT_ID, deviceId, PROPERTY_NUMERIC, ts, ts, LIMIT);
        assertThat(numeric).as("数值点必须能读回").hasSize(1);
        assertThat(numeric.get(0).ts()).as("时间戳按 epoch 毫秒还原").isEqualTo(ts);
        assertThat(numeric.get(0).value()).isEqualTo("23.5");
        assertThat(numeric.get(0).quality()).isEqualTo(QUALITY);

        List<TimeSeriesPointResp> text = store.query(TENANT_ID, deviceId, PROPERTY_TEXT, ts, ts, LIMIT);
        assertThat(text).as("文本点必须能读回").hasSize(1);
        assertThat(text.get(0).value()).isEqualTo("COOL");
        assertThat(text.get(0).ts()).isEqualTo(ts);

        // 列语义（「永不双写」）：直接读原始列，绕过存储实现的取值逻辑
        assertThat(numericColumn(deviceId, PROPERTY_NUMERIC, ts)).as("数值行 value_double 必须非空").isNotNull();
        assertThat(textColumn(deviceId, PROPERTY_NUMERIC, ts)).as("数值行的 value_text 必须是 NULL").isNull();
        assertThat(textColumn(deviceId, PROPERTY_TEXT, ts)).isEqualTo("COOL");
        assertThat(numericColumn(deviceId, PROPERTY_TEXT, ts)).as("文本行的 value_double 必须是 NULL").isNull();
    }

    @Test
    @DisplayName("★ 同刻重写生效：后写覆盖先写 ⇒ 只有一行且值为最后一次（因此写入器不做批内去重是对的）")
    void mustOverwriteValueAtSameTimestamp() {
        long deviceId = 900002L;
        long ts = System.currentTimeMillis();
        writer.writeAll(List.of(new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_NUMERIC, "1", QUALITY, ts)));
        writer.writeAll(List.of(new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_NUMERIC, "42", QUALITY, ts)));

        List<TimeSeriesPointResp> points = store.query(TENANT_ID, deviceId, PROPERTY_NUMERIC, ts, ts, LIMIT);
        assertThat(points).as("同刻重写是更新而不是追加：必须只有一行").hasSize(1);
        assertThat(points.get(0).value()).isEqualTo("42");
    }

    @Test
    @DisplayName("★ 过渡期坐标兼容（真库·**存储层**）：历史**主键字符串**行与标识行能被一次多形态查询同时读回")
    void mustQueryLegacyCoordinateFormTogetherWithIdentifier() {
        long deviceId = 900009L;
        long ts = System.currentTimeMillis();
        // 坐标统一前写下的历史行：TAG 列是属性主键字符串（不是标识）
        writer.writeAll(List.of(
            new TimeSeriesPoint(TENANT_ID, deviceId, LEGACY_PROPERTY, "1", QUALITY, ts),
            new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_NUMERIC, "2", QUALITY, ts + 1)));
        assertThat(meterRegistry.get(IotDbTimeSeriesWriter.METRIC_FAILED).counter().count()).isZero();

        List<TimeSeriesPointResp> identifierOnly =
            store.query(TENANT_ID, deviceId, PROPERTY_NUMERIC, null, null, LIMIT);
        assertThat(identifierOnly).as("只按属性标识查 ⇒ 看不到历史形态那一行（这正是统一前的读侧缺口）")
            .hasSize(1);
        assertThat(identifierOnly.getFirst().value()).isEqualTo("2");

        List<TimeSeriesPointResp> both = store.query(TENANT_ID, deviceId,
            List.of(PROPERTY_NUMERIC, LEGACY_PROPERTY), null, null, LIMIT);
        // ⚠️ 覆盖边界（如实说明）：本条走的是**存储层**的多形态查询（证明真 IoTDB 接受
        // `(property_id = 'a' OR property_id = 'b')` 且两种形态的历史行都能读回）；
        // 「请求里的标识 → 该点位的全部历史形态」这一步解析在 TimeSeriesQueryService，
        // 由单测 TimeSeriesQueryServiceTest#mustQueryLegacyCoordinateFormToo 覆盖（其变异验证见 PR 回执）。
        assertThat(both).as("过渡期读侧两种都认 ⇒ 历史行与当前行都要看见").hasSize(2);
        assertThat(both).extracting(TimeSeriesPointResp::value).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("★ 时间范围缺省（from/to 为 null）走哨兵路径：真驱动接受 0 与 Long.MAX_VALUE")
    void mustQueryWithSentinelRange() {
        long deviceId = 900003L;
        long ts = System.currentTimeMillis();
        writer.writeAll(List.of(new TimeSeriesPoint(TENANT_ID, deviceId, PROPERTY_NUMERIC, "7", QUALITY, ts)));

        List<TimeSeriesPointResp> points = store.query(TENANT_ID, deviceId, PROPERTY_NUMERIC, null, null, LIMIT);
        assertThat(points).as("哨兵区间必须能读回刚写入的点").hasSize(1);
        assertThat(points.get(0).value()).isEqualTo("7");
    }

    @Test
    @DisplayName("★ 表级 TTL = 90 天（建表即生效，SHOW TABLES 的 TTL(ms) 列可核对）")
    void mustCreateTableWithNinetyDayTtl() throws SQLException {
        assertThat(tableTtlMillis()).isEqualTo(TTL.toMillis());
    }

    @Test
    @DisplayName("★ 启用时装配 IoTDB 实现且驱动自检通过（单测环境无驱动，这条只能由 IT 承担）")
    void mustAssembleIotDbBeansWhenEnabled() {
        IotTimeSeriesConfiguration configuration = new IotTimeSeriesConfiguration(properties);

        assertThatCode(configuration::afterPropertiesSet)
            .as("启用时序库时启动自检必须通过：url/批量/表名合法 + 驱动在运行时类路径")
            .doesNotThrowAnyException();
        assertThat(configuration.timeSeriesWriter(new SimpleMeterRegistry()))
            .isInstanceOf(IotDbTimeSeriesWriter.class);
        assertThat(configuration.timeSeriesStore()).isInstanceOf(IotDbTimeSeriesStore.class);
        assertThat(store.available()).as("IoTDB 实现恒为可用").isTrue();
    }

    // ---------------------------------------------------------------- 建表与原始列读取

    private static void createSchemaWithRetry() throws InterruptedException {
        SQLException last = null;
        for (int attempt = 1; attempt <= SCHEMA_ATTEMPTS; attempt++) {
            try {
                createSchema();
                return;
            } catch (SQLException ex) {
                last = ex;
                // 中间尝试只打一行 WARN + 把完整堆栈放 DEBUG（重试不是「吞异常」：最后一次会 error 带堆栈并抛出）
                log.warn("[iot-it] IoTDB 尚未可服务（第 {}/{} 次尝试，{} 后重试）：{}",
                    attempt, SCHEMA_ATTEMPTS, SCHEMA_RETRY_DELAY, ex.getMessage());
                log.debug("[iot-it] 建库建表失败详情", ex);
                Thread.sleep(SCHEMA_RETRY_DELAY.toMillis());
            }
        }
        log.error("[iot-it] IoTDB 建库建表在 {} 次尝试后仍失败（TTL={}ms）", SCHEMA_ATTEMPTS, TTL.toMillis(), last);
        throw new IllegalStateException("IoTDB 在 " + SCHEMA_ATTEMPTS + " 次尝试后仍无法完成建库建表（TTL="
            + TTL.toMillis() + "ms）", last);
    }

    /**
     * 建库 + 建表（DDL 取自 §5.2.1；{@code IF NOT EXISTS} 让重试幂等）。
     *
     * @throws SQLException 执行失败
     */
    private static void createSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(IotDbIntegrationTestSupport.serverUrl(),
                credentials());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + database);
            statement.execute("CREATE TABLE IF NOT EXISTS " + database + "." + TABLE + " ("
                + "tenant_id STRING TAG, device_id STRING TAG, property_id STRING TAG, "
                + "time TIMESTAMP TIME, value_double DOUBLE FIELD, value_text STRING FIELD, quality STRING FIELD"
                + ") WITH (TTL=" + TTL.toMillis() + ")");
        }
    }

    /**
     * 读 {@code SHOW TABLES} 里本表的 TTL（毫秒）。
     *
     * @return TTL（毫秒）
     * @throws SQLException 查询失败
     */
    private static long tableTtlMillis() throws SQLException {
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW TABLES FROM " + database)) {
            int ttlColumn = columnIndex(resultSet, "TTL(ms)");
            while (resultSet.next()) {
                if (TABLE.equalsIgnoreCase(resultSet.getString(1))) {
                    // 真驱动把 TTL(ms) 列按 TEXT 返回：getLong 会抛 UnsupportedOperationException(BinaryColumn)
                    String raw = resultSet.getString(ttlColumn);
                    try {
                        return Long.parseLong(raw == null ? "" : raw.trim());
                    } catch (NumberFormatException ex) {
                        throw new IllegalStateException("SHOW TABLES 的 TTL(ms) 不是毫秒整数（实际：" + raw + "）", ex);
                    }
                }
            }
            throw new IllegalStateException("SHOW TABLES 未列出表 " + TABLE + "，无法核对 TTL");
        }
    }

    /**
     * 按列名取下标（不同版本可能增删列，按名字定位而不是硬编下标）。
     *
     * @param resultSet 结果集
     * @param label     列名
     * @return 1 起的下标
     * @throws SQLException 读元数据失败
     */
    private static int columnIndex(ResultSet resultSet, String label) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> labels = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String actual = metaData.getColumnLabel(index);
            if (label.equalsIgnoreCase(actual)) {
                return index;
            }
            labels.add(actual);
        }
        throw new IllegalStateException("SHOW TABLES 结果集里没有列 " + label + "（实际列：" + labels + "）");
    }

    /** 读 value_double 原始列（无该 field 序列的点时返回 null）。 */
    private static Double numericColumn(long deviceId, String propertyId, long ts) throws SQLException {
        String sql = "SELECT value_double FROM " + TABLE + " WHERE tenant_id = '" + TENANT_ID
            + "' AND device_id = '" + deviceId + "' AND property_id = " + literal(propertyId)
            + " AND time >= " + ts + " AND time <= " + ts;
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            double value = resultSet.getDouble(1);
            return resultSet.wasNull() ? null : value;
        }
    }

    /** 读 value_text 原始列（无该 field 序列的点时返回 null）。 */
    private static String textColumn(long deviceId, String propertyId, long ts) throws SQLException {
        String sql = "SELECT value_text FROM " + TABLE + " WHERE tenant_id = '" + TENANT_ID
            + "' AND device_id = '" + deviceId + "' AND property_id = " + literal(propertyId)
            + " AND time >= " + ts + " AND time <= " + ts;
        try (Connection connection = openDatabaseConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    /**
     * 测试内自用的字面量构造：本类的 propertyId 全是**本类常量**，直接加引号即可。
     *
     * <p>生产路径的同类构造带白名单校验与转义（{@code IotDbTimeSeriesStore#propertyIdLiteral}，
     * 因为那里的 propertyId 来自请求）；这里刻意不复制那套逻辑，避免测试给出「已被校验」的错觉。
     * 为什么不用 {@code ?}：真驱动对 {@code ?} 做无引号文本替换（{@code 701 STRING = INT32} /
     * {@code 616 Column 'temp' cannot be resolved}），查询侧只能用字面量（详见存储类注释）。</p>
     */
    private static String literal(String propertyId) {
        return "'" + propertyId + "'";
    }

    private static Connection openDatabaseConnection() throws SQLException {
        return DriverManager.getConnection(IotDbIntegrationTestSupport.databaseUrl(database), credentials());
    }

    private static Properties credentials() {
        Properties credentials = new Properties();
        credentials.setProperty("user", IotDbIntegrationTestSupport.username());
        credentials.setProperty("password", IotDbIntegrationTestSupport.password());
        return credentials;
    }
}
