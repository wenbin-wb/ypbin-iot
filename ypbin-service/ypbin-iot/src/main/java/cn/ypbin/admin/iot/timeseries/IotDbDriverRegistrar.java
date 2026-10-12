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

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB JDBC 驱动的**显式注册**（可重复调用）。
 *
 * <p><b>为什么必须显式加载（CI 实测根因）</b>：{@code DriverManager} 只靠 ServiceLoader 自动发现声明了
 * {@code META-INF/services/java.sql.Driver} 的驱动，而 {@code org.apache.iotdb:iotdb-jdbc} 的 jar
 * <b>没有</b>该文件——{@code 2.0.1-beta}（sha1 {@code 766549f9…}，与 Maven Central 制品逐字节一致）与
 * {@code 2.0.11} 两个版本的制品均实测确认：jar 内只有 OSGi 描述符 {@code OSGI-INF/…}（那是给 OSGi
 * 容器用的，{@code DriverManager} 看不到），没有 {@code META-INF/services} 目录。
 * 故不显式加载时 {@code DriverManager.getConnection("jdbc:iotdb://…")} 恒抛
 * {@code SQLException: No suitable driver found for jdbc:iotdb://…}；官方 JDBC 示例同样是先
 * {@code Class.forName("org.apache.iotdb.jdbc.IoTDBDriver")} 再取连接（其「方法总览」把 Class.forName
 * 列为取连接的前置步骤）。</p>
 *
 * <p><b>为什么是类名常量而不是 import</b>：驱动在本模块是 {@code runtime} 依赖（见 pom 注释：平台代码只用
 * {@code java.sql.*}，驱动版本由部署决定），编译期看不到该类，只能按类名加载。加载失败**不静默降级**：
 * 抛 {@link IllegalStateException}，与 {@code IotTimeSeriesConfiguration} 的「启用即 fail-fast」一致。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public final class IotDbDriverRegistrar {

    /** IoTDB JDBC 驱动类（官方 JDBC 文档给出；该驱动 jar 不含 ServiceLoader 描述符，故须显式加载）。 */
    private static final String IOTDB_DRIVER_CLASS = "org.apache.iotdb.jdbc.IoTDBDriver";

    private static final Logger log = LoggerFactory.getLogger(IotDbDriverRegistrar.class);

    private IotDbDriverRegistrar() {
    }

    /**
     * 确保驱动类已加载**且已注册**进 {@code DriverManager}（可重复调用，幂等）。
     *
     * <p><b>为什么不能只 {@code Class.forName}</b>：{@code Class.forName} 只在**首次**加载类时触发静态块，
     * 而静态块里的 {@code DriverManager.registerDriver(...)} 可能没有真正生效——两种已发生的路径：
     * ① 驱动自身在静态块里 catch 了 {@code SQLException} 只 {@code log.error}（注册失败不抛）；
     * ② 类的首次加载发生在 {@code DriverManager} 被静态 mock 的测试上下文里，{@code registerDriver}
     * 被拦成 no-op。此后 {@code Class.forName} 对「已加载」的类是 no-op ⇒ 驱动**永远不会**被注册，
     * 首次建连报 {@code SQLException: No suitable driver}（CI 冷状态实测到的形态）。</p>
     *
     * <p>因此这里在加载后**校验并补注册**：已注册则直接返回（幂等，不产生重复驱动），未注册则显式
     * 实例化驱动并 {@code registerDriver}；补注册失败时 <b>fail-fast</b> 抛带明确原因的
     * {@link IllegalStateException}（不静默降级成「等第一次建连再报错」）。</p>
     *
     * @throws IllegalStateException 驱动不在运行时类路径，或已加载但无法注册时抛出（不静默继续）
     */
    public static void ensureRegistered() {
        Class<?> driverClass;
        try {
            driverClass = Class.forName(IOTDB_DRIVER_CLASS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("IoTDB JDBC 驱动不在运行时类路径（需要 org.apache.iotdb:iotdb-jdbc，"
                + "runtime 依赖）：" + IOTDB_DRIVER_CLASS, ex);
        }
        if (isRegistered(driverClass)) {
            log.debug("[iot] IoTDB JDBC 驱动已注册：{}", IOTDB_DRIVER_CLASS);
            return;
        }
        try {
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(driver);
            log.info("[iot] IoTDB JDBC 驱动此前未注册，已显式补注册：{}", IOTDB_DRIVER_CLASS);
        } catch (ReflectiveOperationException | SQLException ex) {
            throw new IllegalStateException("IoTDB JDBC 驱动已加载但注册失败（首次建连会失败，故 fail-fast）："
                + IOTDB_DRIVER_CLASS, ex);
        }
    }

    /**
     * 驱动类是否已注册进 {@code DriverManager}（按类对象比对，不按 URL 前缀）。
     *
     * <p>静态 mock 上下文下 {@code getDrivers()} 可能返回 {@code null} 或抛运行时异常（Mockito 对
     * {@code Enumeration} 无默认空值）：这里按「未注册」处理并继续走补注册路径（mock 下
     * {@code registerDriver} 本身是 no-op），从而不在测试里引入 NPE/异常，也不会把 mock 状态当成真实事实。</p>
     *
     * @param driverClass 驱动类
     * @return 已注册返回 true
     */
    private static boolean isRegistered(Class<?> driverClass) {
        Enumeration<Driver> drivers;
        try {
            drivers = DriverManager.getDrivers();
        } catch (RuntimeException ex) {
            log.debug("[iot] 无法枚举已注册 JDBC 驱动（DriverManager 可能被 mock）：{}", ex.toString());
            return false;
        }
        if (drivers == null) {
            return false;
        }
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver != null && driver.getClass() == driverClass) {
                return true;
            }
        }
        return false;
    }
}
