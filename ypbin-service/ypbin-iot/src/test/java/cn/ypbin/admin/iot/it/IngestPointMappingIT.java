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
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.service.impl.AvailabilityServiceImpl;
import cn.ypbin.admin.iot.shadow.ShadowReportedUpdate;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPoint;
import cn.ypbin.admin.iot.timeseries.TimeSeriesProperties;
import cn.ypbin.admin.iot.values.LatestValue;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 入站点位**成员**校验（P0-6c 后半）的真库集成测试：读数点位必须是该设备已配置的映射点位。
 *
 * <p><b>为什么必须真库</b>：这条校验依赖三件单测证明不了的事 —— ①
 * {@code iot_point_mapping × iot_property} 的**批量查询写法**在真表上能否跑通；
 * ② 入站路径**没有租户身份**，查询必须包在 {@code executeIgnore} 里，否则生产同款租户插件
 * （{@code failOnMissingTenant=true}）会直接抛；③ 「设备没有映射 ⇒ 全丢」在真库上的行为。
 * 本类用真 MySQL + 生产同款租户拦截器跑这三点，并断言被丢弃的读数**不落**活性/影子/最新值。</p>
 *
 * <p>本机不跑容器（低配），由 CI 的 {@code -Pit} 反应堆执行；跳过等于没测，CI 会断言不出现 Skipped。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
@EnabledIfMySqlAvailable
class IngestPointMappingIT {

    private static final Long TENANT = 943001L;

    /** 配了点位映射的设备。 */
    private static final Long MAPPED_DEVICE = 943101L;

    /** 没有任何点位映射的设备（对照）。 */
    private static final Long UNMAPPED_DEVICE = 943102L;

    private static final Long PROP_TEMPERATURE = 943201L;

    private static final Long PROP_HUMIDITY = 943202L;

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final List<LatestValue> RECORDED_LATEST = new ArrayList<>();

    private static final List<TimeSeriesPoint> RECORDED_SERIES = new ArrayList<>();

    private static final List<ShadowReportedUpdate> RECORDED_SHADOW = new ArrayList<>();

    private static HikariDataSource dataSource;
    private static DeviceLivenessMapper livenessMapper;
    private static OutageEventMapper outageMapper;
    private static MaintenanceWindowMapper maintenanceWindowMapper;
    private static IotDeviceMapper deviceMapper;
    private static PointMappingIndex pointMappingIndex;
    private static AvailabilityServiceImpl service;
    private static SimpleMeterRegistry registry;

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
        configuration.addMapper(IotPointMappingMapper.class);
        configuration.addMapper(IotPropertyMapper.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DeviceLiveness.class);
        TableInfoHelper.initTableInfo(assistant, OutageEvent.class);
        TableInfoHelper.initTableInfo(assistant, MaintenanceWindow.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        TableInfoHelper.initTableInfo(assistant, IotShadow.class);
        TableInfoHelper.initTableInfo(assistant, IotPointMapping.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        livenessMapper = sessionTemplate.getMapper(DeviceLivenessMapper.class);
        outageMapper = sessionTemplate.getMapper(OutageEventMapper.class);
        maintenanceWindowMapper = sessionTemplate.getMapper(MaintenanceWindowMapper.class);
        deviceMapper = sessionTemplate.getMapper(IotDeviceMapper.class);
        pointMappingIndex = new PointMappingIndex(
            sessionTemplate.getMapper(IotPointMappingMapper.class),
            sessionTemplate.getMapper(IotPropertyMapper.class));
        freshService();
        seed();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            cleanup();
            dataSource.close();
        }
    }

    @BeforeEach
    void resetState() throws SQLException {
        RECORDED_LATEST.clear();
        RECORDED_SERIES.clear();
        RECORDED_SHADOW.clear();
        // 每个用例换一个 registry + service：SimpleMeterRegistry.clear() 会把已注册的 counter 整个摘掉，
        // 之后按名字就取不到了（本仓真 Redis IT 上实测过这个坑）
        freshService();
        cleanup();
        seed();
    }

    /** 用固定的 mapper 装配一个新 service（指标独立，便于按用例断言计数）。 */
    private static void freshService() {
        registry = new SimpleMeterRegistry();
        service = new AvailabilityServiceImpl(livenessMapper, outageMapper, maintenanceWindowMapper, deviceMapper,
            new AvailabilityProperties(), () -> Optional.of(TENANT), RECORDED_LATEST::addAll,
            RECORDED_SERIES::addAll, new TimeSeriesProperties(), RECORDED_SHADOW::addAll, pointMappingIndex,
            registry);
    }

    @Test
    @DisplayName("★ P0-6c：已映射点位（属性**标识**形态）正常入库，且计数为 0")
    void mappedIdentifierMustBeAccepted() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(1);

        int processed = service.ingest(req(point(MAPPED_DEVICE, "temperature", "23.5", at)));

        assertThat(processed).isEqualTo(1);
        assertThat(livenessOf(MAPPED_DEVICE)).as("合法读数必须刷新活性（真库真行）").isNotNull();
        assertThat(RECORDED_LATEST).singleElement()
            .satisfies(value -> assertThat(value.propertyId()).isEqualTo("temperature"));
        assertThat(RECORDED_SHADOW).singleElement()
            .satisfies(update -> assertThat(update.reported()).containsEntry("temperature", "23.5"));
        assertThat(unmappedCount()).isZero();
    }

    @Test
    @DisplayName("★ P0-6c：已映射点位（属性**主键字符串**形态，access 链路上报的形态）同样被接受")
    void mappedPrimaryKeyStringMustAlsoBeAccepted() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(1);

        int processed = service.ingest(
            req(point(MAPPED_DEVICE, String.valueOf(PROP_TEMPERATURE), "23.5", at)));

        assertThat(processed).as("access 上报的是主键字符串，只认标识会把它全部误杀").isEqualTo(1);
        assertThat(RECORDED_LATEST).singleElement()
            .satisfies(value -> assertThat(value.propertyId()).isEqualTo(String.valueOf(PROP_TEMPERATURE)));
        assertThat(unmappedCount()).isZero();
    }

    @Test
    @DisplayName("★ P0-6c：未映射点位被丢弃 + 计数 + **不落**活性/影子/最新值")
    void unmappedPropertyMustBeDropped() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(1);

        int processed = service.ingest(req(point(MAPPED_DEVICE, "pressure", "1013", at)));

        assertThat(processed).isZero();
        assertThat(livenessOf(MAPPED_DEVICE)).as("被丢弃的读数不该留下活性行").isNull();
        assertThat(RECORDED_LATEST).isEmpty();
        assertThat(RECORDED_SHADOW).isEmpty();
        assertThat(unmappedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ P0-6c：混合批（部分映射/部分未映射）⇒ 只丢未映射的，合法点位照常入库")
    void mixedBatchMustDropOnlyUnmapped() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(2);

        int processed = service.ingest(req(
            point(MAPPED_DEVICE, "temperature", "23.5", at),
            point(MAPPED_DEVICE, "pressure", "1013", at),
            point(MAPPED_DEVICE, "humidity", "61", at.plusMinutes(1))));

        assertThat(processed).as("两条合法读数计入 processed").isEqualTo(2);
        assertThat(RECORDED_LATEST).extracting(LatestValue::propertyId)
            .containsExactlyInAnyOrder("temperature", "humidity");
        assertThat(RECORDED_SHADOW).singleElement()
            .satisfies(update -> assertThat(update.reported())
                .containsOnlyKeys("temperature", "humidity"));
        assertThat(unmappedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ P0-6c：设备**没有任何映射** ⇒ 带点位的读数全丢 + 计数（真库真行为）")
    void deviceWithoutMappingMustDropPointReadings() {
        LocalDateTime at = LocalDateTime.now().withNano(0).minusMinutes(1);

        int processed = service.ingest(req(point(UNMAPPED_DEVICE, "temperature", "23.5", at)));

        assertThat(processed).isZero();
        assertThat(livenessOf(UNMAPPED_DEVICE)).isNull();
        assertThat(RECORDED_LATEST).isEmpty();
        assertThat(unmappedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ P0-6c 边界：无映射设备上「只报时刻+质量」的读数仍刷新活性（按点位判定，不按设备判死）")
    void livenessOnlyReadingMustStillRefreshActivityWithoutMappings() {
        int processed = service.ingest(req(observation(UNMAPPED_DEVICE, 5_000,
            AvailabilityRules.QUALITY_GOOD, LocalDateTime.now().withNano(0).minusMinutes(1))));

        assertThat(processed).isEqualTo(1);
        assertThat(livenessOf(UNMAPPED_DEVICE)).isNotNull();
        assertThat(unmappedCount()).isZero();
    }

    // ---------- helpers ----------

    private static long unmappedCount() {
        return (long) registry.get(AvailabilityServiceImpl.METRIC_UNMAPPED_PROPERTY_ID).counter().count();
    }

    private static DeviceLiveness livenessOf(Long deviceId) {
        return TenantContext.executeWithTenant(TENANT, () -> livenessMapper.selectOne(
            Wrappers.<DeviceLiveness>lambdaQuery().eq(DeviceLiveness::getDeviceId, deviceId)));
    }

    private static ReadingObservationDto point(Long deviceId, String propertyId, String value,
                                              LocalDateTime ts) {
        ReadingObservationDto observation = observation(deviceId, 5_000, AvailabilityRules.QUALITY_GOOD, ts);
        observation.setPropertyId(propertyId);
        observation.setValue(value);
        return observation;
    }

    private static ReadingObservationDto observation(Long deviceId, Integer pollIntervalMs, String quality,
                                                    LocalDateTime ts) {
        ReadingObservationDto observation = new ReadingObservationDto();
        observation.setDeviceId(deviceId);
        observation.setPollIntervalMs(pollIntervalMs);
        observation.setQuality(quality);
        observation.setTs(ts.withNano(0).atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
        return observation;
    }

    private static ReadingIngestReq req(ReadingObservationDto... observations) {
        ReadingIngestReq req = new ReadingIngestReq();
        req.setItems(new ArrayList<>(List.of(observations)));
        return req;
    }

    private static void seed() {
        execute("INSERT INTO iot_device (id, tenant_id, device_code, device_name, protocol, endpoint, "
            + "create_time, update_time) VALUES (" + MAPPED_DEVICE + ", " + TENANT + ", 'IT-MAPPED-1', "
            + "'IT-MAPPED-1', 'tcp', 'tcp://127.0.0.1:15002', NOW(), NOW())");
        execute("INSERT INTO iot_device (id, tenant_id, device_code, device_name, protocol, endpoint, "
            + "create_time, update_time) VALUES (" + UNMAPPED_DEVICE + ", " + TENANT + ", 'IT-MAPPED-2', "
            + "'IT-MAPPED-2', 'tcp', 'tcp://127.0.0.1:15002', NOW(), NOW())");
        insertProperty(PROP_TEMPERATURE, "temperature");
        insertProperty(PROP_HUMIDITY, "humidity");
        // 只给 MAPPED_DEVICE 配映射；UNMAPPED_DEVICE 一条都不配（对照）
        insertPointMapping(MAPPED_DEVICE, PROP_TEMPERATURE);
        insertPointMapping(MAPPED_DEVICE, PROP_HUMIDITY);
    }

    private static void insertProperty(Long id, String identifier) {
        execute("INSERT INTO iot_property (id, tenant_id, service_id, identifier, property_name, data_type, "
            + "create_time, update_time) VALUES (" + id + ", " + TENANT + ", " + (id - 100) + ", '"
            + identifier + "', '" + identifier + "', 'decimal', NOW(), NOW())");
    }

    private static void insertPointMapping(Long deviceId, Long propertyId) {
        execute("INSERT INTO iot_point_mapping (id, tenant_id, device_id, property_id, ref_type, raw_address, "
            + "address_type, create_time, update_time) VALUES (" + (deviceId + propertyId % 100) + ", "
            + TENANT + ", " + deviceId + ", " + propertyId + ", 'property', 'r-" + propertyId
            + "', 'holding', NOW(), NOW())");
    }

    private static void cleanup() {
        for (String table : List.of("iot_shadow", "device_liveness", "outage_event", "maintenance_window",
                "iot_point_mapping", "iot_property", "iot_device")) {
            execute("DELETE FROM " + table + " WHERE tenant_id = " + TENANT);
        }
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
