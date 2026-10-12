/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 连接工厂的行为：凭据单点构造 + 可达性探测的失败语义（真库往返由容器 IT 覆盖）。
 *
 * <p>本机没有 IoTDB 实例，故这里拦 {@link DriverManager} 静态方法，断言「交给了什么 url/凭据、
 * 成功是否关闭、失败是否收敛为 false」；「池化」未做（理由见工厂类注释），因此没有池上限/等待超时
 * 这类只能在真库验证的断言。</p>
 *
 * @author wenbin
 * @since 2026-10-12
 */
class IotDbConnectionFactoryTest {

    /**
     * 在任何 {@code mockStatic(DriverManager.class)} 之前把驱动**真实注册**进 JVM 全局注册表。
     *
     * <p>否则首个「在 mock 下构造写入器/存储/连接工厂」的用例会让驱动类在
     * {@code DriverManager.registerDriver} 被拦成 no-op 的上下文里完成首次加载；此后
     * {@code Class.forName} 对已加载类是 no-op，驱动在本 JVM 内再也注册不上，后跑的
     * {@code IotDbDriverRegistrarTest} 会报 {@code No such driver}（CI 冷状态实测到的形态）。
     * 这里先注册好，测试顺序就不再影响 JVM 全局注册表。</p>
     */
    @BeforeAll
    static void registerIotDbDriverBeforeStaticMocking() {
        IotDbDriverRegistrar.ensureRegistered();
    }

    private static final String URL = "jdbc:iotdb://127.0.0.1:6667/ypbin_it?sql_dialect=table";

    private static TimeSeriesProperties properties() {
        TimeSeriesProperties properties = new TimeSeriesProperties();
        properties.setUrl(URL);
        properties.setUsername("it_user");
        properties.setPassword("it_pass");
        return properties;
    }

    @Test
    @DisplayName("open：url 与单点构造的凭据交给 DriverManager（user/password 逐字一致）")
    void openMustPassUrlAndCredentials() throws SQLException {
        Connection connection = mock(Connection.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);

            assertThat(new IotDbConnectionFactory(properties()).open()).isSameAs(connection);

            ArgumentCaptor<Properties> credentials = ArgumentCaptor.forClass(Properties.class);
            driverManager.verify(() -> DriverManager.getConnection(eq(URL), credentials.capture()));
            assertThat(credentials.getValue())
                .containsEntry("user", "it_user")
                .containsEntry("password", "it_pass");
        }
    }

    @Test
    @DisplayName("★ probe：能建立连接即 true，并关闭连接（不泄漏连接）")
    void probeMustReturnTrueAndCloseConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenReturn(connection);

            assertThat(new IotDbConnectionFactory(properties()).probe()).isTrue();
            verify(connection).close();
        }
    }

    @Test
    @DisplayName("★ probe：SQLException 不抛、返回 false（健康检查要的是布尔事实，不是异常）")
    void probeMustSwallowSqlException() throws SQLException {
        // ⚠️ 异常必须先建好：SQLException 的构造函数会碰 DriverManager，而它正被静态 mock
        //    （在 when(...) 的 stubbing 过程中 new 会触发 UnfinishedStubbingException）。
        SQLException failure = new SQLException("connection refused");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(failure);

            assertThatCode(() -> assertThat(new IotDbConnectionFactory(properties()).probe()).isFalse())
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("probe：驱动抛 RuntimeException（非法 URL 等）同样收敛为 false，不逃逸")
    void probeMustSwallowRuntimeException() throws SQLException {
        IllegalStateException failure = new IllegalStateException("bad url");
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                .thenThrow(failure);

            assertThat(new IotDbConnectionFactory(properties()).probe()).isFalse();
        }
    }
}
