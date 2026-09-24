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
     * 加载驱动类（其静态初始化会把自己注册进 {@code DriverManager}）；已加载过再调用无副作用。
     *
     * @throws IllegalStateException 驱动不在运行时类路径时抛出（不静默继续）
     */
    public static void ensureRegistered() {
        try {
            Class.forName(IOTDB_DRIVER_CLASS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("IoTDB JDBC 驱动不在运行时类路径（需要 org.apache.iotdb:iotdb-jdbc，"
                + "runtime 依赖）：" + IOTDB_DRIVER_CLASS, ex);
        }
        log.debug("[iot] 已显式加载 IoTDB JDBC 驱动：{}", IOTDB_DRIVER_CLASS);
    }
}
