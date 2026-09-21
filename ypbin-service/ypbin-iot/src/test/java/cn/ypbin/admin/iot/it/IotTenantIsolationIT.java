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

import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.test.condition.EnabledIfMySqlAvailable;
import cn.ypbin.starter.test.container.MySqlIntegrationTestSupport;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * M-1 多租户隔离的**真库集成测试**（§10 越权用例的服务侧落地，断言口径见决策 **D0.5**）。
 *
 * <p><b>与单元测试的区别与它补的洞</b>：`IotTenantIsolationGateTest` 只验证「租户插件的决策函数」与配置，
 * 不碰真库；本测试在**真实 MySQL** 上跑**真实 DDL**（`deploy/sql/006-iot-schema.sql` 全量结构）
 * 与**真实 MyBatis-Plus 租户插件**，验证 A 租户对 B 租户数据读不到、改不动、删不掉。</p>
 *
 * <p><b>为什么用原生 JDBC 做地面真值</b>：若「B 的行是否存在」也通过租户插件去查，就会变成用同一个过滤器
 * 证明过滤器有效（自证循环）。因此所有「对方数据依然存在/未被改动」的断言都走原生 JDBC 直连，绕过插件；
 * 并用 {@link TenantContext#executeIgnore} 显式证明「数据确实在库里，只是被租户条件挡住」——这让本测试
 * <b>非空跑</b>：若隔离失效，第 2/3 条断言会立刻失败。</p>
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
class IotTenantIsolationIT {

    /** 测试用租户 A（避开真实数据：取一个明显不可能是生产租户的高位值）。 */
    private static final Long TENANT_A = 900001L;

    /** 测试用租户 B。 */
    private static final Long TENANT_B = 900002L;

    /** 平台表：不继承租户基类，必须登记进 ignore-tables（与生产配置一致）。 */
    private static final String PLATFORM_TABLE = "tenant_node_assignment";

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws SQLException {
        Map<String, String> properties = MySqlIntegrationTestSupport.springProperties();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.get("spring.datasource.url"));
        config.setUsername(properties.get("spring.datasource.username"));
        config.setPassword(properties.get("spring.datasource.password"));
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        // 幂等建表：同一 MySQL 会被多个 IT 共用（谁先跑谁建），避免「表已存在」导致初始化失败
        ItSchema.ensure(dataSource, REPO_ROOT);
        sqlSessionFactory = buildSqlSessionFactory();
        purgeTestTenants();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (dataSource != null) {
            purgeTestTenants();
            dataSource.close();
        }
    }

    @Test
    @DisplayName("插入即自动打上当前租户：显式不传 tenant_id，落库后必须是当前租户")
    void insertMustStampCurrentTenant() {
        Long id = insertProduct(TENANT_A, "it-stamp-" + TENANT_A, "A 的水表");

        assertThat(rawTenantIdOf(id))
            .as("租户插件必须在 INSERT 时补上 tenant_id")
            .isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("跨租户列表：A 只看得见自己的数据；而数据确实在库里（executeIgnore 可见）⇒ 非空跑")
    void crossTenantListMustNotLeak() {
        Long idA = insertProductRaw(TENANT_A, "it-list-a-" + TENANT_A, "A 的产品");
        Long idB = insertProductRaw(TENANT_B, "it-list-b-" + TENANT_B, "B 的产品");

        List<IotProduct> visibleToA = listProductsAs(TENANT_A);

        assertThat(visibleToA).extracting(IotProduct::getId).contains(idA).doesNotContain(idB);
        // 更强的通用不变量：A 能看到的每一行都必须属于 A（不受其它用例已插入数据的影响）
        assertThat(visibleToA).extracting(IotProduct::getTenantId).containsOnly(TENANT_A);

        // 反证：绕过租户条件后 B 的行确实存在 ⇒ 上面「看不见」是租户过滤的结果，而非数据不存在
        List<IotProduct> ignored = TenantContext.executeIgnore(IotTenantIsolationIT::listProducts);
        assertThat(ignored).extracting(IotProduct::getId).contains(idA, idB);
    }

    @Test
    @DisplayName("跨租户按 id 查详情：查不到（对应 D0.5 的「200 + 409 业务错误『不存在』」），且不泄露存在性")
    void crossTenantSelectByIdMustReturnNothing() {
        Long idB = insertProductRaw(TENANT_B, "it-detail-b-" + TENANT_B, "B 的产品");

        IotProduct seenByA = selectProductAs(TENANT_A, idB);
        IotProduct seenByOwner = selectProductAs(TENANT_B, idB);

        assertThat(seenByA).as("A 租户不能读到 B 租户的产品").isNull();
        assertThat(seenByOwner).as("B 租户自己能读到（证明该行有效）").isNotNull();
        assertThat(seenByOwner.getProductCode()).isEqualTo("it-detail-b-" + TENANT_B);
    }

    @Test
    @DisplayName("业务唯一键按租户隔离：不同租户可用同一 product_code（uk 带 tenant_id）")
    void sameBusinessKeyAllowedAcrossTenants() {
        String sharedCode = "it-shared-code";

        Long idA = insertProductRaw(TENANT_A, sharedCode, "A 的共享编码产品");
        Long idB = insertProductRaw(TENANT_B, sharedCode, "B 的共享编码产品");

        assertThat(idA).isNotEqualTo(idB);
        assertThat(rawTenantIdOf(idA)).isEqualTo(TENANT_A);
        assertThat(rawTenantIdOf(idB)).isEqualTo(TENANT_B);
        assertThat(listProductsAs(TENANT_A)).extracting(IotProduct::getId).contains(idA).doesNotContain(idB);
    }

    @Test
    @DisplayName("跨租户删除无效果：A 删不掉 B 的行，且 B 的行未被逻辑删除")
    void crossTenantDeleteMustNotAffectOtherTenant() throws SQLException {
        Long idB = insertProductRaw(TENANT_B, "it-del-b-" + TENANT_B, "B 的产品");

        int affected = deleteProductAs(TENANT_A, idB);

        assertThat(affected).as("删除影响行数必须为 0").isZero();
        assertThat(rawIsDeletedOf(idB)).as("B 的行必须仍然存在且未标记删除").isZero();
    }

    @Test
    @DisplayName("跨租户更新无效果：A 改不动 B 的行（按 id 更新影响 0 行，原始数据不变）")
    void crossTenantUpdateMustNotAffectOtherTenant() throws SQLException {
        Long idB = insertProductRaw(TENANT_B, "it-upd-b-" + TENANT_B, "B 的产品原名");

        int affected = updateProductNameAs(TENANT_A, idB, "被 A 篡改的名字");

        assertThat(affected).as("更新影响行数必须为 0").isZero();
        assertThat(rawProductNameOf(idB)).as("B 的名称不得被改动").isEqualTo("B 的产品原名");
    }

    // ---------- 装配 ----------

    private static SqlSessionFactory buildSqlSessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        configuration.addInterceptor(mybatisPlusInterceptor());
        configuration.addMapper(IotProductMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /** 与生产同构的租户插件：处理器读 {@code TenantContext}，ignore-tables 与 nacos 配置一致。 */
    private static MybatisPlusInterceptor mybatisPlusInterceptor() {
        TenantProperties properties = new TenantProperties();
        properties.setIgnoreTables(List.of(PLATFORM_TABLE));
        properties.setFailOnMissingTenant(true);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
            new TenantLineInnerInterceptor(new DefaultTenantLineHandler(Optional::empty, properties)));
        return interceptor;
    }

    /**
     * 原生 JDBC 插入（显式带 tenant_id，绕过租户插件）。
     *
     * <p>读/写隔离用例刻意用它与插件**解耦**：否则「摘掉插件」时插入会因 tenant_id 为
     * NOT NULL 而先报错，测试转红的原因就说不清是「隔离失效」还是「插入失败」。用原生插入后，
     * 摘掉插件会让隔离用例以<b>断言失败</b>（读到/改到对方数据）的形式暴露。</p>
     */
    private static Long insertProductRaw(Long tenantId, String productCode, String productName) {
        Long id = System.nanoTime() & 0x7fffffffffffffffL;
        String sql = "insert into iot_product (id, tenant_id, product_code, product_name, protocol,"
            + " data_format, model_status, status, is_deleted) values (?, ?, ?, ?, 'mqtt', 'json',"
            + " 'draft', 1, 0)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, tenantId);
            statement.setString(3, productCode);
            statement.setString(4, productName);
            statement.executeUpdate();
            return id;
        } catch (SQLException ex) {
            throw new IllegalStateException("原生插入失败", ex);
        }
    }

    // ---------- 走租户插件的读写（被测路径） ----------

    private static Long insertProduct(Long tenantId, String productCode, String productName) {
        return TenantContext.executeWithTenant(tenantId, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                IotProduct product = new IotProduct();
                product.setProductCode(productCode);
                product.setProductName(productName);
                product.setProtocol("mqtt");
                product.setDataFormat("json");
                product.setModelStatus("draft");
                session.getMapper(IotProductMapper.class).insert(product);
                return product.getId();
            }
        });
    }

    private static List<IotProduct> listProductsAs(Long tenantId) {
        return TenantContext.executeWithTenant(tenantId, IotTenantIsolationIT::listProducts);
    }

    private static List<IotProduct> listProducts() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(IotProductMapper.class).selectList(null);
        }
    }

    private static IotProduct selectProductAs(Long tenantId, Long id) {
        return TenantContext.executeWithTenant(tenantId, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                return session.getMapper(IotProductMapper.class).selectById(id);
            }
        });
    }

    private static int deleteProductAs(Long tenantId, Long id) {
        return TenantContext.executeWithTenant(tenantId, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                return session.getMapper(IotProductMapper.class).deleteById(id);
            }
        });
    }

    private static int updateProductNameAs(Long tenantId, Long id, String newName) {
        return TenantContext.executeWithTenant(tenantId, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                IotProduct patch = new IotProduct();
                patch.setId(id);
                patch.setProductName(newName);
                return session.getMapper(IotProductMapper.class).updateById(patch);
            }
        });
    }

    // ---------- 原生 JDBC 地面真值（绕过租户插件） ----------

    private static Long rawTenantIdOf(Long id) {
        return queryLong("select tenant_id from iot_product where id = ?", id);
    }

    private static Integer rawIsDeletedOf(Long id) {
        Long value = queryLong("select is_deleted from iot_product where id = ?", id);
        return value == null ? null : value.intValue();
    }

    private static String rawProductNameOf(Long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "select product_name from iot_product where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static Long queryLong(String sql, Long parameter) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("原生 JDBC 查询失败：" + sql, ex);
        }
    }

    /** 清理本测试两个租户的数据（原生 JDBC，不受租户插件影响）。 */
    private static void purgeTestTenants() throws SQLException {
        String[] tables = {"iot_product_version", "iot_product"};
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "delete from " + table + " where tenant_id in (?, ?)")) {
                    statement.setLong(1, TENANT_A);
                    statement.setLong(2, TENANT_B);
                    statement.executeUpdate();
                }
            }
        }
    }
}
