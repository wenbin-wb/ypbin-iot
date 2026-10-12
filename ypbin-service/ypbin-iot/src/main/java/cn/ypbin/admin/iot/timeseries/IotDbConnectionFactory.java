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

import cn.ypbin.starter.core.util.LogSanitizer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB JDBC 连接的**单点工厂**：驱动注册、凭据 {@link Properties} 构造、取连接与可达性探测都只在这里。
 *
 * <p><b>为什么要有它</b>：写入器（{@code IotDbTimeSeriesWriter}）、查询器（{@code IotDbTimeSeriesStore}）
 * 与真值探针（{@code IotDbRowCountProbe}）此前各自重复「构造凭据 Properties + {@code DriverManager
 * .getConnection}」，任何一处改动（换凭据键、加连接参数、换驱动加载方式）都要改三处。抽成一个类后，
 * 「怎么连库」只有一个事实源，且它同时是 {@link #probe()} 这个**真实可达性探测**的落点。</p>
 *
 * <p><b>为什么没有连接池（如实声明，2026-10-12）</b>：本机与 CI 都没有可用的 IoTDB 实例（真库往返由
 * {@code @EnabledIfIotDbAvailable} 的容器 IT 承担）。连接池的正确性依赖驱动的真实行为
 * （{@code Connection.isValid}/{@code isClosed}/{@code close} 语义、断线后连接能否复用、事务隔离等），
 * 这些**无法在没有真库的前提下验证**；交一个「单测全绿、真机可能崩」的池比每批新建连接更危险
 * （后者只是慢，前者会丢数据/挂死）。因此本轮**只做工厂抽取 + 真实探测**，池化登记为后续项：
 * 建议在容器 IT 能覆盖「断线重连 / 池耗尽的等待与超时 / 连接失效回收」之后再引入 HikariCP 或
 * IoTDB 的 SessionPool，并配池上限与等待超时两个配置项。</p>
 *
 * <p><b>连接建立已含 TCP + 认证</b>：{@link #probe()} 只做「能否建立连接」，不执行 SQL——IoTDB 表模型
 * 的探活语句（如 {@code SHOW DATABASES}/{@code SELECT 1}）在未验证的驱动版本上未必可用，而
 * {@code DriverManager.getConnection} 成功本身就证明网络可达且凭据被接受。它是**连接级**探测，
 * 不声称 SQL 级活性。</p>
 *
 * @author wenbin
 * @since 2026-10-12
 */
public class IotDbConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(IotDbConnectionFactory.class);

    private final TimeSeriesProperties properties;

    /** 凭据只构造一次（不可变使用；{@code Properties} 不共享给外部，避免被意外改写）。 */
    private final Properties credentials;

    /**
     * 构造工厂（同时确保 IoTDB JDBC 驱动已注册；幂等、无副作用）。
     *
     * @param properties 时序库配置（url/username/password）
     */
    public IotDbConnectionFactory(TimeSeriesProperties properties) {
        // 驱动 jar 不含 ServiceLoader 描述符，必须显式加载（见 IotDbDriverRegistrar 的一手依据）
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        this.credentials = credentials;
    }

    /**
     * 取一条 IoTDB JDBC 连接（**调用方负责关闭**：写入/查询/探针都用 try-with-resources）。
     *
     * @return 新连接
     * @throws SQLException 连接建立失败（网络不通 / 认证失败 / 驱动异常），由调用方按各自失败语义处理
     */
    public Connection open() throws SQLException {
        return DriverManager.getConnection(properties.getUrl(), credentials);
    }

    /**
     * 真实可达性探测：能建立一条连接即视为可达（TCP + 认证），随后立即关闭。
     *
     * <p><b>失败不抛</b>：探测的调用方（健康指示器 / 运维查询）要的是「可达与否」这一布尔事实，
     * 不是异常；失败在此记 WARN（含完整堆栈，不静默），返回 {@code false}。调用方应做结果缓存，
     * 避免把健康检查变成对 IoTDB 的持续压测。</p>
     *
     * @return 可建立连接返回 {@code true}；任何 SQL/运行时异常返回 {@code false}
     */
    public boolean probe() {
        try (Connection connection = open()) {
            return connection != null;
        } catch (SQLException | RuntimeException ex) {
            log.warn("[iot] 时序库可达性探测失败（连接未能建立；url={}）：",
                LogSanitizer.sanitize(properties.getUrl()), ex);
            return false;
        }
    }
}
