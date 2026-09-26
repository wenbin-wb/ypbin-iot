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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 时序库「真值对账」低频探针（2026-09-26 新增，本轮最重要的观测）。
 *
 * <p><b>为什么必须有它</b>：写入侧的全部指标（{@code iot.timeseries.write.attempted/rows/…}）都来自
 * JDBC 的 {@code executeBatch()} **回执**，而回执 {@code SUCCESS_NO_INFO} 的官方语义是
 * 「语句成功、但受影响行数**未知**」⇒ 驱动一边 ack、一边一行都没落库时，写入侧指标完全看不出来
 * （生产实证：{@code write.failed=0} 而 IoTDB 当天 0 行）。唯一能测到「库内到底有多少行」的手段就是
 * **直接查库计数**：本探针低频执行一次 {@code SELECT COUNT(*) FROM <table>}，把结果写进
 * {@value #METRIC_DB_ROWS}，运维用它**证伪**「ack 了但没落库」。</p>
 *
 * <p><b>为什么低频</b>：这是一次**全表**聚合，属额外的库负载。默认
 * {@value #DEFAULT_PROBE_INTERVAL_MS} ms（10 分钟）一次、首测延迟
 * {@value #DEFAULT_PROBE_INITIAL_DELAY_MS} ms，由
 * {@code ypbin.timeseries.db-rows-probe-interval-ms} /
 * {@code ypbin.timeseries.db-rows-probe-initial-delay-ms} 覆盖，**不要调成高频**
 * （写入路径本身没有任何额外查询：本类不在写入循环里做任何 DB/RPC，只在调度线程上按节拍查一次）。</p>
 *
 * <p><b>哨兵值</b>：{@value #ROW_COUNT_UNKNOWN} 表示「尚未成功测得」。为什么不用 0 兜底：0 与
 * 「跑过了、库里确实是 0 行」读数完全相同，会把「没测」误读成「空库」。</p>
 *
 * <p><b>失败语义</b>：查询失败（连不上/超时/结果集为空）只**计数 + ERROR 全堆栈**，保留上一次取值、
 * **绝不外抛**（调度线程抛出会被 Spring 记为任务异常；且本探针是观测设施，不能反过来影响业务）。
 * 失败会以 {@value #METRIC_PROBE_FAILED} 暴露。</p>
 *
 * <p><b>超时依据（一手核实，2026-09-26）</b>：{@code Statement.setQueryTimeout} 在平台运行时依赖
 * {@code org.apache.iotdb:iotdb-jdbc:2.0.1-beta} 上**确有实现**（{@code javap -p -c}
 * {@code org.apache.iotdb.jdbc.IoTDBStatement#setQueryTimeout} 把秒数存入 {@code queryTimeout} 字段，
 * 并在 {@code executeQuery} 路径上
 * {@code TSExecuteStatementReq.setTimeout(秒 × 1000)} 下推到 RPC 请求）⇒ 超时是真的下推，不是空实现。
 * 连接超时另由 {@code DriverManager.setLoginTimeout}（{@code IotTimeSeriesConfiguration} 启动时设置）兜底。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
@Component
@ConditionalOnProperty(prefix = TimeSeriesProperties.PREFIX, name = "enabled", havingValue = "true")
public class IotDbRowCountProbe {

    private static final Logger log = LoggerFactory.getLogger(IotDbRowCountProbe.class);

    /** 库内真值：{@code SELECT COUNT(*)} 的最近一次成功取值。 */
    public static final String METRIC_DB_ROWS = "iot.timeseries.db.rows";

    /** 探针失败次数（按次计）：">0 且 {@value #METRIC_DB_ROWS} 停在旧值时，说明对账已失效"。 */
    public static final String METRIC_PROBE_FAILED = "iot.timeseries.db.probe.failed";

    /** 「尚未成功测得」的哨兵值（{@code 0} 是合法真值，不能拿它当「没测」）。 */
    public static final long ROW_COUNT_UNKNOWN = -1L;

    /** 默认探测间隔（毫秒）：10 分钟一次（低频；见类注释的「为什么低频」）。 */
    public static final long DEFAULT_PROBE_INTERVAL_MS = 600_000L;

    /** 默认首测延迟（毫秒）：1 分钟。 */
    public static final long DEFAULT_PROBE_INITIAL_DELAY_MS = 60_000L;

    private final TimeSeriesProperties properties;
    private final Counter probeFailedCounter;

    /** 最近一次成功测得的库内行数（gauge 的取值源）。 */
    private final AtomicLong dbRows = new AtomicLong(ROW_COUNT_UNKNOWN);

    public IotDbRowCountProbe(TimeSeriesProperties properties, MeterRegistry meterRegistry) {
        // 同写入器/查询器：本类可被直接构造，连库前必须先确保驱动已注册（幂等、无副作用）
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
        Gauge.builder(METRIC_DB_ROWS, dbRows, AtomicLong::doubleValue)
            .description("时序库内实际行数（低频 SELECT COUNT(*) 真值对账；-1 = 尚未成功测得）")
            .register(meterRegistry);
        this.probeFailedCounter = Counter.builder(METRIC_PROBE_FAILED)
            .description("时序库真值对账（SELECT COUNT(*)）失败次数").register(meterRegistry);
    }

    /**
     * 低频对账一次：查库内实际行数并写入 {@value #METRIC_DB_ROWS}。失败只计数不抛。
     *
     * <p>间隔由 {@code ypbin.timeseries.db-rows-probe-interval-ms} 控制（默认
     * {@value #DEFAULT_PROBE_INTERVAL_MS} ms）。</p>
     */
    @Scheduled(fixedDelayString = "${ypbin.timeseries.db-rows-probe-interval-ms:600000}",
        initialDelayString = "${ypbin.timeseries.db-rows-probe-initial-delay-ms:60000}")
    public void probeOnce() {
        String sql = "SELECT COUNT(*) FROM " + properties.getTableName();
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        try (Connection connection = DriverManager.getConnection(properties.getUrl(), credentials);
             Statement statement = connection.createStatement()) {
            // 远程调用必须显式超时（本仓铁律）：超时下推到 IoTDB 的 RPC 请求（见类注释的一手依据）
            statement.setQueryTimeout(properties.getDbRowsProbeQueryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) {
                    // COUNT(*) 必返回一行；没有行说明驱动/服务端异常，按失败处理——绝不把「没读到」当成 0
                    throw new SQLException("COUNT 查询未返回任何行");
                }
                long rows = resultSet.getLong(1);
                long previous = dbRows.getAndSet(rows);
                if (previous == ROW_COUNT_UNKNOWN) {
                    log.info("[iot] 时序库真值对账首次测得：{} 行（对账语句 {}）", rows,
                        LogSanitizer.sanitize(sql));
                } else if (rows != previous) {
                    // 下降通常是 TTL 过期（90 天）或人工清理，属正常；只在日志里留痕，不告警
                    log.info("[iot] 时序库真值对账：{} 行（上次 {} 行）", rows, previous);
                }
            }
        } catch (SQLException | RuntimeException ex) {
            probeFailedCounter.increment();
            log.error("[iot] 时序库真值对账失败（保留上一次取值 {}，不影响写入）：语句={}",
                dbRows.get(), LogSanitizer.sanitize(sql), ex);
        }
    }
}
