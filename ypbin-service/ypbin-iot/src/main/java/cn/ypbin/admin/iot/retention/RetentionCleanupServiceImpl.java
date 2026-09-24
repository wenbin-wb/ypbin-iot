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

import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 保留清理实现（D0.8）。
 *
 * <p>要点：</p>
 * <ol>
 *   <li><b>用数据库时钟</b>算截止时刻（与应用时钟解耦，避免时区/漂移导致误删）；</li>
 *   <li><b>跨租户</b>：清理没有租户身份，必须 {@link TenantContext#executeIgnore}；表是租户表，
 *       不忽略的话租户插件会追加条件并在无上下文时直接拒绝执行；</li>
 *   <li><b>一轮两张表各一条 DELETE</b>：不在循环里做数据库调用（本仓铁律），批量删除交给单条语句；</li>
 *   <li><b>不可逆动作的护栏</b>：天数必须为正（启动自检 + 每轮复核），否则拒绝执行并报错，绝不清空全表。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class RetentionCleanupServiceImpl implements RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupServiceImpl.class);

    /** 清理计数（按表打 tag，便于分别观察）。 */
    public static final String METRIC_DELETED = "iot.retention.deleted";

    private final OutageEventMapper outageEventMapper;
    private final MaintenanceWindowMapper maintenanceWindowMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final RetentionProperties properties;
    private final Counter outageCounter;
    private final Counter windowCounter;

    public RetentionCleanupServiceImpl(OutageEventMapper outageEventMapper,
                                       MaintenanceWindowMapper maintenanceWindowMapper,
                                       DeviceLivenessMapper livenessMapper,
                                       RetentionProperties properties, MeterRegistry meterRegistry) {
        this.outageEventMapper = outageEventMapper;
        this.maintenanceWindowMapper = maintenanceWindowMapper;
        this.livenessMapper = livenessMapper;
        this.properties = properties;
        this.outageCounter = Counter.builder(METRIC_DELETED).tag("table", "outage_event")
            .description("保留清理删除的行数").register(meterRegistry);
        this.windowCounter = Counter.builder(METRIC_DELETED).tag("table", "maintenance_window")
            .description("保留清理删除的行数").register(meterRegistry);
    }

    @Override
    @Scheduled(fixedDelayString = "${ypbin.retention.cleanup-interval-ms:86400000}",
        initialDelayString = "${ypbin.retention.initial-delay-ms:300000}")
    public RetentionCleanupResult cleanupOnce() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 保留清理已关闭（{}.enabled=false）：断档事件与维护窗口会无限增长",
                RetentionProperties.PREFIX);
            return RetentionCleanupResult.skippedResult();
        }
        if (properties.getOutageEventDays() <= 0 || properties.getMaintenanceWindowDays() <= 0) {
            // 不可逆动作的护栏：天数配置成 0/负数意味着"全部过期"，这里拒绝执行而不是照删
            log.error("[iot] 保留天数非法（断档={} 天，维护窗口={} 天），本轮清理**拒绝执行**；"
                    + "请修正 {}.outage-event-days / {}.maintenance-window-days",
                properties.getOutageEventDays(), properties.getMaintenanceWindowDays(),
                RetentionProperties.PREFIX, RetentionProperties.PREFIX);
            return RetentionCleanupResult.skippedResult();
        }
        LocalDateTime now = livenessMapper.selectNow();
        LocalDateTime outageCutoff = now.minusDays(properties.getOutageEventDays());
        LocalDateTime windowCutoff = now.minusDays(properties.getMaintenanceWindowDays());

        int deletedOutages = TenantContext.executeIgnore(
            () -> outageEventMapper.deleteStartedBefore(outageCutoff));
        int deletedWindows = TenantContext.executeIgnore(
            () -> maintenanceWindowMapper.deleteStartedBefore(windowCutoff));
        outageCounter.increment(deletedOutages);
        windowCounter.increment(deletedWindows);

        if (deletedOutages > 0 || deletedWindows > 0) {
            log.info("[iot] 保留清理完成：断档事件 {} 行（截止 {}）、维护窗口 {} 行（截止 {}）",
                deletedOutages, outageCutoff, deletedWindows, windowCutoff);
        } else {
            log.info("[iot] 保留清理完成：无过期数据（断档截止 {}、维护窗口截止 {}）",
                LogSanitizer.sanitize(outageCutoff), LogSanitizer.sanitize(windowCutoff));
        }
        return new RetentionCleanupResult(deletedOutages, deletedWindows, false);
    }
}
