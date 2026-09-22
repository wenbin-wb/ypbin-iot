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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.service.impl.AvailabilityServiceImpl;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
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
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 断档/可用率真库集成测试（M-2）：验证**只靠单测验证不了的那一半**——
 * 断档候选的 SQL 时间推进条件（{@code TIMESTAMPADD} 那条）、活性行的唯一键/复活语义、
 * 以及可用率查询对真实行的聚合。
 *
 * <p>本机不跑容器（低配），由 CI 的 {@code -Pit} 反应堆执行；跳过等于没测，CI 会断言不出现 Skipped。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@EnabledIfMySqlAvailable
class OutageAvailabilityIT {

    private static final Long TENANT = 920001L;
    private static final Long DEVICE = 920101L;
    private static final Long OTHER_DEVICE = 920102L;
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static DeviceLivenessMapper livenessMapper;
    private static OutageEventMapper outageMapper;
    private static IotDeviceMapper deviceMapper;
    private static AvailabilityServiceImpl service;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        ItSchema.ensure(dataSource, REPO_ROOT);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 装上**生产同款**的租户拦截器：tenant_id 由插件注入（服务代码不写 tenant_id），
        // 这样本 IT 同时钉住「新表是租户表、插件会为其注入 tenant_id」与「无上下文时 fail-closed」
        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setFailOnMissingTenant(true);
        MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(
            new DefaultTenantLineHandler(Optional::empty, tenantProperties)));
        configuration.addInterceptor(plugins);
        configuration.addMapper(DeviceLivenessMapper.class);
        configuration.addMapper(OutageEventMapper.class);
        configuration.addMapper(IotDeviceMapper.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DeviceLiveness.class);
        TableInfoHelper.initTableInfo(assistant, OutageEvent.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        livenessMapper = sessionTemplate.getMapper(DeviceLivenessMapper.class);
        outageMapper = sessionTemplate.getMapper(OutageEventMapper.class);
        deviceMapper = sessionTemplate.getMapper(IotDeviceMapper.class);
        service = new AvailabilityServiceImpl(livenessMapper, outageMapper, deviceMapper,
            new AvailabilityProperties());
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

    /**
     * 每个用例前清掉断档与活性行：用例之间**不得**靠执行顺序传递状态（本仓有过顺序耦合被复核点名的教训）。
     * 设备行只种一次（{@code @BeforeAll}），故这里不删 iot_device。
     */
    @BeforeEach
    void resetOutageState() {
        execute("DELETE FROM outage_event WHERE tenant_id = " + TENANT);
        execute("DELETE FROM device_liveness WHERE tenant_id = " + TENANT);
    }

    @Test
    @DisplayName("★ 闭环：上报刷新活性 → 扫描按 SQL 时间条件开断档 → 有效数据闭合 → 查询算出可用率")
    void outageLifecycleMustBeComputableFromRealRows() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        // 1) 一次有效数据（10 分钟前）⇒ 活性行落库
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusMinutes(10))));
        DeviceLiveness liveness = inTenant(() -> livenessMapper.selectOne(
            Wrappers.<DeviceLiveness>lambdaQuery().eq(DeviceLiveness::getDeviceId, DEVICE)));
        assertThat(liveness).as("上报后必须有活性行").isNotNull();
        assertThat(liveness.getLastGoodAt()).isNotNull();

        // 2) 扫描：10 分钟 > 2 × 5s ⇒ 必须开断档（起点=最后一次有效数据）
        assertThat(service.scanAndOpenOutages()).as("超时设备必须被扫描发现").isEqualTo(1);
        List<OutageEvent> open = openOutages(DEVICE);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().getEndTs()).isNull();
        assertThat(open.getFirst().getDurationSec()).isNull();

        // 3) 再扫一次：已有进行中的断档，不得重复开
        assertThat(service.scanAndOpenOutages()).as("进行中的断档不得重复开").isZero();

        // 4) 有效数据到达 ⇒ 闭合断档并算出时长
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow)));
        List<OutageEvent> closed = closedOutages(DEVICE);
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().getEndTs()).isNotNull();
        assertThat(closed.getFirst().getDurationSec()).as("10 分钟断档").isEqualTo(600L);
        assertThat(inTenant(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)).getOpenOutageId()))
            .as("闭合后 open_outage_id 必须清空").isNull();

        // 5) 可用率：20 分钟窗口内 10 分钟断档 ⇒ 0.5，不达标
        AvailabilityResp resp = inTenant(() -> service.query(DEVICE, dbNow.minusMinutes(20), dbNow));
        assertThat(resp.getWindowSeconds()).isEqualTo(1_200L);
        assertThat(resp.getOutageSeconds()).isEqualTo(600L);
        assertThat(resp.getAvailability()).isEqualByComparingTo(new BigDecimal("0.500000"));
        assertThat(resp.getMeetsTarget()).isFalse();
        assertThat(resp.getOutages()).hasSize(1);
    }

    @Test
    @DisplayName("★ 新鲜设备不得被扫描误判为断档（时间条件不能写成「只要有数据就断档」）")
    void freshDeviceMustNotBeReportedAsOutage() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        service.ingest(req(observation(OTHER_DEVICE, 60_000, AvailabilityRules.QUALITY_GOOD, dbNow)));

        assertThat(service.scanAndOpenOutages()).as("刚上报的设备不构成断档").isZero();
        assertThat(openOutages(OTHER_DEVICE)).isEmpty();
    }

    @Test
    @DisplayName("★ 从未有过有效数据的设备：断档起点退化为「首次观测」，且扫描能发现它")
    void neverGoodDeviceMustUseFirstObservationAsStart() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        service.ingest(req(observation(OTHER_DEVICE, 5_000, "BAD", dbNow.minusMinutes(30))));

        assertThat(service.scanAndOpenOutages()).isEqualTo(1);
        List<OutageEvent> events = inTenant(() -> outageMapper.selectList(
            Wrappers.<OutageEvent>lambdaQuery().eq(OutageEvent::getDeviceId, OTHER_DEVICE)));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getStartTs()).as("无有效数据 ⇒ 起点=首次观测")
            .isEqualTo(dbNow.minusMinutes(30));
    }

    @Test
    @DisplayName("★ 逻辑删除的活性行必须被复活（唯一键不含量删标记，重插会撞键）")
    void softDeletedLivenessMustBeRevived() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow.minusMinutes(5))));
        execute("UPDATE device_liveness SET is_deleted = 1 WHERE tenant_id = " + TENANT
            + " AND device_id = " + DEVICE);

        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow)));

        DeviceLiveness row = inTenant(() -> livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE));
        assertThat(row.getIsDeleted()).as("复活后不得仍是删除态").isZero();
        assertThat(row.getLastGoodAt()).isEqualTo(dbNow);
        assertThat(rawRowCount(DEVICE)).as("只允许一行").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 租户隔离：无租户上下文时新表被插件拒绝（fail-closed，证明它们确实是租户表）")
    void tenantTablesMustFailClosedWithoutContext() {
        assertThatThrownBy(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)))
            .hasMessageContaining("缺少租户上下文");
        assertThatThrownBy(() -> outageMapper.selectList(Wrappers.<OutageEvent>lambdaQuery()
            .eq(OutageEvent::getDeviceId, DEVICE)))
            .hasMessageContaining("缺少租户上下文");
    }

    private static List<OutageEvent> openOutages(Long deviceId) {
        return inTenant(() -> outageMapper.selectList(Wrappers.<OutageEvent>lambdaQuery()
            .eq(OutageEvent::getDeviceId, deviceId)
            .isNull(OutageEvent::getEndTs)));
    }

    private static List<OutageEvent> closedOutages(Long deviceId) {
        return inTenant(() -> outageMapper.selectList(Wrappers.<OutageEvent>lambdaQuery()
            .eq(OutageEvent::getDeviceId, deviceId)
            .isNotNull(OutageEvent::getEndTs)));
    }

    /** 在租户上下文里执行（生产里由登录态提供；IT 里显式指定，同时验证 fail-closed 确实生效）。 */
    private static <T> T inTenant(Supplier<T> supplier) {
        return TenantContext.executeWithTenant(TENANT, supplier);
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

    private static void seedDevices() {
        insertDevice(DEVICE, "IT-OUTAGE-1");
        insertDevice(OTHER_DEVICE, "IT-OUTAGE-2");
    }

    private static void insertDevice(Long id, String code) {
        execute("INSERT INTO iot_device (id, tenant_id, device_code, device_name, protocol, endpoint, "
            + "create_time, update_time) VALUES (" + id + ", " + TENANT + ", '" + code + "', '" + code
            + "', 'tcp', 'tcp://127.0.0.1:15002', NOW(), NOW())");
    }

    private static void cleanup() {
        execute("DELETE FROM outage_event WHERE tenant_id = " + TENANT);
        execute("DELETE FROM device_liveness WHERE tenant_id = " + TENANT);
        execute("DELETE FROM iot_device WHERE tenant_id = " + TENANT);
    }

    private static long rawRowCount(Long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM device_liveness WHERE tenant_id = " + TENANT
                        + " AND device_id = " + deviceId);
                var rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (SQLException ex) {
            throw new IllegalStateException("统计活性行失败", ex);
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
