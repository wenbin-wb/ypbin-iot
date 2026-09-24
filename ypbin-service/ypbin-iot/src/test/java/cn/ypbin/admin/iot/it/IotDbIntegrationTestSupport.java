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

import cn.ypbin.starter.test.container.ContainerSupport;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * IoTDB 集成测试支撑：**外部实例优先、容器回退、都没有则跳过（打印原因）**。
 *
 * <p>与 MySQL 侧的 {@code ContainerSupport} 同一取向，但 IoTDB 的支撑类不在 {@code ypbin-starter-test}
 * （那是 starter 的公共测试库，本仓不该为一个业务域往上游加依赖），故在本模块内自带一份最小实现；
 * Docker 可用性判断复用 starter 的 {@link ContainerSupport#dockerAvailable()}，不重复造探测。</p>
 *
 * <h2>镜像与版本（一手来源，访问 2026-09-24）</h2>
 * <ul>
 *   <li>命名规约 {@code apache/iotdb:<version>-standalone} 来自官方仓库
 *       <a href="https://raw.githubusercontent.com/apache/iotdb/master/docker/ReadMe.md">docker/ReadMe.md</a>
 *       （「From 2.0.5, we maintain 4 images: datanode, confignode, standalone and ainode」）与官方
 *       <a href="https://iotdb.incubator.apache.org/UserGuide/latest-Table/Deployment-and-Maintenance/Docker-Deployment_apache.html">
 *       Docker Deployment</a>（示例 {@code apache/iotdb:2.0.x-standalone}）；</li>
 *   <li>具体 tag {@code 2.0.11-standalone} 由官方 Docker Hub 仓库元数据核实（
 *       {@code https://hub.docker.com/v2/repositories/apache/iotdb/tags?name=2.0.11} 返回
 *       {@code 2.0.11-standalone}，amd64 + arm64，2026-09-14 推送），且官方
 *       <a href="https://iotdb.incubator.apache.org/Download/">Download</a> 页当前最新 release 为 2.0.11
 *       ——本仓 JDBC 驱动固定在 {@code 2.0.1-beta}，官方明确「不要用更新的客户端连更旧的服务端」，
 *       故服务端只能**不比驱动更旧**，2.0.11 满足；</li>
 *   <li>可用 {@code YPBIN_TEST_IOTDB_IMAGE} 覆盖镜像（离线环境/换版本时无需改代码）。</li>
 * </ul>
 *
 * <p>⚠️ <b>未能核实</b>：官方文档没有给出「standalone 镜像从启动到 JDBC 可用的确定耗时」，
 * 因此容器就绪用「6667 端口可连」+ 建表阶段的**有界重试**兜底，而不是断言某个时间上限
 * （本机实测：容器启动后建表一次成功，重试窗口未被用满）。</p>
 *
 * <p><b>本机实测（2026-09-24，非 CI）</b>：容器模式 + {@code dn_rpc_address=0.0.0.0} 下，
 * {@code jdbc:iotdb://localhost:<映射端口>?sql_dialect=table} 可完成建库建表/写入/查询；
 * 未设该变量时容器内日志为 {@code listening on ip 127.0.0.1 port 6667}，宿主端口连接被 reset
 * （客户端表现为 {@code TTransportException: Connection reset}），且**永不自愈**。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public final class IotDbIntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(IotDbIntegrationTestSupport.class);

    /** 外部 IoTDB 的 JDBC 根地址（**不带库名**、必须含 {@code sql_dialect=table}），设置即优先使用。 */
    public static final String ENV_IOTDB_URL = "YPBIN_TEST_IOTDB_URL";

    /** 外部 IoTDB 用户名（默认 root）。 */
    public static final String ENV_IOTDB_USERNAME = "YPBIN_TEST_IOTDB_USERNAME";

    /** 外部 IoTDB 口令（默认 root）。 */
    public static final String ENV_IOTDB_PASSWORD = "YPBIN_TEST_IOTDB_PASSWORD";

    /** 覆盖容器镜像（默认 {@link #DEFAULT_IOTDB_IMAGE}）。 */
    public static final String ENV_IOTDB_IMAGE = "YPBIN_TEST_IOTDB_IMAGE";

    /** 默认镜像（tag 已按官方一手来源核实，见类注释；可用 {@link #ENV_IOTDB_IMAGE} 覆盖）。 */
    public static final String DEFAULT_IOTDB_IMAGE = "apache/iotdb:2.0.11-standalone";

    /** 表模型必须带的连接参数（官方 JDBC 文档）。 */
    public static final String TABLE_DIALECT_PARAM = "sql_dialect=table";

    /** IoTDB 的 RPC/SQL 端口（官方 docker ReadMe 的端口说明）。 */
    static final int IOTDB_RPC_PORT = 6667;

    /**
     * DataNode RPC 监听地址的容器环境变量。
     *
     * <p><b>为什么必须覆盖</b>：官方镜像默认 {@code dn_rpc_address=127.0.0.1}（容器内日志实测
     * {@code listening on ip 127.0.0.1 port 6667}），此时 Docker 把 6667 发布到宿主端口也**不可达**
     * （客户端表现为 {@code TTransportException: Connection reset}）——端口发布只能转发到容器内
     * 非 loopback 的监听套接字。官方 Docker Deployment 文档正是用环境变量覆盖
     * {@code dn_rpc_address}/{@code cn_internal_address}/{@code dn_internal_address}
     * （访问 2026-09-24）；本容器是单实例，只需把对外 RPC 监听改为全地址即可（内部通信仍走默认）。</p>
     */
    private static final String ENV_DN_RPC_ADDRESS = "dn_rpc_address";

    /** 让 DataNode 监听容器内所有地址（端口发布才能转发进来）。 */
    private static final String BIND_ALL_ADDRESSES = "0.0.0.0";

    /** 容器启动上限（含 ConfigNode + DataNode 就绪）。 */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    private static final String DEFAULT_USERNAME = "root";

    private static final String DEFAULT_PASSWORD = "root";

    /** 已解析出的根 JDBC 地址（容器模式）；非空即表示容器已就绪。 */
    private static volatile String resolvedServerUrl;

    private IotDbIntegrationTestSupport() {
    }

    /**
     * IoTDB 是否可用于集成测试（外部实例或 Docker 容器任一可用）。
     *
     * @return 可用返回 true
     */
    public static boolean available() {
        return externalConfigured() || ContainerSupport.dockerAvailable();
    }

    /**
     * 跳过原因（{@code @EnabledIfIotDbAvailable} 会把原因写进报告并打一条 WARN，不静默跳过）。
     *
     * @return 原因说明
     */
    public static String skipReason() {
        return "IoTDB 不可用：未设置 " + ENV_IOTDB_URL + " 且本机 Docker 不可用，跳过集成测试";
    }

    /**
     * 服务根地址（不带库名）：外部实例优先，否则拉起官方镜像容器。
     *
     * @return JDBC 地址（含 {@code sql_dialect=table}）
     */
    public static String serverUrl() {
        if (externalConfigured()) {
            String external = System.getenv(ENV_IOTDB_URL);
            if (external == null || !external.contains(TABLE_DIALECT_PARAM)) {
                // 配置错就报错，不静默补参数：少了它连的是树模型，建表语句会以另一种方式失败，排查成本更高
                throw new IllegalStateException(ENV_IOTDB_URL + " 必须包含 " + TABLE_DIALECT_PARAM
                    + "（表模型要求，见官方 JDBC 文档）：" + external);
            }
            return external.trim();
        }
        return containerServerUrl();
    }

    /**
     * 在根地址上挂数据库名（插入到查询串之前）。
     *
     * <p>连接串形状与官方 JDBC 示例一致：{@code jdbc:iotdb://host:port/<db>?sql_dialect=table}。</p>
     *
     * <p><b>为什么必须把库名写进 URL</b>：写入器/存储用的是**非限定表名**（{@code INSERT INTO reading ...}、
     * {@code SELECT ... FROM reading ...}），且 Java 侧不会发 {@code USE <db>}——会话没有默认库时真库会报
     * {@code 701: database is not specified}。因此「库名在 URL 里」是当前实现的**前提条件**，不是可选写法。</p>
     *
     * @param database 数据库名
     * @return 指向该库的 JDBC 地址
     */
    public static String databaseUrl(String database) {
        String root = serverUrl();
        int queryIndex = root.indexOf('?');
        String base = queryIndex < 0 ? root : root.substring(0, queryIndex);
        String suffix = queryIndex < 0 ? "" : root.substring(queryIndex);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + database + suffix;
    }

    /**
     * 用户名。
     *
     * @return 用户名
     */
    public static String username() {
        return configuredElse(ENV_IOTDB_USERNAME, DEFAULT_USERNAME);
    }

    /**
     * 口令。
     *
     * @return 口令
     */
    public static String password() {
        return configuredElse(ENV_IOTDB_PASSWORD, DEFAULT_PASSWORD);
    }

    /** 是否显式配置了外部实例 */
    private static boolean externalConfigured() {
        String external = System.getenv(ENV_IOTDB_URL);
        return external != null && !external.isBlank();
    }

    private static String configuredElse(String envName, String fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String image() {
        return configuredElse(ENV_IOTDB_IMAGE, DEFAULT_IOTDB_IMAGE);
    }

    private static synchronized String containerServerUrl() {
        if (resolvedServerUrl != null) {
            return resolvedServerUrl;
        }
        String usedImage = image();
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(usedImage))
            // 必须覆盖 dn_rpc_address：官方镜像默认绑 127.0.0.1，端口发布出去的连接会被 reset（见常量注释）
            .withEnv(ENV_DN_RPC_ADDRESS, BIND_ALL_ADDRESSES)
            .withExposedPorts(IOTDB_RPC_PORT)
            .waitingFor(Wait.forListeningPorts(IOTDB_RPC_PORT).withStartupTimeout(STARTUP_TIMEOUT));
        container.start();
        // 先算好再发布静态字段：字段赋值是同步块内最后一步（同 ContainerSupport 的口径）
        String url = "jdbc:iotdb://" + container.getHost() + ":" + container.getMappedPort(IOTDB_RPC_PORT)
            + "?" + TABLE_DIALECT_PARAM;
        log.info("[iot-it] IoTDB 容器已启动：{}（镜像 {}）", url, usedImage);
        resolvedServerUrl = url;
        return url;
    }
}
