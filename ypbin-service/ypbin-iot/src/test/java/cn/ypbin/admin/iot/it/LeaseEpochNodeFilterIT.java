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

import cn.ypbin.admin.iot.entity.TenantLedger;
import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;

/**
 * epoch 对账接口的**按节点过滤 + 分页**真实库验收（R8-4）。
 *
 * <p>为什么必须真库：这条技术债的判据正是 SQL 本身（{@code WHERE a.access_node = ?}、
 * {@code ORDER BY tenant_id}、以及两条 {@code is_deleted = 0}）。
 * 用 mock 只能证明「调了这个方法」，证明不了「过滤真的生效、分页真的不漏不重」。</p>
 *
 * <p>覆盖：① 只返回本节点租户（别的节点的行不得出现、按 tenant_id 升序）；② 无归属的节点返回空集合；
 * ③ 已逻辑删除的**台账**行不得把 {@code config_epoch} 复活；④ 已逻辑删除的**归属**行不得出现。</p>
 *
 * <p>⚠️ 强度边界：本类是 **mapper 级**真库用例，只证明 SQL 语义；HTTP 层的参数绑定与
 * 「缺参/空白参被拒」由 {@code InternalLeaseControllerTest} 覆盖（两者互补，缺一不可）。</p>
 *
 * @author wenbin
 * @since 2026-10-09
 */
@EnabledIfMySqlAvailable
class LeaseEpochNodeFilterIT {

    /** 两个节点：A 持有 5 个租户（用于分页），B 持有 2 个（用于证明过滤）。 */
    private static final String NODE_A = "access-it-page-a";

    private static final String NODE_B = "access-it-page-b";

    /**
     * 租户区间固定在 <b>929xxx</b>：其它 IT（TenantLedgerIT / EventLogIngestIT / OutageAvailabilityIT）
     * 用的是 920001，本 IT 的 purge 会物理删除整个区间 ⇒ 必须错开，否则测试间会互相清数据
     * （复核指出：原 9200xx 区间与它们重叠，只靠「默认串行执行」侥幸不冲突）。
     */
    private static final List<Long> TENANTS_A =
        List.of(929001L, 929002L, 929003L, 929004L, 929005L);

    private static final List<Long> TENANTS_B = List.of(929011L, 929012L);

    /** 清理区间（物理删除，保证用例可重复运行；与其它 IT 的区间不重叠）。 */
    private static final long TENANT_RANGE_FROM = 929000L;

    private static final long TENANT_RANGE_TO = 929999L;

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    /** 主键发生器：与租户区间错开，避免唯一键撞车。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(9_290_000_000_001L);

    private static HikariDataSource dataSource;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static TenantNodeAssignmentMapper assignmentMapper;
    private static TenantLedgerMapper ledgerMapper;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        // 与其它 IT 共用同一实例与同一建库助手（幂等）
        ItSchema.ensure(dataSource, REPO_ROOT);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new MybatisPlusInterceptor());
        configuration.addMapper(TenantNodeAssignmentMapper.class);
        configuration.addMapper(TenantLedgerMapper.class);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        // MP 的实体元数据缓存（注解式 Mapper 不会自动初始化）
        for (Class<?> entity : List.of(TenantNodeAssignment.class, TenantLedger.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
        sqlSessionTemplate = new SqlSessionTemplate(factory);
        assignmentMapper = sqlSessionTemplate.getMapper(TenantNodeAssignmentMapper.class);
        ledgerMapper = sqlSessionTemplate.getMapper(TenantLedgerMapper.class);

        purge();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (dataSource != null) {
            purge();
            dataSource.close();
        }
    }

    @BeforeEach
    void reseed() throws SQLException {
        purge();
    }

    @Test
    @DisplayName("★ 只返回本节点的租户：别的节点的归属行一律不出现，且按 tenant_id 升序")
    void epochsMustBeFilteredByNode() throws SQLException {
        for (Long tenantId : TENANTS_A) {
            insertAssignment(NODE_A, tenantId, 1L, false);
        }
        for (Long tenantId : TENANTS_B) {
            insertAssignment(NODE_B, tenantId, 5L, false);
        }
        insertLedger(929001L, 7L, false);

        List<TenantEpochItem> items = assignmentMapper.selectEpochItemsByNode(NODE_A);

        assertThat(items).as("只应返回本节点持有（is_deleted=0）的 5 个租户，且升序")
            .extracting(TenantEpochItem::getTenantId).containsExactlyElementsOf(TENANTS_A);
        assertThat(items).extracting(TenantEpochItem::getTenantId)
            .as("别的节点（%s）的租户不得出现", NODE_B)
            .doesNotContainAnyElementsOf(TENANTS_B);
        assertThat(items.get(0).getConfigEpoch()).as("有台账行 ⇒ 取台账 config_epoch").isEqualTo(7L);
        assertThat(items.get(1).getConfigEpoch()).as("无台账行 ⇒ 0（LEFT JOIN + COALESCE，不得为 null）").isZero();
        assertThat(items.get(0).getEpoch()).as("归属 epoch 来自 assignment").isEqualTo(1L);
    }

    @Test
    @DisplayName("本节点没有任何归属时返回空集合（不得抛错、不得退化成全量）")
    void nodeWithoutAssignmentsReturnsEmpty() throws SQLException {
        insertAssignment(NODE_B, 929011L, 1L, false);

        assertThat(assignmentMapper.selectEpochItemsByNode(NODE_A))
            .as("NODE_A 没有任何归属行 ⇒ 空集合（若返回 NODE_B 的行，说明节点过滤失效）")
            .isEmpty();
    }

    @Test
    @DisplayName("★ 已逻辑删除的台账行不得复活 config_epoch（与 bump 不得复活软删行同口径）")
    void softDeletedLedgerMustNotReviveConfigEpoch() throws SQLException {
        insertAssignment(NODE_A, 929001L, 1L, false);
        insertLedger(929001L, 77L, true);

        List<TenantEpochItem> items = assignmentMapper.selectEpochItemsByNode(NODE_A);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getConfigEpoch())
            .as("台账行已软删 ⇒ 必须按「无台账」处理（0），不得把已删行的版本号复活")
            .isZero();
    }

    @Test
    @DisplayName("★ 已逻辑删除的归属行不得出现（手写 SQL 不会自动注入逻辑删除条件）")
    void softDeletedAssignmentMustNotAppear() throws SQLException {
        insertAssignment(NODE_A, 929001L, 1L, false);
        insertAssignment(NODE_A, 929002L, 1L, true);

        List<TenantEpochItem> items = assignmentMapper.selectEpochItemsByNode(NODE_A);

        assertThat(items).extracting(TenantEpochItem::getTenantId)
            .as("软删的归属行（920002）不得被对账读到").containsExactly(929001L);
    }

    /** 物理插入一条归属（不走 MyBatis-Plus，便于直接构造软删/特定 epoch 的边界数据）。 */
    private static void insertAssignment(String node, Long tenantId, long epoch, boolean deleted)
            throws SQLException {
        String sql = "INSERT INTO tenant_node_assignment "
            + "(id, tenant_id, access_node, epoch, state, lease_expire_at, status, is_deleted) "
            + "VALUES (?, ?, ?, ?, 'active', DATE_ADD(NOW(), INTERVAL 5 MINUTE), 1, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ID_SEQ.incrementAndGet());
            statement.setLong(2, tenantId);
            statement.setString(3, node);
            statement.setLong(4, epoch);
            statement.setInt(5, deleted ? 1 : 0);
            statement.executeUpdate();
        }
    }

    /** 物理插入一条台账（config_epoch 可指定，用于验证读取口径）。 */
    private static void insertLedger(Long tenantId, long configEpoch, boolean deleted) throws SQLException {
        String sql = "INSERT INTO tenant_ledger "
            + "(id, tenant_id, assignable, config_epoch, status, is_deleted) VALUES (?, ?, 1, ?, 1, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ID_SEQ.incrementAndGet());
            statement.setLong(2, tenantId);
            statement.setLong(3, configEpoch);
            statement.setInt(4, deleted ? 1 : 0);
            statement.executeUpdate();
        }
    }

    /**
     * 物理清空本 IT 的数据（逻辑删除会撞唯一键 {@code uk_tenant_ledger}，故必须物理删）。
     *
     * <p>按**节点名**与**租户区间**双重清理：容器是复用的（{@code ContainerSupport} 用 reuse），
     * 只按区间清理时，历史版本用过的区间会留下同一节点名的行 ⇒ 用例变成顺序依赖
     * （本 IT 初版就因此在「无归属节点应返回空集」上假红过）。</p>
     */
    private static void purge() throws SQLException {
        execute("DELETE FROM tenant_node_assignment WHERE access_node IN ('" + NODE_A + "', '" + NODE_B + "')"
            + " OR tenant_id BETWEEN " + TENANT_RANGE_FROM + " AND " + TENANT_RANGE_TO);
        execute("DELETE FROM tenant_ledger WHERE tenant_id BETWEEN "
            + TENANT_RANGE_FROM + " AND " + TENANT_RANGE_TO);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
