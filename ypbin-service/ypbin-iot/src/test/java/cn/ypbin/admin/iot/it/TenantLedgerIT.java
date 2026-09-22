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
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 * M0b-3 的**真库**用例：台账写入口与配置版本号语义。
 *
 * <p><b>为什么必须真库</b>：版本号递增与「复活逻辑删除行」都写在 SQL（注解 UPDATE）里——用 mock 验证不了，
 * 只能对真 MySQL 断言。三件事必须成立：</p>
 * <ol>
 *   <li>新建台账行：{@code config_epoch = 1}；</li>
 *   <li>每次变更：{@code config_epoch} **必须 +1**（接入侧靠它判断「配置有没有变」，不递增 ⇒ 对账漏拉）；</li>
 *   <li>**软删后重设必须复活同一行**：{@code uk_tenant_ledger(tenant_id)} 不含量删除标记，
 *       盲目 insert 会撞唯一键（M-1 的 {@code iot_service} 踩过同类问题）。</li>
 * </ol>
 *
 * <p>运行方式同其它 IT：{@code -Pit} 触发、{@code @EnabledIfMySqlAvailable} 门控。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@EnabledIfMySqlAvailable
class TenantLedgerIT {

    private static final Long TENANT = 920001L;

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static TenantLedgerMapper ledgerMapper;
    private static TransactionTemplate transactionTemplate;
    private static TenantLedgerService ledgerService;

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
        configuration.addInterceptor(new MybatisPlusInterceptor());
        configuration.addMapper(TenantLedgerMapper.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            TenantLedger.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        ledgerMapper = new SqlSessionTemplate(factory).getMapper(TenantLedgerMapper.class);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ledgerService = new TenantLedgerService(ledgerMapper);

        physicalDelete();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            physicalDelete();
            dataSource.close();
        }
    }

    @Test
    @DisplayName("★ 新建=1；每次变更必须 +1；软删后重设必须复活同一行（不得撞唯一键）")
    void configEpochMustIncreaseOnEveryChangeAndReviveAfterSoftDelete() {
        physicalDelete();
        assertThat(ledgerService.setAssignable(TENANT, true))
            .isEqualTo(TenantLedgerService.LedgerChange.CREATED);
        assertThat(configEpoch()).as("新建台账行的版本号起点").isEqualTo(1L);

        assertThat(ledgerService.setAssignable(TENANT, false))
            .isEqualTo(TenantLedgerService.LedgerChange.UPDATED);
        assertThat(configEpoch()).as("每次变更必须 +1（否则接入侧对账漏拉）").isEqualTo(2L);
        assertThat(ledgerMapper.selectById(rowId()).getAssignable()).isFalse();

        // 逻辑删除该行（模拟运维/数据清理）后重新置为可分配
        softDelete();
        assertThat(ledgerService.setAssignable(TENANT, true))
            .as("软删过的租户必须走「复活」而不是新建")
            .isEqualTo(TenantLedgerService.LedgerChange.REVIVED);
        assertThat(configEpoch()).as("复活也是一次变更，版本号必须继续 +1").isEqualTo(3L);
        assertThat(ledgerMapper.selectById(rowId()).getAssignable()).isTrue();
        assertThat(rawRowCount()).as("全程只允许一行台账（唯一键 + 复活语义）").isEqualTo(1);
    }

    @Test
    @DisplayName("可分配来源：只有 assignable=true 的租户会出现在分配来源里")
    void listAssignableOnlyReturnsEnabled() {
        physicalDelete();
        ledgerService.setAssignable(TENANT, true);

        List<Long> assignable = ledgerService.listAssignableTenantIds();

        assertThat(assignable).contains(TENANT);
        ledgerService.setAssignable(TENANT, false);
        assertThat(ledgerService.listAssignableTenantIds()).doesNotContain(TENANT);
    }

    @Test
    @DisplayName("★ 采集配置变更推进版本号：有台账行才 +1（真库验证）；无台账行是 no-op 且**不得**插入")
    void bumpConfigEpochMustSkipWhenNoLedgerRow() {
        physicalDelete();
        assertThat(ledgerService.bumpConfigEpoch(TENANT))
            .as("台账无该租户 ⇒ 未发信号（不得顺手 insert，那会静默改变可分配来源）").isFalse();
        assertThat(rawRowCount()).as("no-op 不得留下任何行").isZero();

        ledgerService.setAssignable(TENANT, true);
        assertThat(configEpoch()).isEqualTo(1L);
        assertThat(ledgerService.bumpConfigEpoch(TENANT)).isTrue();
        assertThat(configEpoch()).as("每次采集配置变更必须 +1").isEqualTo(2L);
        assertThat(ledgerService.bumpConfigEpoch(TENANT)).isTrue();
        assertThat(configEpoch()).isEqualTo(3L);
    }

    @Test
    @DisplayName("★ 逻辑删除的台账行不得被 bump 复活（否则软删失效 + 版本号漂移）")
    void bumpConfigEpochMustNotReviveSoftDeletedRow() {
        physicalDelete();
        ledgerService.setAssignable(TENANT, true);
        softDelete();

        assertThat(ledgerService.bumpConfigEpoch(TENANT))
            .as("软删行不算「有台账」，不得复活").isFalse();
        assertThat(rawRowCount()).isEqualTo(1);
        assertThat(ledgerMapper.selectIncludingDeleted(TENANT).getIsDeleted())
            .as("bump 不得把 is_deleted 改回 0").isNotZero();
    }

    @Test
    @DisplayName("运维写入口：返回的是**回读**的落库状态（含变更类型与最新版本号）")
    void setAssignableAndGetMustReturnPersistedRow() {
        physicalDelete();
        var created = ledgerService.setAssignableAndGet(TENANT, true);
        assertThat(created.getChange()).isEqualTo("created");
        assertThat(created.getConfigEpoch()).isEqualTo(1L);
        assertThat(created.getAssignable()).isTrue();

        var updated = ledgerService.setAssignableAndGet(TENANT, false);
        assertThat(updated.getChange()).isEqualTo("updated");
        assertThat(updated.getConfigEpoch()).isEqualTo(2L);
        assertThat(updated.getAssignable()).isFalse();

        assertThat(ledgerService.listAll()).extracting("tenantId").contains(TENANT);
    }

    private static Long rowId() {
        return ledgerMapper.selectOne(Wrappers.<TenantLedger>lambdaQuery()
            .eq(TenantLedger::getTenantId, TENANT)).getId();
    }

    private static Long configEpoch() {
        return ledgerMapper.selectOne(Wrappers.<TenantLedger>lambdaQuery()
            .eq(TenantLedger::getTenantId, TENANT)).getConfigEpoch();
    }

    private static void softDelete() {
        execute("UPDATE tenant_ledger SET is_deleted = 1 WHERE tenant_id = " + TENANT);
    }

    private static void physicalDelete() {
        execute("DELETE FROM tenant_ledger WHERE tenant_id = " + TENANT);
    }

    private static long rawRowCount() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM tenant_ledger WHERE tenant_id = " + TENANT);
                var rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException ex) {
            throw new IllegalStateException("统计台账行失败", ex);
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
