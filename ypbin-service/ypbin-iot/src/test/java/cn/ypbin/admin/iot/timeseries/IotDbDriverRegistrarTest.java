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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 驱动注册的回归护栏：缺了它，「驱动没注册」只会在真库才暴露（CI 的容器 IT 首次就是这么红的）。
 *
 * <p>它能在**单元测试**里跑，是因为驱动是本模块声明的 {@code runtime} 依赖——runtime scope 属于测试类路径，
 * 故「驱动在类路径」这件事在单测环境同样可断言（不需要 IoTDB 实例：{@code DriverManager.getDriver} 只做
 * URL 前缀匹配）。</p>
 *
 * <p>⚠️ {@code DriverManager} 的注册表是**JVM 全局**的，所以「构造器里也注册」这件事在本进程里**测不出来**
 * （前一条用例已经把驱动注册好了，断言会恒真——本仓踩过恒真断言的坑）。因此那条用
 * {@link #constructorsMustRegisterDriverInFreshJvm()} 起一个**子 JVM** 验证：全新 JVM 里没有任何东西
 * 注册过驱动，只有构造器这一条路径。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class IotDbDriverRegistrarTest {

    /** 表模型连接串（库名在 URL 里；驱动按前缀匹配，不需要真库）。 */
    private static final String IOTDB_URL = "jdbc:iotdb://127.0.0.1:6667/iot?sql_dialect=table";

    /** 子 JVM 输出里的成功标记（带驱动类名，便于确认是哪个驱动被注册）。 */
    private static final String PROBE_OK = "PROBE-DRIVER=org.apache.iotdb.jdbc.IoTDBDriver";

    @Test
    @DisplayName("★ 显式加载后 DriverManager 能按 jdbc:iotdb:// 找到驱动（该 jar 无 ServiceLoader 描述符，这一步不能省）")
    void mustRegisterDriverForIotDbUrl() throws Exception {
        IotDbDriverRegistrar.ensureRegistered();

        Driver driver = DriverManager.getDriver(IOTDB_URL);
        assertThat(driver).as("未找到 IoTDB 驱动：说明显式加载没生效或驱动不在类路径").isNotNull();
        assertThat(driver.acceptsURL(IOTDB_URL)).isTrue();
    }

    @Test
    @DisplayName("可重复调用：装配与 IT 都会调，重复加载不得抛异常")
    void mustBeIdempotent() {
        assertThatCode(() -> {
            IotDbDriverRegistrar.ensureRegistered();
            IotDbDriverRegistrar.ensureRegistered();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 全新 JVM 里只靠构造写入器/查询存储就能注册驱动（本进程全局注册表骗不了它，故必须 fork 子 JVM）")
    void constructorsMustRegisterDriverInFreshJvm() throws Exception {
        String javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"),
                ConstructorProbe.class.getName()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as("子 JVM 未正常退出，输出：%n%s", output).isZero();
        assertThat(output).as("子 JVM 里构造后仍找不到驱动 ⇒ 构造器没注册（或驱动不在类路径）").contains(PROBE_OK);
    }

    /**
     * 子 JVM 探针：**不调用** {@code ensureRegistered()}，只构造写入器与查询存储，看驱动是否已注册。
     *
     * <p>之所以必须是独立进程：{@code DriverManager} 的注册表是 JVM 全局的，同进程内前一条用例已经把驱动
     * 注册好了，「构造后能不能找到驱动」在本进程里恒为真，测不出构造器有没有做这件事。</p>
     */
    public static final class ConstructorProbe {

        private ConstructorProbe() {
        }

        public static void main(String[] args) throws SQLException {
            TimeSeriesProperties properties = new TimeSeriesProperties();
            properties.setEnabled(true);
            properties.setUrl(IOTDB_URL);
            new IotDbTimeSeriesWriter(properties, new SimpleMeterRegistry());
            new IotDbTimeSeriesStore(properties);
            System.out.println(PROBE_OK + "（" + DriverManager.getDriver(IOTDB_URL).getClass().getName() + "）");
        }
    }
}
