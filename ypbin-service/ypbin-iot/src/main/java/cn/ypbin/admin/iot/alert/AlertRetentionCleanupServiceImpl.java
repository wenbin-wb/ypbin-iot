/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 告警保留清理实现（口径 7）。
 *
 * <p><b>三条不可逆动作的护栏</b>（照 {@code RetentionCleanupServiceImpl} 的做法）：</p>
 * <ol>
 *   <li><b>用数据库时钟</b>算截止时刻（与应用时钟解耦，避免时区漂移导致误删）；</li>
 *   <li><b>天数必须为正</b>：0/负数意味着「全部过期」，这里拒绝执行并报错，绝不清空全表；</li>
 *   <li><b>只删已恢复的行</b>：活动告警**永久保留**，条件同时写在取 ID 与删除两条语句里
 *       （防「取 ID 与删除之间状态被改回活动」的窗口）。</li>
 * </ol>
 *
 * <p><b>为什么分批</b>：一次删掉几十万行会长时间持锁。分批时每次批量删除是**一条语句**
 * （{@code DELETE ... WHERE id IN (...)}），循环体里只有一次方法调用、没有裸的 Mapper 调用，
 * 满足仓内「循环内不做 DB 调用」的纪律。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertRetentionCleanupServiceImpl implements AlertRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AlertRetentionCleanupServiceImpl.class);

    /** 单轮最多迭代批次数（防配置异常导致无限循环把库拖住；正常情况下远用不到）。 */
    private static final int MAX_BATCHES_PER_ROUND = 100;

    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertNotificationMapper notificationMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final AlertProperties properties;
    private final AlertMetrics metrics;

    public AlertRetentionCleanupServiceImpl(IotAlertInstanceMapper instanceMapper,
                                            IotAlertNotificationMapper notificationMapper,
                                            DeviceLivenessMapper livenessMapper,
                                            AlertProperties properties, AlertMetrics metrics) {
        this.instanceMapper = instanceMapper;
        this.notificationMapper = notificationMapper;
        this.livenessMapper = livenessMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    @Scheduled(fixedDelayString = "${ypbin.alert.retention-cleanup-interval-ms:86400000}",
        initialDelayString = "${ypbin.alert.retention-initial-delay-ms:300000}")
    public CleanupResult cleanupOnce() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 告警保留清理已关闭（{}.enabled=false）：已恢复告警会无限增长",
                AlertProperties.PREFIX);
            return CleanupResult.skippedResult();
        }
        if (properties.getRetentionResolvedDays() <= 0) {
            // 不可逆动作的护栏：天数配成 0/负数意味着「全部过期」，拒绝执行而不是照删
            log.error("[iot] 告警保留天数非法（{} 天），本轮清理**拒绝执行**；请修正 {}.retention-resolved-days",
                properties.getRetentionResolvedDays(), AlertProperties.PREFIX);
            return CleanupResult.skippedResult();
        }
        LocalDateTime now = livenessMapper.selectNow();
        LocalDateTime cutoff = now.minusDays(properties.getRetentionResolvedDays());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_ROUND; batch++) {
            int deleted = cleanupBatch(cutoff);
            total += deleted;
            if (deleted < properties.getRetentionBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("[iot] 告警保留清理完成：删除已恢复告警 {} 行（截止 {}，保留 {} 天）", total, cutoff,
                properties.getRetentionResolvedDays());
        } else {
            log.info("[iot] 告警保留清理完成：无过期已恢复告警（截止 {}）", cutoff);
        }
        return new CleanupResult(false, total);
    }

    /**
     * 删除一批过期已恢复告警（含其投递记录）。
     *
     * <p>顺序：先删投递记录（子表），再删实例；两步都以「已恢复实例的 ID」为锚点。
     * 两次批量删除都是**单条语句**，不在循环里单条查/删。</p>
     *
     * @param cutoff 截止时刻
     * @return 删除的实例行数
     */
    private int cleanupBatch(LocalDateTime cutoff) {
        int limit = properties.getRetentionBatchSize();
        List<Long> ids = TenantContext.executeIgnore(
            () -> instanceMapper.selectResolvedIdsBefore(cutoff, limit));
        if (ids.isEmpty()) {
            return 0;
        }
        int deletedNotifications = TenantContext.executeIgnore(
            () -> notificationMapper.deleteByInstanceIds(ids));
        int deletedInstances = TenantContext.executeIgnore(() -> instanceMapper.deleteResolvedByIds(ids));
        metrics.retentionDeleted(deletedInstances);
        if (deletedNotifications > 0) {
            log.debug("[iot] 告警保留清理：随实例一并删除投递记录 {} 行", deletedNotifications);
        }
        return deletedInstances;
    }
}
