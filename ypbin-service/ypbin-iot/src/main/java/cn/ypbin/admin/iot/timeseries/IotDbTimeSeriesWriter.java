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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
 * <p><b>成功侧观测的口径（2026-09-26 L2 复核两轮修正）</b>：本类的 {@code rows} 系列指标全部来自
 * {@code executeBatch()} 的**驱动回执**，因此它们只回答「驱动认了多少条语句」，**不回答「库里有多少行」**。
 * 两轮修正各纠正了一个过度声称：
 * ① {@link Statement#SUCCESS_NO_INFO} 的语义是「语句成功、但**受影响行数未知**」⇒ {@code rows} 不能叫
 *   「实际落库行数」；
 * ② 更进一步——**钉死的运行时驱动 {@code iotdb-jdbc:2.0.1-beta} 返回的根本不是 JDBC 行数**：其
 *   {@code executeBatchSQL()} 把**RPC 状态码**（成功 = 200）逐条塞进返回的 {@code int[]}
 *   （一手核实：{@code javap -p -c org.apache.iotdb.jdbc.IoTDBStatement#executeBatchSQL}，2026-09-26）⇒
 *   {@code rows} 在生产上恒等于「本批受理条数」、缺口恒为 0、{@code rows.noinfo} 恒为 0，而且
 *   **连非 200 的错误状态码也会被当成 1 行计入**（这一事实由 {@link #METRIC_ROWS_NONSTANDARD} 显式暴露）。
 * 能测到库内真值的只有低频对账探针 {@link IotDbRowCountProbe}（{@code iot.timeseries.db.rows}）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class IotDbTimeSeriesWriter implements TimeSeriesWriter {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesWriter.class);

    /** 写入失败计数（按批次计）。 */
    public static final String METRIC_FAILED = "iot.timeseries.write.failed";

    /** 交给写入器的**待写行数**（按行计）：与 {@link #METRIC_ROWS} 配对，用于区分「没收集」与「收集了没写」。 */
    public static final String METRIC_ATTEMPTED = "iot.timeseries.write.attempted";

    /**
     * 回执中**非失败**的条数（累计，**上界钳制到本批条数**）。
     *
     * <p>⚠️ <b>它不是「实际落库行数」，甚至不是「驱动确认的行数」</b>（这是两轮复核修正的过度声称）：
     * 本值只是回执里「不等于 {@link Statement#EXECUTE_FAILED}」的条数。钉死的驱动
     * {@code iotdb-jdbc:2.0.1-beta} 回的是 **RPC 状态码**（成功 200、失败 6xx 等，见类注释的一手依据），
     * 因此本值在生产上恒等于 {@code attempted}、缺口恒为 0，**无法**发现「0 行落库但 0 失败」。</p>
     *
     * <p>口径边界：想要「驱动确认了行数」的部分应看
     * {@code rows - rows.noinfo - rows.unknown - rows.nonstandard}，但**本驱动下它恒为 0**
     * （{@code nonstandard == rows}）。库内真值只认 {@link IotDbRowCountProbe#METRIC_DB_ROWS}。</p>
     */
    public static final String METRIC_ROWS = "iot.timeseries.write.rows";

    /**
     * 回执为 {@link Statement#SUCCESS_NO_INFO}（{@code -2}）的条数（累计，上界钳制到本批条数）。
     *
     * <p>为什么需要它：{@code rows} 把「受影响行数未知」也算作成功，于是一批全 {@code SUCCESS_NO_INFO} 时
     * 成功侧观测**看起来完好**；本指标让这种「其实什么都没确认」的状态可被区分出来。</p>
     *
     * <p><b>对本仓钉死的驱动恒为 0</b>（{@code iotdb-jdbc:2.0.1-beta} 不返回 JDBC 语义的值，见类注释）——
     * 它存在的意义是：换驱动/换 JDBC 实现（或上游修复后）时，这个口径**自动仍然正确**。</p>
     */
    public static final String METRIC_ROWS_NO_INFO = "iot.timeseries.write.rows.noinfo";

    /**
     * 驱动**违约**（{@code executeBatch()} 返回 {@code null}）时按整批计入的行数。
     *
     * <p>这些行既没有「成功」回执也没有「失败」回执，落库情况**完全未知**；保留既有的「不抛、按整批计」
     * 语义（不让上报事务回滚），但把它们单独计量，避免与「驱动确认过的行」混为一谈。</p>
     */
    public static final String METRIC_ROWS_UNKNOWN = "iot.timeseries.write.rows.unknown";

    /**
     * **非 JDBC 标准**回执的条数（既不是 {@code -3}/{@code -2}，也不是经典行数 {@code 0}/{@code 1}）。
     *
     * <p>为什么必须单独计量（2026-09-26 复核发现）：钉死的驱动回的是 **RPC 状态码**
     * （成功 = 200，失败 = 6xx），于是 {@code rows} 把「状态码 200」当成「1 行」、把「状态码 6xx」
     * 也当成「1 行」——既高估，又**把行级错误算成成功**。本指标把这件事摆到台面上：
     * 当 {@code rows.nonstandard == rows}（本驱动下的常态）时，说明 {@code rows} 只是「受理条数」，
     * 任何「成功行数」的解读都不成立。</p>
     */
    public static final String METRIC_ROWS_NONSTANDARD = "iot.timeseries.write.rows.nonstandard";

    /** 数值行列清单：不含 {@code value_text}（未用列必须不出现，见类注释的实测依据）。 */
    private static final String COLUMNS_NUMERIC =
        "(tenant_id, device_id, property_id, time, value_double, quality)";

    /** 文本行列清单：不含 {@code value_double}。 */
    private static final String COLUMNS_TEXT = "(tenant_id, device_id, property_id, time, value_text, quality)";

    /** 六个占位符（两条语句列数相同：TAG×3 + time + 值列 + quality）。 */
    private static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?)";

    /** 批量下界（配置非法时的防御性归一：0/负数会让「按批切分」退化甚至死循环）。 */
    private static final int MIN_BATCH_SIZE = 1;

    /** JDBC 的经典「一语句影响 1 行」回执（INSERT 单行语句的正常返回）。 */
    private static final int ROW_COUNT_ONE = 1;

    /** JDBC 的经典「一语句影响 0 行」回执（如零值参数、驱动省略计数时也可能回 0）。 */
    private static final int ROW_COUNT_NONE = 0;

    /**
     * 同一告警点的最小告警间隔（毫秒）：长期违约时把 WARN 压到约 30 条/分。
     *
     * <p><b>为什么自建这个最小实现</b>：本仓与 {@code ypbin-starter} 都**没有**日志限流设施
     * （全仓源码 grep 无 {@code RateLimiter}/{@code throttle} 工具类，pom 里也没有 Guava / resilience4j），
     * 按「若仓库无既有手段就给最小实现」的要求补一个 CAS 计数的小工具，**不引新依赖**。</p>
     *
     * <p><b>限流不是无代价的</b>（如实声明，别当成「一条都不丢」）：被抑制的条数只会在**下一次放行的同类告警**
     * 里报出；若违约在窗口内结束、此后不再有同类告警，最后一个窗口被抑制的条数不会出现在日志里。
     * 窗口 2s、量级个位数，故不额外引入计数指标。</p>
     */
    static final long WARN_MIN_INTERVAL_MS = 2_000L;

    private final TimeSeriesProperties properties;
    private final Counter failedCounter;
    private final Counter attemptedCounter;
    private final Counter rowsCounter;
    private final Counter rowsNoInfoCounter;
    private final Counter rowsUnknownCounter;
    private final Counter rowsNonStandardCounter;

    /** 「回执与提交数不符」告警的限流器（独立实例：两类告警不互相挤掉）。 */
    private final LogThrottle mismatchWarnThrottle = new LogThrottle(WARN_MIN_INTERVAL_MS);

    /** 「回执为 null（驱动违约）」告警的限流器。 */
    private final LogThrottle nullReceiptWarnThrottle = new LogThrottle(WARN_MIN_INTERVAL_MS);

    /** 首次「驱动受理」只打一条 INFO：成功路径在正常运行时保持安静，出问题时靠 WARN 与指标暴露。 */
    private final AtomicBoolean firstSuccessLogged = new AtomicBoolean(false);

    public IotDbTimeSeriesWriter(TimeSeriesProperties properties, MeterRegistry meterRegistry) {
        // 本类可被直接构造（不经 IotTimeSeriesConfiguration）⇒ 构造时确保驱动已注册（幂等、无副作用）
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("时序写入失败的批次数").register(meterRegistry);
        this.attemptedCounter = Counter.builder(METRIC_ATTEMPTED)
            .description("交给时序写入器的待写行数（与 write.rows 配对，用于区分没收集与收集了没写）")
            .register(meterRegistry);
        this.rowsCounter = Counter.builder(METRIC_ROWS)
            .description("executeBatch 回执中非失败的条数（上界钳制到本批条数）；"
                + "本驱动回的是 RPC 状态码（200），故本值只是「受理条数」，不等于实际落库行数，"
                + "真值见 iot.timeseries.db.rows")
            .register(meterRegistry);
        this.rowsNoInfoCounter = Counter.builder(METRIC_ROWS_NO_INFO)
            .description("驱动回执为 SUCCESS_NO_INFO（受影响行数未知）的条数（上界钳制到本批条数）；"
                + "本仓钉死的驱动恒为 0")
            .register(meterRegistry);
        this.rowsUnknownCounter = Counter.builder(METRIC_ROWS_UNKNOWN)
            .description("驱动未按 JDBC 契约返回回执（null）时按整批计入的行数——落库情况未知")
            .register(meterRegistry);
        this.rowsNonStandardCounter = Counter.builder(METRIC_ROWS_NONSTANDARD)
            .description("非 JDBC 标准回执的条数（既非 -3/-2 也非经典行数 0/1）；本驱动为 RPC 状态码，"
                + "故它恒等于 rows —— 说明 rows 不能当行数读")
            .register(meterRegistry);
    }

    @Override
    public void writeAll(List<TimeSeriesPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        // 先记「待写」再写：与 write.rows 配对，让「收集到了却没写进去」必然表现为两个指标的缺口
        attemptedCounter.increment(points.size());
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
            int[] results = statement.executeBatch();
            // ⚠️ 不抛异常 ≠ 真的写进去了：逐条读回执，把「驱动受理了多少条语句」记成指标。
            //    回执 ≠ 行数（本驱动回的是 RPC 状态码）⇒ 真值只认 IotDbRowCountProbe。
            if (results == null) {
                // JDBC 契约要求 executeBatch 返回 int[]（非 null）；驱动违约时按「整批按成功计」保持
                // 既有语义（不抛、不改上报结果），但**必须告警**——绝不静默把未知当成已知。
                rowsCounter.increment(chunk.size());
                rowsUnknownCounter.increment(chunk.size());
                warnThrottled(nullReceiptWarnThrottle,
                    "[iot] 时序驱动未按 JDBC 契约返回 executeBatch 回执（按整批成功计数，"
                        + "实际落库行数未知）：表={} 提交={} 被限流抑制={}",
                    LogSanitizer.sanitize(properties.getTableName()), chunk.size());
                return;
            }
            int succeeded = 0;
            int failed = 0;
            int noInfo = 0;
            int nonStandard = 0;
            for (int result : results) {
                if (result == Statement.EXECUTE_FAILED) {
                    failed++;
                    continue;
                }
                succeeded++;
                if (result == Statement.SUCCESS_NO_INFO) {
                    // 官方语义：语句成功、但受影响行数**未知** ⇒ 单独计量，不能当作「确认写入」
                    noInfo++;
                } else if (result != ROW_COUNT_ONE && result != ROW_COUNT_NONE) {
                    // 既不是「1 行/0 行」这类经典行数，也不是 JDBC 的哨兵值 ⇒ 非标准回执。
                    // 本仓钉死的驱动就落在这一类（返回 RPC 状态码，成功 200、失败 6xx）——
                    // 注意：非 200 的错误码在下面也走了 succeeded++，这正是本指标要暴露的事实。
                    nonStandard++;
                }
            }
            // 上界钳制：回执条数可能多于本批条数（驱动违约形态），但一批不可能影响超过本批条数的行
            int accounted = Math.min(succeeded, chunk.size());
            rowsCounter.increment(accounted);
            rowsNoInfoCounter.increment(Math.min(noInfo, accounted));
            rowsNonStandardCounter.increment(Math.min(nonStandard, accounted));
            if (failed > 0 || succeeded != chunk.size()) {
                // 暴露而不是静默：回执长度/成功数与提交数不符，说明驱动吞掉了部分行
                warnThrottled(mismatchWarnThrottle,
                    "[iot] 时序写入回执与提交数不符（已计数，不影响上报落库）：表={} 提交={} 成功={} "
                        + "失败={} 回执长度={} 被限流抑制={}",
                    LogSanitizer.sanitize(properties.getTableName()), chunk.size(), succeeded, failed,
                    results.length);
            } else if (firstSuccessLogged.compareAndSet(false, true)) {
                // 措辞刻意避开「落库成功」：本驱动只回「受理」，库内到底有没有见 METRIC_DB_ROWS
                log.info("[iot] 时序写入首次收到驱动受理回执：表={} 本批受理条数={}（不代表已落库；"
                        + "库内真值见指标 {}）",
                    LogSanitizer.sanitize(properties.getTableName()), succeeded,
                    IotDbRowCountProbe.METRIC_DB_ROWS);
            }
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
     * 限流告警：被限流时**不丢弃信息**，而是累计条数并在下一次放行时一并报出。
     *
     * @param throttle 限流器
     * @param format   日志模板（末位参数固定为「被限流抑制的条数」）
     * @param args     模板参数（不含被抑制条数）
     */
    private static void warnThrottled(LogThrottle throttle, String format, Object... args) {
        if (!throttle.tryAcquire()) {
            return;
        }
        Object[] full = new Object[args.length + 1];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = throttle.drainSuppressed();
        log.warn(format, full);
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

    /**
     * 最小日志限流器：同一告警点每 {@link #WARN_MIN_INTERVAL_MS} 毫秒最多放行一条，并累计被抑制条数。
     *
     * <p>限流**不等于**静默：被抑制的条数由 {@link #drainSuppressed()} 在下一条放行的告警里一并报出。</p>
     */
    static final class LogThrottle {

        private final long minIntervalNanos;
        private final LongSupplier nanoClock;
        private final AtomicLong nextAllowedNanos = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();

        LogThrottle(long minIntervalMillis) {
            this(minIntervalMillis, System::nanoTime);
        }

        /**
         * 测试用构造：注入时钟以做确定性断言（生产用 {@link System#nanoTime()}）。
         *
         * @param minIntervalMillis 最小告警间隔（毫秒）
         * @param nanoClock         纳秒时钟
         */
        LogThrottle(long minIntervalMillis, LongSupplier nanoClock) {
            this.minIntervalNanos = Duration.ofMillis(minIntervalMillis).toNanos();
            this.nanoClock = nanoClock;
        }

        /**
         * 是否放行本条告警。
         *
         * @return true = 放行；false = 被限流（已计入被抑制条数）
         */
        boolean tryAcquire() {
            long now = nanoClock.getAsLong();
            long next = nextAllowedNanos.get();
            if (now < next) {
                suppressed.incrementAndGet();
                return false;
            }
            if (nextAllowedNanos.compareAndSet(next, now + minIntervalNanos)) {
                return true;
            }
            // CAS 竞态：本轮让给别的线程，本轮不重复告警
            suppressed.incrementAndGet();
            return false;
        }

        /**
         * 取走并清零「自上次放行以来被抑制的条数」。
         *
         * @return 被抑制条数
         */
        long drainSuppressed() {
            return suppressed.getAndSet(0L);
        }
    }
}
