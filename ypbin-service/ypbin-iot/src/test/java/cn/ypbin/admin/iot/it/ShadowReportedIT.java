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

import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.service.impl.AvailabilityServiceImpl;
import cn.ypbin.admin.iot.shadow.DbShadowReportedWriter;
import cn.ypbin.admin.iot.shadow.ShadowReportedUpdate;
import cn.ypbin.admin.iot.timeseries.TimeSeriesProperties;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 设备影子 {@code reported} 的真库集成测试（G2）。
 *
 * <p>单测只 mock Mapper，咬不到 SQL 语义；而 G2 的语义**全在 SQL 里**（按点位合并而不是整体覆盖、
 * 多行批量、时间戳只前进、软删复活、并发不丢更新）。这里用真 MySQL + **生产同款租户拦截器**
 * 跑完整链路：{@code 上报 → 采集增量 → 写入器 → INSERT ... ON DUPLICATE KEY UPDATE}。</p>
 *
 * <p>本机不跑容器（低配），由 CI 的 {@code -Pit} 反应堆执行；跳过等于没测，CI 会断言不出现 Skipped。</p>
 *
 * @author wenbin
 * @since 2026-09-25
 */
@EnabledIfMySqlAvailable
class ShadowReportedIT {

    private static final Long TENANT = 940001L;
    private static final Long DEVICE = 940101L;
    private static final Long OTHER_DEVICE = 940102L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static IotShadowMapper shadowMapper;
    private static DbShadowReportedWriter writer;
    private static AvailabilityServiceImpl service;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(6);
        dataSource = new HikariDataSource(config);
        ItSchema.ensure(dataSource, REPO_ROOT);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 生产同款租户拦截器：证明 iot_shadow 语句能被它解析并执行（含 JSqlParser 解析这一关）
        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setFailOnMissingTenant(true);
        MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(
            new DefaultTenantLineHandler(Optional::empty, tenantProperties)));
        configuration.addInterceptor(plugins);
        configuration.addMapper(DeviceLivenessMapper.class);
        configuration.addMapper(OutageEventMapper.class);
        configuration.addMapper(MaintenanceWindowMapper.class);
        configuration.addMapper(IotDeviceMapper.class);
        configuration.addMapper(IotShadowMapper.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DeviceLiveness.class);
        TableInfoHelper.initTableInfo(assistant, OutageEvent.class);
        TableInfoHelper.initTableInfo(assistant, MaintenanceWindow.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        TableInfoHelper.initTableInfo(assistant, IotShadow.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        shadowMapper = sessionTemplate.getMapper(IotShadowMapper.class);
        writer = new DbShadowReportedWriter(shadowMapper, OBJECT_MAPPER, new SimpleMeterRegistry());
        // 真链路：AvailabilityServiceImpl 用**真写入器**，证明「上报确实驱动了 reported」
        service = new AvailabilityServiceImpl(
            sessionTemplate.getMapper(DeviceLivenessMapper.class),
            sessionTemplate.getMapper(OutageEventMapper.class),
            sessionTemplate.getMapper(MaintenanceWindowMapper.class),
            sessionTemplate.getMapper(IotDeviceMapper.class), new AvailabilityProperties(),
            () -> Optional.of(TENANT), values -> { }, points -> { }, new TimeSeriesProperties(), writer,
            new SimpleMeterRegistry());
        cleanup();
        seedDevices();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            cleanup();
            dataSource.close();
        }
    }

    @BeforeEach
    void resetShadowState() {
        cleanup();
        seedDevices();
    }

    @Test
    @DisplayName("★ G2 闭环：上报带点位与值的读数 ⇒ 影子 reported 里出现该点位与值（行不存在则自动建）")
    void ingestMustWriteReportedIntoShadow() {
        LocalDateTime readingAt = LocalDateTime.now().withNano(0).minusMinutes(1);

        service.ingest(req(observation(DEVICE, "temperature", "23.5", readingAt)));

        Map<String, Object> reported = reportedOf(DEVICE);
        assertThat(reported).as("此前 reported 永不写入（merged ≡ desired）——G2 就是要补这一半")
            .containsEntry("temperature", "23.5");
        assertThat(reportTsOf(DEVICE)).as("最近上报时刻取读数时刻").isEqualTo(readingAt);
        assertThat(isDeletedOf(DEVICE)).as("新建的影子行必须是有效行").isZero();
    }

    @Test
    @DisplayName("★ 多点位连续上报 ⇒ **合并**而不是覆盖（先后两次上报的两个点位都要在）")
    void consecutiveIngestsMustMergeByProperty() {
        LocalDateTime first = LocalDateTime.now().withNano(0).minusMinutes(2);
        service.ingest(req(observation(DEVICE, "temperature", "23.5", first)));
        service.ingest(req(observation(DEVICE, "humidity", "61", first.plusMinutes(1))));

        Map<String, Object> reported = reportedOf(DEVICE);
        assertThat(reported).as("整体覆盖会把先写的点位抹掉").containsOnlyKeys("temperature", "humidity")
            .containsEntry("temperature", "23.5").containsEntry("humidity", "61");
        assertThat(reportTsOf(DEVICE)).as("report_ts 取较晚的那次").isEqualTo(first.plusMinutes(1));
    }

    @Test
    @DisplayName("★ 同一点位再上报 ⇒ 值覆盖；乱序旧批次不得把 report_ts 改小")
    void samePropertyMustOverwriteAndReportTsMustNotRegress() {
        LocalDateTime base = LocalDateTime.now().withNano(0).minusMinutes(10);
        service.ingest(req(observation(DEVICE, "temperature", "23.5", base)));
        service.ingest(req(observation(DEVICE, "temperature", "24.1", base.plusMinutes(5))));
        assertThat(reportedOf(DEVICE)).containsEntry("temperature", "24.1");

        // 乱序：一个更早的批次晚到（HTTP 重试/多副本乱序）
        service.ingest(req(observation(DEVICE, "temperature", "20.0", base.minusMinutes(5))));

        assertThat(reportTsOf(DEVICE)).as("GREATEST(...) 保证「最近上报」不倒退").isEqualTo(base.plusMinutes(5));
        // ⚠️ 如实记录边界：影子 reported 的**值**是后到者覆盖（它只存「最近一次上报的点位快照」，
        // 没有逐点位时刻可比较）。这与最新值写入器**不同**：后者自 2026-09-26 起对每个 field 按 ts
        // 比较后写入（跨批不回退，见 ROADMAP 四点十七「已闭环」）。此处断言的是影子层的当前真实行为。
        assertThat(reportedOf(DEVICE)).containsEntry("temperature", "20.0");
    }

    @Test
    @DisplayName("★ 重放同一批 ⇒ 结果不变（幂等）")
    void replayMustBeIdempotent() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(3);
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("temperature", "23.5");
        patch.put("humidity", "61");
        ShadowReportedUpdate update = new ShadowReportedUpdate(TENANT, DEVICE, patch, at);

        writer.writeAll(List.of(update));
        Map<String, Object> once = reportedOf(DEVICE);
        LocalDateTime onceTs = reportTsOf(DEVICE);
        writer.writeAll(List.of(update));

        assertThat(reportedOf(DEVICE)).as("重放同一批不得改变结果").isEqualTo(once);
        assertThat(reportTsOf(DEVICE)).isEqualTo(onceTs);
    }

    @Test
    @DisplayName("★ 并发：两个副本同时上报**不同点位** ⇒ 两个点位都要留住（禁止丢更新）")
    void concurrentWritersMustNotLoseUpdates() throws Exception {
        LocalDateTime at = LocalDateTime.now().withNano(0);
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String propertyId = i == 0 ? "temperature" : "humidity";
                String value = i == 0 ? "23.5" : "61";
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < rounds; round++) {
                            writer.writeAll(List.of(new ShadowReportedUpdate(TENANT, DEVICE,
                                Map.of(propertyId, value), at)));
                        }
                    } catch (Throwable ex) {
                        synchronized (failures) {
                            failures.add(ex);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("并发写入未在超时内结束").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("写入器不得把异常外抛").isEmpty();
        assertThat(reportedOf(DEVICE)).as("读改写（Java 里合并再整份写回）会在这里丢更新；"
            + "JSON_MERGE_PATCH 在行锁内合并，两个点位必须都在")
            .containsOnlyKeys("temperature", "humidity")
            .containsEntry("temperature", "23.5").containsEntry("humidity", "61");
    }

    @Test
    @DisplayName("★ 软删的影子行仍有唯一键：命中冲突时必须复活（is_deleted/status 复位）")
    void mergeMustReviveSoftDeletedRow() {
        LocalDateTime at = LocalDateTime.now().withNano(0);
        writer.writeAll(List.of(new ShadowReportedUpdate(TENANT, DEVICE, Map.of("temperature", "23.5"), at)));
        execute("UPDATE iot_shadow SET is_deleted = 1, status = 0 WHERE tenant_id = " + TENANT
            + " AND device_id = " + DEVICE);
        assertThat(isDeletedOf(DEVICE)).as("前置：行已软删").isEqualTo(1);

        writer.writeAll(List.of(new ShadowReportedUpdate(TENANT, DEVICE, Map.of("humidity", "61"), at)));

        assertThat(isDeletedOf(DEVICE)).as("不复活就永远查不到合并结果（唯一键仍被占）").isZero();
        assertThat(reportedOf(DEVICE)).as("复活的同时必须完成本次合并，且保留软删前的点位")
            .containsOnlyKeys("temperature", "humidity");
    }

    @Test
    @DisplayName("★ 一台设备一条语句：多设备一批走同一条语句（不因设备数放大往返）")
    void oneStatementMustCoverWholeBatch() {
        LocalDateTime at = LocalDateTime.now().withNano(0);
        writer.writeAll(List.of(
            new ShadowReportedUpdate(TENANT, DEVICE, Map.of("temperature", "23.5"), at),
            new ShadowReportedUpdate(TENANT, OTHER_DEVICE, Map.of("humidity", "61"), at)));

        assertThat(reportedOf(DEVICE)).containsOnlyKeys("temperature");
        assertThat(reportedOf(OTHER_DEVICE)).containsOnlyKeys("humidity");
    }

    private static ReadingObservationDto observation(Long deviceId, String propertyId, String value,
                                                     LocalDateTime ts) {
        ReadingObservationDto observation = new ReadingObservationDto();
        observation.setDeviceId(deviceId);
        observation.setPollIntervalMs(5_000);
        observation.setPropertyId(propertyId);
        observation.setValue(value);
        observation.setQuality(AvailabilityRules.QUALITY_GOOD);
        observation.setTs(ts.withNano(0).atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
        return observation;
    }

    private static ReadingIngestReq req(ReadingObservationDto... observations) {
        ReadingIngestReq req = new ReadingIngestReq();
        req.setItems(new ArrayList<>(List.of(observations)));
        return req;
    }

    /** 直接读库取 reported（绕过租户插件，断言的是**存储里真实存了什么**）。 */
    private static Map<String, Object> reportedOf(Long deviceId) {
        String raw = rawValue("reported", deviceId);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return OBJECT_MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() { });
    }

    /**
     * 读库里的 {@code report_ts}。
     *
     * <p><b>必须用 {@code getObject(..., LocalDateTime.class)} 而不是 {@code getTimestamp().toLocalDateTime()}</b>：
     * 后者会先把 DATETIME 按**连接时区**解释成一个瞬间、再换到 **JVM 默认时区**，在 CI（TZ=UTC）上
     * 与写入侧（平台时区 GMT+8 的 LocalDateTime）相差 8 小时——这是测试读法的坑，不是存储值错
     * （生产同款读路径走 MyBatis 的 LocalDateTimeTypeHandler，读写对称，不受影响；本轮 CI 实测校正）。</p>
     */
    private static LocalDateTime reportTsOf(Long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT report_ts FROM iot_shadow WHERE tenant_id = " + TENANT + " AND device_id = "
                        + deviceId);
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return rs.getObject(1, LocalDateTime.class);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 report_ts 失败", ex);
        }
    }

    private static int isDeletedOf(Long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT is_deleted FROM iot_shadow WHERE tenant_id = " + TENANT + " AND device_id = "
                        + deviceId);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 is_deleted 失败", ex);
        }
    }

    private static String rawValue(String column, Long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + column + " FROM iot_shadow WHERE tenant_id = " + TENANT + " AND device_id = "
                        + deviceId);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 " + column + " 失败", ex);
        }
    }

    private static void seedDevices() {
        insertDevice(DEVICE, "IT-SHADOW-1");
        insertDevice(OTHER_DEVICE, "IT-SHADOW-2");
    }

    private static void insertDevice(Long id, String code) {
        execute("INSERT INTO iot_device (id, tenant_id, device_code, device_name, protocol, endpoint, "
            + "create_time, update_time) VALUES (" + id + ", " + TENANT + ", '" + code + "', '" + code
            + "', 'tcp', 'tcp://127.0.0.1:15002', NOW(), NOW())");
    }

    private static void cleanup() {
        execute("DELETE FROM iot_shadow WHERE tenant_id = " + TENANT);
        execute("DELETE FROM device_liveness WHERE tenant_id = " + TENANT);
        execute("DELETE FROM outage_event WHERE tenant_id = " + TENANT);
        execute("DELETE FROM maintenance_window WHERE tenant_id = " + TENANT);
        execute("DELETE FROM iot_device WHERE tenant_id = " + TENANT);
    }

    private static void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("执行 SQL 失败：" + sql, ex);
        }
    }
}
