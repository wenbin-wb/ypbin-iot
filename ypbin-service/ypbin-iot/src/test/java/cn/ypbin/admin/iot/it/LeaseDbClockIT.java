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

import cn.ypbin.admin.iot.entity.AccessNode;
import cn.ypbin.admin.iot.entity.TenantLedger;
import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import cn.ypbin.admin.iot.lease.AccessNodeRegistry;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseProperties;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.LeaseState;
import cn.ypbin.admin.iot.mapper.AccessNodeMapper;
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.admin.iot.service.impl.LeaseServiceImpl;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M0b-4 的**真库**用例：租约时间基准必须是**数据库时钟**，不是 JVM 时钟。
 *
 * <p><b>怎么做到「能区分」而不是恒真</b>：把本用例自己的连接池会话时区设成 {@value #SESSION_TZ}
 * （{@code SET time_zone}），于是 DB 的 {@code NOW()} 与 JVM 的 {@code LocalDateTime.now()} 相差数小时。
 * 若实现仍在读 JVM 时钟，落库的 {@code lease_expire_at} 就会偏离「DB 现在 + ttl」数小时，本用例立刻失败；
 * 只有取 DB 时钟才会通过。JVM 时区恰好等于该偏移时（理论上可能），用例会显式跳过「必须偏离」那半条断言，
 * 而不是假装通过。</p>
 *
 * <p>为什么这件事重要：租约的写入与过期判定若各读本机时钟，**时钟快的节点会提前抢走仍在正常续约的租户**
 * ——多副本下表现为「莫名频繁的接管」，而且没有任何显式错误。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@EnabledIfMySqlAvailable
class LeaseDbClockIT {

    /** 会话时区：刻意选一个与常见 JVM 时区（UTC / +08:00）都不同的偏移。 */
    private static final String SESSION_TZ = "-11:00";

    private static final String NODE = "access-it-dbclock";

    private static final Long TENANT = 930001L;

    private static final Duration TTL = Duration.ofSeconds(30);

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static TenantNodeAssignmentMapper assignmentMapper;
    private static AccessNodeMapper accessNodeMapper;
    private static TenantLedgerMapper ledgerMapper;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(4);
        // 关键：本用例的每条连接都把会话时区设成 SESSION_TZ ⇒ DB 的 NOW() 与 JVM 时钟产生可观测偏移
        config.setConnectionInitSql("SET time_zone = '" + SESSION_TZ + "'");
        dataSource = new HikariDataSource(config);
        ItSchema.ensure(dataSource, REPO_ROOT);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new MybatisPlusInterceptor());
        configuration.addMapper(TenantNodeAssignmentMapper.class);
        configuration.addMapper(AccessNodeMapper.class);
        configuration.addMapper(TenantLedgerMapper.class);
        for (Class<?> entity : List.of(TenantNodeAssignment.class, AccessNode.class,
                TenantLedger.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        sqlSessionTemplate = new SqlSessionTemplate(factory);
        assignmentMapper = sqlSessionTemplate.getMapper(TenantNodeAssignmentMapper.class);
        accessNodeMapper = sqlSessionTemplate.getMapper(AccessNodeMapper.class);
        ledgerMapper = sqlSessionTemplate.getMapper(TenantLedgerMapper.class);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            purge();
            dataSource.close();
        }
    }

    @Test
    @DisplayName("★ 租约到期时间必须来自数据库时钟：会话时区偏移数小时，落库值仍应等于「DB 现在 + ttl」")
    void leaseExpiryMustFollowDatabaseClock() {
        purge();
        LocalDateTime dbNowBefore = assignmentMapper.selectNow();
        registerNode();
        insertAssignableTenant();

        LeaseProperties properties = new LeaseProperties();
        properties.setTtl(TTL);
        properties.setAssignableTenantIds(List.of());
        LeaseServiceImpl service = new LeaseServiceImpl(assignmentMapper,
            new AccessNodeRegistry(accessNodeMapper), ledgerMapper, properties,
            new SimpleMeterRegistry(), transactionTemplate);

        service.acquire(acquireReq());

        TenantNodeAssignment assignment = assignmentMapper.selectOne(
            Wrappers.<TenantNodeAssignment>lambdaQuery()
                .eq(TenantNodeAssignment::getTenantId, TENANT)
                .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode()));
        assertThat(assignment).as("必须完成一次分配").isNotNull();
        LocalDateTime stored = assignment.getLeaseExpireAt();

        // ① 与「DB 现在 + ttl」一致（同一会话时区，允许几十秒误差以吸收执行耗时）
        long diffFromDb = Math.abs(ChronoUnit.SECONDS.between(stored, dbNowBefore.plus(TTL)));
        assertThat(diffFromDb)
            .as("到期时间应≈DB 现在(%s)+ttl，实际=%s", dbNowBefore, stored)
            .isLessThan(120);

        // ② 必须**偏离 JVM 时钟**（否则说明还在读本机时钟）——仅在两个时区确实不同才断言
        ZoneOffset jvmOffset = ZoneId.systemDefault().getRules().getOffset(Instant.now());
        if (!jvmOffset.equals(ZoneOffset.of(SESSION_TZ))) {
            long diffFromJvm = Math.abs(ChronoUnit.SECONDS.between(stored,
                LocalDateTime.now().plus(TTL)));
            assertThat(diffFromJvm)
                .as("若实现读的是 JVM 时钟，这里会相差数小时（JVM 偏移=%s，DB 会话时区=%s）",
                    jvmOffset, SESSION_TZ)
                .isGreaterThan(3600);
        }
    }

    @Test
    @DisplayName("★ M0b-4：领取/续约响应必须回传**数据库时钟**（接入侧据此校准到期判据）")
    void responsesMustExposeDatabaseClockForAccessSide() {
        purge();
        registerNode();
        insertAssignableTenant();

        LeaseProperties properties = new LeaseProperties();
        properties.setTtl(TTL);
        properties.setAssignableTenantIds(List.of());
        LeaseServiceImpl service = new LeaseServiceImpl(assignmentMapper,
            new AccessNodeRegistry(accessNodeMapper), ledgerMapper, properties,
            new SimpleMeterRegistry(), transactionTemplate);

        LocalDateTime before = assignmentMapper.selectNow();
        LeaseAcquireResp acquired = service.acquire(acquireReq());
        LocalDateTime after = assignmentMapper.selectNow();

        assertThat(acquired.getServerTime()).as("领取响应必须带服务端时间").isNotNull();
        assertThat(ChronoUnit.SECONDS.between(before, acquired.getServerTime()))
            .as("服务端时间应落在本次调用窗口内").isGreaterThanOrEqualTo(-1L);
        assertThat(ChronoUnit.SECONDS.between(acquired.getServerTime(), after))
            .as("服务端时间应落在本次调用窗口内").isGreaterThanOrEqualTo(-1L);

        LeaseRenewReq renew = new LeaseRenewReq();
        renew.setAccessNode(NODE);
        LeaseRenewItem item = new LeaseRenewItem();
        item.setTenantId(TENANT);
        item.setEpoch(0L);
        renew.setLeases(List.of(item));
        LeaseRenewResp renewed = service.renew(renew);

        assertThat(renewed.getServerTime()).as("续约响应必须带服务端时间").isNotNull();
        assertThat(ChronoUnit.SECONDS.between(acquired.getServerTime(), renewed.getServerTime()))
            .as("续约的服务端时间不应早于领取（同一数据库时钟）").isGreaterThanOrEqualTo(-1L);
    }

    private static void registerNode() {
        transactionTemplate.executeWithoutResult(status -> {
            accessNodeMapper.delete(Wrappers.<AccessNode>lambdaQuery()
                .eq(AccessNode::getAccessNode, NODE));
            AccessNode node = new AccessNode();
            node.setAccessNode(NODE);
            node.setMaxTenants(1);
            accessNodeMapper.insert(node);
        });
    }

    private static void insertAssignableTenant() {
        transactionTemplate.executeWithoutResult(status -> {
            TenantLedger ledger = new TenantLedger();
            ledger.setTenantId(TENANT);
            ledger.setAssignable(Boolean.TRUE);
            ledger.setConfigEpoch(1L);
            ledgerMapper.insert(ledger);
        });
    }

    private static LeaseAcquireReq acquireReq() {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(NODE);
        return req;
    }

    private static void purge() {
        execute("DELETE FROM tenant_node_assignment WHERE tenant_id = " + TENANT);
        execute("DELETE FROM tenant_ledger WHERE tenant_id = " + TENANT);
        execute("DELETE FROM access_node WHERE access_node = '" + NODE + "'");
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
