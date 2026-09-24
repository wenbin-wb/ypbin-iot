/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据保留策略（D0.8：分层保留）。
 *
 * <p>为什么要有它：断档事件与维护窗口是**可用率报表的历史依据**，既要支持年度复盘（13 个月），
 * 又不能无限增长把库撑爆；而「删数据」是不可逆动作，因此：① 天数必须为正（0/负数会被启动自检拒绝，
 * 防止配置笔误变成"清空全表"）；② 默认关闭**不做**，默认开启但只删过期数据；③ 每次清理都打日志 + 计数。</p>
 *
 * <p>原始时序（IoTDB）的保留不在这里：它由 IoTDB 表模型的 TTL 承担（D0.8/§5.2），
 * 因为二者是不同的存储与不同的删除语义。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@ConfigurationProperties(prefix = RetentionProperties.PREFIX)
public class RetentionProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.retention";

    /** 是否启用清理（关闭时每轮只打一条 WARN，不删任何数据）。 */
    private boolean enabled = true;

    /** 断档事件保留天数（默认 396 天 ≈ 13 个月，覆盖年度复盘）。 */
    private int outageEventDays = 396;

    /** 维护窗口保留天数（默认 396 天 ≈ 13 个月，与断档口径一致：可用率报表要同时看它们）。 */
    private int maintenanceWindowDays = 396;

    /** 清理间隔（毫秒；默认 24 小时）。 */
    private long cleanupIntervalMs = 86_400_000L;

    /** 首次清理延迟（毫秒；默认 5 分钟，避免与应用启动争抢数据库）。 */
    private long initialDelayMs = 300_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOutageEventDays() {
        return outageEventDays;
    }

    public void setOutageEventDays(int outageEventDays) {
        this.outageEventDays = outageEventDays;
    }

    public int getMaintenanceWindowDays() {
        return maintenanceWindowDays;
    }

    public void setMaintenanceWindowDays(int maintenanceWindowDays) {
        this.maintenanceWindowDays = maintenanceWindowDays;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
