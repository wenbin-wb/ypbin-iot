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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 时序库（IoTDB 表模型）配置（D0.7/§5.2.1）。
 *
 * <p><b>默认关闭</b>：真库写入与 TTL 行为必须由容器 IT 证明（本机跑不了 IoTDB），
 * 在 IT 就位之前**不把未验证的写入路径默认打开**——这是本仓「验证后才宣称完成」的取向。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@ConfigurationProperties(prefix = TimeSeriesProperties.PREFIX)
public class TimeSeriesProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.timeseries";

    /** 是否启用时序写入（默认 false；启用前请先补齐容器 IT）。 */
    private boolean enabled = false;

    /** IoTDB JDBC 地址（如 jdbc:iotdb://127.0.0.1:6667/）。 */
    private String url = "";

    /** 用户名。 */
    private String username = "root";

    /** 密码。 */
    private String password = "root";

    /** 目标表名（§5.2.1 的单表模型，默认 reading）。 */
    private String tableName = "reading";

    /** 批量大小（一次 addBatch/executeBatch 的行数上限）。 */
    private int batchSize = 500;

    /** 连接超时（毫秒，远程调用必须显式超时：本仓铁律）。 */
    private int connectTimeoutMs = 3_000;

    /**
     * 「库内真值对账」探针的间隔（毫秒，默认 10 分钟，见 {@link IotDbRowCountProbe}）。
     *
     * <p>低频是刻意设计：它是一次全表聚合，属额外负载；调成高频会把观测设施变成压力来源。</p>
     */
    private long dbRowsProbeIntervalMs = IotDbRowCountProbe.DEFAULT_PROBE_INTERVAL_MS;

    /** 「库内真值对账」查询的超时（秒）：远程调用必须显式超时（下推到 IoTDB RPC，见探针类注释）。 */
    private int dbRowsProbeQueryTimeoutSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getDbRowsProbeIntervalMs() {
        return dbRowsProbeIntervalMs;
    }

    public void setDbRowsProbeIntervalMs(long dbRowsProbeIntervalMs) {
        this.dbRowsProbeIntervalMs = dbRowsProbeIntervalMs;
    }

    public int getDbRowsProbeQueryTimeoutSeconds() {
        return dbRowsProbeQueryTimeoutSeconds;
    }

    public void setDbRowsProbeQueryTimeoutSeconds(int dbRowsProbeQueryTimeoutSeconds) {
        this.dbRowsProbeQueryTimeoutSeconds = dbRowsProbeQueryTimeoutSeconds;
    }
}
