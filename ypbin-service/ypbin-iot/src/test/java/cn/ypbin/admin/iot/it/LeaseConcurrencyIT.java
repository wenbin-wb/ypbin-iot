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
import cn.ypbin.admin.iot.lease.LeaseProperties;
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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-2 验收口径「**多副本并发单赢家**」的真库用例（M0b-1）。
 *
 * <p><b>它到底在证什么</b>：容量判定原先在 iot 服务的**进程内计数**里做。同一 {@code node-id} 被两个副本
 * 同时使用时（滚动重启、误配置），两个副本都会数到 0 并各自按满容量分配 ⇒ **超额分配**，
 * 而服务端只打一条 WARN，从数据上看不出来。落库后靠节点行 {@code SELECT ... FOR UPDATE} 串行化。</p>
 *
 * <p><b>怎么模拟两个副本</b>：建**两个 {@link LeaseServiceImpl} 实例、各自一个
 * {@link AccessNodeRegistry}**（因而各有独立的进程内锁），但**共用同一个数据源与同一个 nodeId**。
 * 这正是跨副本的等价形态——若正确性依赖进程内锁，本用例就会失败。</p>
 *
 * <p><b>为什么必须用 Spring 管理的会话</b>：{@code FOR UPDATE} 的锁只在**同一个事务连接**上有效。
 * 若像普通 IT 那样用裸 {@code SqlSessionFactory}（自带 JDBC 事务、自动提交），锁会在语句结束即释放，
 * 用例会假绿。因此这里用 mybatis-spring 的 {@link SqlSessionTemplate} + {@link DataSourceTransactionManager}，
 * 让 Mapper 调用加入 {@code TransactionTemplate} 开启的事务。</p>
 *
 * <p><b>运行方式</b>：默认跳过（{@code @EnabledIfMySqlAvailable}）。设置 {@code YPBIN_TEST_MYSQL_URL}
 * 指向外部实例，或让本机 Docker 可用（容器回退）。由 {@code -Pit} 触发 failsafe：</p>
 * <pre>{@code
 * mvn -Pit -pl ypbin-service/ypbin-iot verify
 * }</pre>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@EnabledIfMySqlAvailable
class LeaseConcurrencyIT {

    /** 测试用节点（避开生产 node-id）。 */
    private static final String NODE = "access-it-concurrency";

    /** 节点容量：故意设小，便于观察「是否超额」。 */
    private static final int CAPACITY = 2;

    /** 可分配租户（5 个 ⇒ 远大于容量，超额分配必然可见）。 */
    private static final List<Long> TENANTS =
        List.of(910001L, 910002L, 910003L, 910004L, 910005L);

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
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);

        // 幂等建表（与其它 IT 共用同一实例与同一助手）
        ItSchema.ensure(dataSource, REPO_ROOT);

        // mybatis-spring：让 Mapper 调用加入 Spring 事务（FOR UPDATE 的锁才真的持有到提交）
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new MybatisPlusInterceptor());
        configuration.addMapper(TenantNodeAssignmentMapper.class);
        configuration.addMapper(AccessNodeMapper.class);
        configuration.addMapper(TenantLedgerMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        // MP 的 Lambda 包装器需要实体的 TableInfo 缓存（注解式 Mapper 不会自动初始化）
        for (Class<?> entity : List.of(TenantNodeAssignment.class, AccessNode.class,
                TenantLedger.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
        sqlSessionTemplate = new SqlSessionTemplate(factory);
        assignmentMapper = sqlSessionTemplate.getMapper(TenantNodeAssignmentMapper.class);
        accessNodeMapper = sqlSessionTemplate.getMapper(AccessNodeMapper.class);
        ledgerMapper = sqlSessionTemplate.getMapper(TenantLedgerMapper.class);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        purge();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            purge();
            dataSource.close();
        }
    }

    @Test
    @DisplayName("★ 多副本并发单赢家：两个副本共用同一 nodeId 并发领取，总分配不得超过容量")
    void concurrentReplicasMustNotOverAllocate() throws Exception {
        registerNode();
        insertAssignableTenants(TENANTS);

        LeaseServiceImpl replicaA = newReplica();
        LeaseServiceImpl replicaB = newReplica();

        // 两个线程同时开始，最大化「两个副本都读到旧计数」的窗口
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> taskA = () -> {
                barrier.await(30, TimeUnit.SECONDS);
                replicaA.acquire(acquireReq());
                return null;
            };
            Callable<Void> taskB = () -> {
                barrier.await(30, TimeUnit.SECONDS);
                replicaB.acquire(acquireReq());
                return null;
            };
            List<Future<Void>> futures = new ArrayList<>();
            futures.add(pool.submit(taskA));
            futures.add(pool.submit(taskB));
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long active = countActiveOfNode();
        assertThat(active)
            .as("同一 nodeId 的两个副本并发领取后，持有租户数不得超过容量 %d（超额即 M0b-1 失效）",
                CAPACITY)
            .isLessThanOrEqualTo(CAPACITY);
        assertThat(active).as("5 个可分配租户、容量 2 ⇒ 应当正好占满容量").isEqualTo(CAPACITY);
        assertThat(distinctTenantCount())
            .as("每个租户只允许一行归属（唯一键 + 原子分配）")
            .isEqualTo(active);
    }

    @Test
    @DisplayName("M0b-2：可分配租户来自租户台账（配置为空时也能分配，证明来源已切换）")
    void assignableTenantsComeFromLedger() {
        LeaseServiceImpl replica = newReplica();
        // 台账只放一个可分配租户；properties.assignable-tenant-ids 保持为空
        insertAssignableTenants(List.of(TENANTS.get(0)));

        replica.acquire(acquireReq());

        assertThat(countActiveOfNode())
            .as("台账里的唯一可分配租户必须被分配（若仍在读配置，这里会是 0）")
            .isEqualTo(1);
        assertThat(ledgerMapper.selectCount(Wrappers.<TenantLedger>lambdaQuery()
            .eq(TenantLedger::getAssignable, Boolean.TRUE))).isEqualTo(1L);
    }

    /** 新建一个「副本」：独立注册表（独立进程内锁），共用同一数据源。 */
    private static LeaseServiceImpl newReplica() {
        AccessNodeRegistry registry = new AccessNodeRegistry(accessNodeMapper);
        LeaseProperties properties = new LeaseProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setAssignableTenantIds(List.of());
        return new LeaseServiceImpl(assignmentMapper, registry, ledgerMapper, properties,
            new SimpleMeterRegistry(), transactionTemplate);
    }

    private static void registerNode() {
        transactionTemplate.executeWithoutResult(status -> {
            accessNodeMapper.delete(Wrappers.<AccessNode>lambdaQuery()
                .eq(AccessNode::getAccessNode, NODE));
            AccessNode node = new AccessNode();
            node.setAccessNode(NODE);
            node.setMaxTenants(CAPACITY);
            accessNodeMapper.insert(node);
        });
    }

    private static void insertAssignableTenants(List<Long> tenantIds) {
        // ⚠️ 必须**物理删除**：BaseEntity 是逻辑删除，而 `uk_tenant_ledger(tenant_id)` 不含量删除标记，
        //    软删后同一 tenant_id 再插会撞唯一键（DuplicateKeyException）。这也提示 M0b-3 的台账写入口
        //    必须走「复活已有行」而不是盲目 insert。
        physicalDeleteTenants();
        transactionTemplate.executeWithoutResult(status -> {
            for (Long tenantId : tenantIds) {
                TenantLedger ledger = new TenantLedger();
                ledger.setTenantId(tenantId);
                ledger.setAssignable(Boolean.TRUE);
                ledger.setConfigEpoch(1L);
                ledgerMapper.insert(ledger);
            }
        });
    }

    private static LeaseAcquireReq acquireReq() {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(NODE);
        return req;
    }

    private static long countActiveOfNode() {
        return assignmentMapper.selectCount(Wrappers.<TenantNodeAssignment>lambdaQuery()
            .eq(TenantNodeAssignment::getAccessNode, NODE)
            .eq(TenantNodeAssignment::getState, LeaseState.ACTIVE.getCode()));
    }

    private static long distinctTenantCount() {
        return assignmentMapper.selectList(Wrappers.<TenantNodeAssignment>lambdaQuery()
                .select(TenantNodeAssignment::getTenantId)
                .eq(TenantNodeAssignment::getAccessNode, NODE))
            .stream()
            .map(TenantNodeAssignment::getTenantId)
            .distinct()
            .count();
    }

    private static void purge() {
        physicalDeleteTenants();
        accessNodeMapper.delete(Wrappers.<AccessNode>lambdaQuery()
            .eq(AccessNode::getAccessNode, NODE));
    }

    /** 物理删除测试租户的台账与归属（逻辑删除会让唯一键继续占用）。 */
    private static void physicalDeleteTenants() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ledger = connection.prepareStatement(
                    "DELETE FROM tenant_ledger WHERE tenant_id BETWEEN 910001 AND 910005");
                PreparedStatement assignment = connection.prepareStatement(
                    "DELETE FROM tenant_node_assignment WHERE tenant_id BETWEEN 910001 AND 910005")) {
            ledger.executeUpdate();
            assignment.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("清理测试租户失败", ex);
        }
    }
}
