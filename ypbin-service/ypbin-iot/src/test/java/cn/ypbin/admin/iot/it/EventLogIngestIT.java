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

import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotEventLog;
import cn.ypbin.admin.iot.event.EventIngestItemDto;
import cn.ypbin.admin.iot.event.EventIngestReq;
import cn.ypbin.admin.iot.event.EventIngestResult;
import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotEventLogMapper;
import cn.ypbin.admin.iot.service.impl.IotEventServiceImpl;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 运行期事件实例（G6）的**真库集成测试**：幂等（含并发重复投递）、查询过滤、空结果、跨租户不可见。
 *
 * <p><b>为什么必须真库</b>：单测只能 mock Mapper，咬不到三件事——
 * ① {@code ON DUPLICATE KEY UPDATE} 在真实 MySQL 上是否真的幂等且不抛异常；
 * ② 租户拦截器（生产同款）是否真的给<b>自定义批量 SQL</b> 追加了 {@code tenant_id}；
 * ③ 并发重复投递时唯一键是否真的只允许一行落地。这三条是本功能的安全与正确性核心，
 * 故用 {@code deploy/sql/006-iot-schema.sql} 的真实 DDL + 生产同款租户插件在本类里钉住。</p>
 *
 * <p><b>地面真值走原生 JDBC</b>：所有「库里到底有几行」的断言都绕过 MyBatis 与租户插件直连查询——
 * 用同一个过滤器证明过滤器有效是自证循环（同 {@code IotTenantIsolationIT} 的口径）。</p>
 *
 * <p><b>运行方式</b>：默认跳过（{@code @EnabledIfMySqlAvailable}）。设置 {@code YPBIN_TEST_MYSQL_URL}
 * 指向外部实例，或让本机 Docker 可用（容器回退）。由 {@code -Pit} 触发 failsafe：
 * {@code mvn -Pit -pl ypbin-service/ypbin-iot verify}。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@EnabledIfMySqlAvailable
class EventLogIngestIT {

    /** 测试用租户 A（避开真实数据：取明显不可能是生产租户的高位值）。 */
    private static final Long TENANT = 920001L;

    /** 测试用租户 B（跨租户不可见的反例）。 */
    private static final Long OTHER_TENANT = 920002L;

    /** 租户 A 的设备。 */
    private static final Long DEVICE = 9200001L;

    /** 租户 B 的设备。 */
    private static final Long OTHER_DEVICE = 9200002L;

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;

    private static IotEventLogMapper eventLogMapper;

    private static IotEventServiceImpl service;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        ItSchema.ensure(dataSource, REPO_ROOT);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 生产同款租户拦截器：自定义 SQL（批量插入 / IN 预查）也必须被追加 tenant_id
        TenantProperties tenantProperties = new TenantProperties();
        tenantProperties.setFailOnMissingTenant(true);
        MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(
            new DefaultTenantLineHandler(Optional::empty, tenantProperties)));
        // 分页拦截器：没有它 selectPage 不会算 total、也不会 LIMIT（本 IT 一开始就踩到：
        // 分母为 0 ⇒ 「时间范围/级别过滤」用例拿到 total=0 而误判成过滤失效）
        plugins.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(plugins);
        configuration.addMapper(IotEventLogMapper.class);
        configuration.addMapper(IotDeviceMapper.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, IotEventLog.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        eventLogMapper = sessionTemplate.getMapper(IotEventLogMapper.class);
        IotDeviceMapper deviceMapper = sessionTemplate.getMapper(IotDeviceMapper.class);
        service = new IotEventServiceImpl(eventLogMapper, deviceMapper);

        cleanup();
        seedDevice(TENANT, DEVICE, "it-events-a");
        seedDevice(OTHER_TENANT, OTHER_DEVICE, "it-events-b");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (dataSource != null) {
            cleanup();
            dataSource.close();
        }
    }

    /** 每个用例前清掉事件行：用例之间不得靠执行顺序传递状态（本仓有过顺序耦合被复核点名的教训）。 */
    @BeforeEach
    void resetEventRows() throws SQLException {
        execute("DELETE FROM iot_event_log WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ")");
    }

    @Test
    @DisplayName("★ 幂等：同一条事件投两次，库里只有一行，第二次被明确报为「幂等命中」")
    void duplicateDeliveryMustNotCreateSecondRow() throws SQLException {
        Long ts = epoch(T0());

        EventIngestResult first = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-1", ts)));
        EventIngestResult second = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-1", ts)));

        assertThat(first.getAccepted()).isEqualTo(1);
        assertThat(second.getAccepted()).isZero();
        assertThat(second.getDuplicated()).as("重复投递必须被报成幂等命中（调用方可安全清理重放队列）")
            .isEqualTo(1);
        assertThat(rawCount("idempotent_key = 'k-1'")).as("重复投递不得产生第二行").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 幂等：同一批里同一幂等键出现两次，只落一行")
    void sameKeyTwiceInOneBatchMustInsertOnce() throws SQLException {
        Long ts = epoch(T0());

        EventIngestResult result = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-2", ts),
            item(DEVICE, "overTemp", "warn", "k-2", ts)));

        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getDuplicated()).isEqualTo(1);
        assertThat(rawCount("idempotent_key = 'k-2'")).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 并发重复投递：4 个线程同时投同一条事件，唯一键必须让它只落一行")
    void concurrentDuplicateDeliveryMustStillInsertOneRow() throws Exception {
        Long ts = epoch(T0());
        CyclicBarrier barrier = new CyclicBarrier(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<EventIngestResult> results = new ArrayList<>();
        try {
            List<Callable<EventIngestResult>> tasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                tasks.add(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return service.ingest(req(item(DEVICE, "overTemp", "warn", "k-race", ts)));
                });
            }
            List<Future<EventIngestResult>> futures = new ArrayList<>();
            for (Callable<EventIngestResult> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<EventIngestResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rawCount("idempotent_key = 'k-race'"))
            .as("并发重复投递后库里必须恰好一行（应用层先查后插有竞态窗口，靠唯一键兜底）")
            .isEqualTo(1L);
        assertThat(results).as("四个请求都必须成功返回（命中唯一键不得抛异常）").hasSize(4);
        // 并发下「谁算新落库」不可精确归属：四个调用方都在插入前做了预查（当时都没有），
        // 于是各自把这条算成自己的 accepted——这是**已声明的语义边界**（见 EventIngestResult 的说明），
        // 唯一可保证、也是真正要保证的不变量是上面那条「库里只有一行」。
        // 这里再钉一条**不依赖归属口径**的不变量：四个调用方合计必须把「同一条事件」各记一次
        // （要么 accepted、要么 duplicated），即**没有调用方把事件整条丢掉**。
        // ⚠️ 它**不能**发现「重复计数」——每方只投 1 条时 accepted≤1、duplicated≤1、discarded≤1，
        // Σ==4 在「四方各自 accepted=1」这种已声明边界下同样成立（复核指出，注释不许夸大能力）。
        assertThat(results.stream().mapToInt(EventIngestResult::getAccepted).sum())
            .as("至少有一方报告了新落库").isGreaterThanOrEqualTo(1);
        assertThat(results.stream()
            .mapToInt(r -> r.getAccepted() + r.getDuplicated() + r.getDiscarded()).sum())
            .as("四个调用方合计记满 4 条（各自一条）：少记说明有调用方把事件整条丢了"
                + "（注意：本断言对「重复计数」无鉴别力，见上方注释）")
            .isEqualTo(4);
    }

    @Test
    @DisplayName("★ 查询：时间范围左闭右开 + 级别过滤 + 最新优先 + 分页；库中真实行数与返回一致")
    void queryMustFilterByRangeLevelAndOrder() {
        LocalDateTime base = T0();
        service.ingest(req(item(DEVICE, "e-info", "info", "q-1", epoch(base.minusMinutes(30))),
            item(DEVICE, "e-warn", "warn", "q-2", epoch(base.minusMinutes(10))),
            item(DEVICE, "e-error", "error", "q-3", epoch(base))));

        EventLogQuery all = new EventLogQuery();
        all.setFrom(base.minusHours(1));
        all.setTo(base.plusMinutes(1));
        PageResult<EventLogResp> allPage = inTenant(() -> service.pageEvents(DEVICE, all));
        assertThat(allPage.getTotal()).isEqualTo(3L);
        assertThat(allPage.getItems()).extracting(EventLogResp::getEventCode)
            .as("必须按事件发生时刻倒序（最新优先）")
            .containsExactly("e-error", "e-warn", "e-info");

        EventLogQuery warnOnly = new EventLogQuery();
        warnOnly.setLevel("warn");
        PageResult<EventLogResp> warnPage = inTenant(() -> service.pageEvents(DEVICE, warnOnly));
        assertThat(warnPage.getItems()).extracting(EventLogResp::getEventCode).containsExactly("e-warn");

        // 左闭右开：from = e-warn 的时刻 ⇒ 只含 e-warn 与其后（即 e-warn、e-error）
        EventLogQuery halfOpen = new EventLogQuery();
        halfOpen.setFrom(base.minusMinutes(10));
        halfOpen.setTo(base.plusMinutes(1));
        PageResult<EventLogResp> halfOpenPage = inTenant(() -> service.pageEvents(DEVICE, halfOpen));
        assertThat(halfOpenPage.getItems()).extracting(EventLogResp::getEventCode)
            .containsExactly("e-error", "e-warn");

        // 分页：每页 2 条 ⇒ 第二页只剩 1 条，且不重复第一页的行
        EventLogQuery firstPage = new EventLogQuery();
        firstPage.setPage(1);
        firstPage.setPageSize(2);
        EventLogQuery secondPage = new EventLogQuery();
        secondPage.setPage(2);
        secondPage.setPageSize(2);
        PageResult<EventLogResp> pageOne = inTenant(() -> service.pageEvents(DEVICE, firstPage));
        PageResult<EventLogResp> pageTwo = inTenant(() -> service.pageEvents(DEVICE, secondPage));
        assertThat(pageOne.getItems()).hasSize(2);
        assertThat(pageTwo.getItems()).hasSize(1);
        assertThat(pageOne.getItems()).extracting(EventLogResp::getId)
            .doesNotContainAnyElementsOf(pageTwo.getItems().stream().map(EventLogResp::getId).toList());
    }

    @Test
    @DisplayName("★ 空结果：没有命中的窗口返回空集合（不是 null、不是异常）")
    void queryMustReturnEmptyPageForUnknownWindow() {
        service.ingest(req(item(DEVICE, "e-info", "info", "empty-1", epoch(T0()))));

        EventLogQuery future = new EventLogQuery();
        future.setFrom(T0().plusDays(1));
        future.setTo(T0().plusDays(2));
        PageResult<EventLogResp> result = inTenant(() -> service.pageEvents(DEVICE, future));

        assertThat(result.getItems()).isNotNull().isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    @Test
    @DisplayName("★ 跨租户不可见：B 租户的事件对 A 不可见，且 A 查 B 的设备按「设备不存在」处理")
    void crossTenantEventsMustNotBeVisible() throws SQLException {
        service.ingest(req(item(OTHER_DEVICE, "e-b", "warn", "b-1", epoch(T0()))));

        assertThat(rawCountForTenant(OTHER_TENANT))
            .as("反证：B 的事件确实在库里（否则下面的「看不见」可能是数据不存在）")
            .isEqualTo(1L);

        EventLogQuery query = new EventLogQuery();
        assertThatThrownBy(() -> inTenant(() -> service.pageEvents(OTHER_DEVICE, query)))
            .as("A 租户查 B 租户的设备必须按「设备不存在」处理，不泄露存在性")
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");

        // A 查自己的设备：只看得到自己的事件（B 的那条不在结果里）
        service.ingest(req(item(DEVICE, "e-a", "info", "a-1", epoch(T0()))));
        PageResult<EventLogResp> own = inTenant(() -> service.pageEvents(DEVICE, new EventLogQuery()));
        assertThat(own.getItems()).extracting(EventLogResp::getEventCode).containsExactly("e-a");
        assertThat(own.getTotal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 上报时刻按平台时区落库并可原样读回（epoch → DATETIME → 视图）")
    void eventTsMustRoundTripThroughPlatformZone() {
        LocalDateTime ts = T0();
        service.ingest(req(item(DEVICE, "e-info", "info", "tz-1", epoch(ts))));

        PageResult<EventLogResp> result = inTenant(() -> service.pageEvents(DEVICE, new EventLogQuery()));

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventTs())
            .as("上报 epoch 毫秒 → 平台时区（GMT+8）墙上时间必须原样往返")
            .isEqualTo(ts);
    }

    /** 上报时间基准（秒精度，与 DATETIME 列一致）。 */
    private static LocalDateTime T0() {
        return LocalDateTime.now().withNano(0).minusMinutes(30);
    }

    /** 墙上时间 → epoch 毫秒（按平台时区，与真实上报一致）。 */
    private static Long epoch(LocalDateTime ts) {
        return ts.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
    }

    /** 构造一条上报项。 */
    private static EventIngestItemDto item(Long deviceId, String code, String level, String key, Long ts) {
        EventIngestItemDto item = new EventIngestItemDto();
        item.setDeviceId(deviceId);
        item.setEventCode(code);
        item.setLevel(level);
        item.setIdempotentKey(key);
        item.setTs(ts);
        return item;
    }

    /** 构造上报请求。 */
    private static EventIngestReq req(EventIngestItemDto... items) {
        EventIngestReq req = new EventIngestReq();
        req.setItems(new ArrayList<>(List.of(items)));
        return req;
    }

    /** 进入租户 A 上下文执行（查询路径必然有租户身份，网关侧注入）。 */
    private static <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.executeWithTenant(TENANT, action);
    }

    /** 原生 JDBC 统计租户 A 设备的事件行数（地面真值，绕过租户插件）。 */
    private static long rawCount(String extraWhere) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM iot_event_log WHERE tenant_id = " + TENANT
                     + " AND device_id = " + DEVICE + " AND " + extraWhere);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** 原生 JDBC 统计某租户的事件行数。 */
    private static long rawCountForTenant(Long tenantId) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM iot_event_log WHERE tenant_id = " + tenantId);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** 清理本类写入的事件与设备行。 */
    private static void cleanup() throws SQLException {
        execute("DELETE FROM iot_event_log WHERE tenant_id IN (" + TENANT + ", " + OTHER_TENANT + ")");
        execute("DELETE FROM iot_device WHERE id IN (" + DEVICE + ", " + OTHER_DEVICE + ")");
    }

    /** 种一台设备（原生 JDBC：避免用例依赖租户上下文的装配顺序）。 */
    private static void seedDevice(Long tenantId, Long deviceId, String code) throws SQLException {
        execute("INSERT INTO iot_device (id, tenant_id, device_code, device_name, protocol, endpoint, "
            + "online_status, create_time, update_time, status, is_deleted) VALUES ("
            + deviceId + ", " + tenantId + ", '" + code + "', 'IT 事件设备', 'tcp', "
            + "'tcp://127.0.0.1:15002', 'unknown', NOW(), NOW(), 1, 0)");
    }

    /** 执行一条原生 SQL。 */
    private static void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 打开一条原生连接。 */
    private static Connection openConnection() throws SQLException {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        return DriverManager.getConnection(properties.get("spring.datasource.url"),
            properties.get("spring.datasource.username"), properties.get("spring.datasource.password"));
    }
}
