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
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
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
import java.time.format.DateTimeFormatter;
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
    /** 原生 SQL 里的 DATETIME 字面量格式（LocalDateTime.toString() 带 `T`，MySQL 不认——CI 实测过）。 */
    private static final DateTimeFormatter SQL_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static DeviceLivenessMapper livenessMapper;
    private static OutageEventMapper outageMapper;

    private static MaintenanceWindowMapper maintenanceWindowMapper;
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
        configuration.addMapper(MaintenanceWindowMapper.class);
        configuration.addMapper(IotDeviceMapper.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, DeviceLiveness.class);
        TableInfoHelper.initTableInfo(assistant, OutageEvent.class);
        TableInfoHelper.initTableInfo(assistant, MaintenanceWindow.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        livenessMapper = sessionTemplate.getMapper(DeviceLivenessMapper.class);
        outageMapper = sessionTemplate.getMapper(OutageEventMapper.class);
        deviceMapper = sessionTemplate.getMapper(IotDeviceMapper.class);
        maintenanceWindowMapper = sessionTemplate.getMapper(MaintenanceWindowMapper.class);
        service = new AvailabilityServiceImpl(livenessMapper, outageMapper, maintenanceWindowMapper,
            deviceMapper, new AvailabilityProperties());
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
        execute("DELETE FROM maintenance_window WHERE tenant_id = " + TENANT);
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
    @DisplayName("★ 生效周期口径：上报周期小于兜底时按**上报周期**判定（不能抬到兜底，否则与 spec 的 K×周期不符）")
    void pollIntervalBelowFallbackMustUseReportedInterval() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        // 周期 1s、K=2 ⇒ 阈值 2s；最后有效数据在 3s 前 ⇒ 必须判为断档
        service.ingest(req(observation(OTHER_DEVICE, 1_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusSeconds(3))));

        assertThat(service.scanAndOpenOutages())
            .as("用 GREATEST(周期, 兜底) 的口径时这里会是 0（阈值被抬到 10s）").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 多副本守卫（SQL 谓词）：同一设备只允许一个副本把 open_outage_id 从 NULL 改成新值")
    void markOpenOutageMustBeConditionalOnNullInRealSql() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow.minusMinutes(1))));
        Long rowId = inTenant(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)).getId());

        assertThat(inTenant(() -> livenessMapper.markOpenOutage(rowId, 111L)))
            .as("第一次抢到").isEqualTo(1);
        assertThat(inTenant(() -> livenessMapper.markOpenOutage(rowId, 222L)))
            .as("已被占：必须返回 0（少了 `AND open_outage_id IS NULL` 这里会是 1）").isZero();
        assertThat(inTenant(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)).getOpenOutageId()))
            .as("标记必须仍是第一个副本写入的那条").isEqualTo(111L);
    }

    @Test
    @DisplayName("★ 扫描饥饿：被清理的垃圾候选不得永久占住候选批次（批次=1 时第二轮必须能发现真断档）")
    void garbageCandidatesMustNotStarveRealOutages() {
        LocalDateTime dbNow = livenessMapper.selectNow();
        AvailabilityProperties oneByOne = new AvailabilityProperties();
        oneByOne.setScanBatchSize(1);
        AvailabilityServiceImpl tightScan = new AvailabilityServiceImpl(livenessMapper, outageMapper,
            maintenanceWindowMapper, deviceMapper, oneByOne);
        // 两个「设备不存在」的垃圾活性行（id 更小 ⇒ 优先被候选查询选中）+ 一个真断档设备
        insertOrphanLiveness(1L, 999_998L, dbNow.minusHours(1));
        insertOrphanLiveness(2L, 999_999L, dbNow.minusHours(1));
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusHours(1))));

        assertThat(tightScan.scanAndOpenOutages()).as("第一轮只处理到垃圾候选").isZero();
        assertThat(tightScan.scanAndOpenOutages()).as("第二轮只剩垃圾").isZero();
        assertThat(tightScan.scanAndOpenOutages()).as("垃圾被清理后必须能发现真断档").isEqualTo(1);
    }

    /**
     * 插一条「设备不存在」的活性行（模拟设备已删除但活性行遗留）。
     *
     * <p>用 Mapper 而不是原生 SQL：{@code LocalDateTime.toString()} 是 {@code 2026-09-22T01:59:59}
     * （带 T），MySQL 的 DATETIME 字面量不认——CI 上一轮就是这么失败的（测试代码问题，不是产品问题）。</p>
     */
    private static void insertOrphanLiveness(Long rowId, Long orphanDeviceId, LocalDateTime lastGoodAt) {
        DeviceLiveness orphan = new DeviceLiveness();
        orphan.setId(rowId);
        // device_id 必须**每条不同**：uk_device_liveness 是 (tenant_id, device_id)，
        // 两条同 device_id 会撞唯一键（CI 实测 `Duplicate entry '920001-999999'`）
        orphan.setDeviceId(orphanDeviceId);
        orphan.setPollIntervalMs(5_000);
        orphan.setLastGoodAt(lastGoodAt);
        orphan.setFirstObservedAt(lastGoodAt);
        orphan.setLastObservedAt(lastGoodAt);
        inTenant(() -> {
            livenessMapper.insert(orphan);
            return 1;
        });
    }

    @Test
    @DisplayName("★ A14：观测时间戳在**数据库层**只前进——旧快照（另一个副本）写回不得回退")
    void livenessTimestampsMustNotRegressWhenWrittenOutOfOrder() {
        LocalDateTime dbNow = livenessMapper.selectNow().withNano(0);
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow)));
        DeviceLiveness row = inTenant(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)));

        // 模拟另一个副本：它手里是更早的快照（Java 侧「latest」管不到跨副本），直接写回
        DeviceLiveness stale = new DeviceLiveness();
        stale.setId(row.getId());
        stale.setPollIntervalMs(5_000);
        stale.setLastGoodAt(dbNow.minusMinutes(10));
        stale.setFirstObservedAt(dbNow.minusMinutes(10));
        stale.setLastObservedAt(dbNow.minusMinutes(10));
        inTenant(() -> {
            livenessMapper.reviveAndUpdate(stale);
            return 1;
        });

        DeviceLiveness after = inTenant(() -> livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, DEVICE)));
        assertThat(after.getLastGoodAt()).as("旧时间戳不得覆盖新值（靠 SQL 的 CASE 比较）").isEqualTo(dbNow);
        assertThat(after.getLastObservedAt()).as("最近观测同样只前进").isEqualTo(dbNow);
        assertThat(after.getFirstObservedAt()).as("首次观测取较小值").isEqualTo(dbNow.minusMinutes(10));
    }

    @Test
    @DisplayName("★ A11：明细超过上限被截断时，可用率/次数仍取**精确聚合**值（不因截断偏高）")
    void summaryMustBeExactWhenDetailsAreTruncated() {
        LocalDateTime dbNow = livenessMapper.selectNow().withNano(0);
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, dbNow)));
        // 201 条各 30 秒、相邻不重叠（步长=时长），全部落在窗口内；一条 SQL 插完
        LocalDateTime firstStart = dbNow.minusHours(2);
        StringBuilder sql = new StringBuilder("INSERT INTO outage_event (id, tenant_id, device_id, start_ts, "
            + "end_ts, duration_sec, reason, create_time, update_time) VALUES ");
        for (int i = 0; i <= AvailabilityRules.MAX_OUTAGE_ROWS; i++) {
            LocalDateTime start = firstStart.plusSeconds(i * 30L);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(").append(10_000 + i).append(", ").append(TENANT).append(", ").append(DEVICE)
                .append(", '").append(start.format(SQL_DATE_TIME)).append("', '")
                .append(start.plusSeconds(30).format(SQL_DATE_TIME)).append("', 30, 'NO_GOOD_DATA', "
                    + "NOW(), NOW())");
        }
        execute(sql.toString());

        AvailabilityResp resp = inTenant(() -> service.query(DEVICE, dbNow.minusHours(4), dbNow));

        assertThat(resp.getOutageCount()).as("次数是精确值（201），不是明细条数").isEqualTo(201);
        assertThat(resp.getOutageSeconds()).as("合计 201×30s，来自聚合而非明细求和").isEqualTo(6_030L);
        assertThat(resp.getOutages()).as("明细按最新优先截断到上限").hasSize(AvailabilityRules.MAX_OUTAGE_ROWS);
        assertThat(resp.getTruncated()).isTrue();
        assertThat(resp.getAvailability()).as("1 - 6030/14400").isEqualByComparingTo(new BigDecimal("0.581250"));
    }

    @Test
    @DisplayName("★ A11：跨窗口边界的断档只算交集（SQL 侧裁剪），可用率不被高估")
    void summaryMustClampOutagesToWindow() {
        LocalDateTime dbNow = livenessMapper.selectNow().withNano(0);
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusMinutes(30))));
        // 断档 [now-20min, now-5min]，窗口 [now-10min, now] ⇒ 只算 [now-10min, now-5min] = 300s
        execute("INSERT INTO outage_event (id, tenant_id, device_id, start_ts, end_ts, duration_sec, reason, "
            + "create_time, update_time) VALUES (20001, " + TENANT + ", " + DEVICE + ", '"
            + dbNow.minusMinutes(20).format(SQL_DATE_TIME) + "', '"
            + dbNow.minusMinutes(5).format(SQL_DATE_TIME) + "', 900, 'NO_GOOD_DATA', NOW(), NOW())");

        AvailabilityResp resp = inTenant(() -> service.query(DEVICE, dbNow.minusMinutes(10), dbNow));

        assertThat(resp.getWindowSeconds()).isEqualTo(600L);
        assertThat(resp.getOutageSeconds()).as("只算窗口内那 300 秒").isEqualTo(300L);
        assertThat(resp.getLongestOutageSeconds()).isEqualTo(300L);
        assertThat(resp.getAvailability()).isEqualByComparingTo(new BigDecimal("0.500000"));
    }

    @Test
    @DisplayName("★ A3：维护窗口必须**同时**从统计总时长与断档里排除（计划停机不算断档）")
    void maintenanceWindowMustBeExcludedFromAvailability() {
        LocalDateTime dbNow = livenessMapper.selectNow().withNano(0);
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusHours(3))));
        // 造一段 1 小时断档：[now-2h, now-1h]
        execute("INSERT INTO outage_event (id, tenant_id, device_id, start_ts, end_ts, duration_sec, reason, "
            + "create_time, update_time) VALUES (30001, " + TENANT + ", " + DEVICE + ", '"
            + dbNow.minusHours(2).format(SQL_DATE_TIME) + "', '" + dbNow.minusHours(1).format(SQL_DATE_TIME)
            + "', 3600, 'NO_GOOD_DATA', NOW(), NOW())");
        // ① 该设备的维护窗口正好覆盖这段断档；② 另一台设备的窗口不得影响本设备
        insertMaintenanceWindow(DEVICE, dbNow.minusHours(2), dbNow.minusHours(1), "MANUAL");
        insertMaintenanceWindow(999_999L, dbNow.minusHours(4), dbNow, "MANUAL");

        AvailabilityResp resp = inTenant(() -> service.query(DEVICE, dbNow.minusHours(4), dbNow));

        assertThat(resp.getMaintenanceSeconds()).as("只算本设备（或租户级）的窗口：1 小时").isEqualTo(3_600L);
        assertThat(resp.getEffectiveWindowSeconds()).as("分母 = 4h − 1h 维护").isEqualTo(3 * 3_600L);
        assertThat(resp.getOutageSeconds()).as("维护内的断档被剔除").isZero();
        assertThat(resp.getOutageInMaintenanceSeconds()).as("剔除部分要能解释").isEqualTo(3_600L);
        assertThat(resp.getAvailability()).as("计划停机不算断档 ⇒ 可用率 100%")
            .isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(resp.getMaintenanceWindows()).as("响应回显窗口，便于解释口径").hasSize(1);
    }

    @Test
    @DisplayName("★ A3：租户级窗口（device_id 为空）对该租户所有设备生效")
    void tenantWideMaintenanceWindowMustApplyToEveryDevice() {
        LocalDateTime dbNow = livenessMapper.selectNow().withNano(0);
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD,
            dbNow.minusHours(3))));
        insertMaintenanceWindow(null, dbNow.minusHours(1), dbNow, "LEASE_HANDOVER");

        AvailabilityResp resp = inTenant(() -> service.query(DEVICE, dbNow.minusHours(4), dbNow));

        assertThat(resp.getMaintenanceSeconds()).as("租户级窗口 1 小时").isEqualTo(3_600L);
        assertThat(resp.getEffectiveWindowSeconds()).isEqualTo(3 * 3_600L);
        assertThat(resp.getMaintenanceWindows()).singleElement()
            .satisfies(dto -> assertThat(dto.getSource()).isEqualTo("LEASE_HANDOVER"));
    }

    /** 插一条维护窗口（deviceId 为空=租户级）。 */
    private static void insertMaintenanceWindow(Long deviceId, LocalDateTime from, LocalDateTime to,
            String source) {
        MaintenanceWindow row = new MaintenanceWindow();
        row.setDeviceId(deviceId);
        row.setStartTs(from);
        row.setEndTs(to);
        row.setSource(source);
        row.setReason("IT 造数");
        inTenant(() -> {
            maintenanceWindowMapper.insert(row);
            return 1;
        });
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
        execute("DELETE FROM maintenance_window WHERE tenant_id = " + TENANT);
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
