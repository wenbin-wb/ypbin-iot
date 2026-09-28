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

import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跨租户的投递轮次编排（设计 §2.4）。
 *
 * <p>与评估器同构的两段式：**跨租户取到期记录**（一次查询，{@code executeIgnore}），再**逐租户进入各自
 * 上下文投递**（否则租户插件会拒绝执行，或更新到错误租户的行）。</p>
 *
 * <p><b>限流是全局的</b>：{@link AlertNotifyThrottle} 的状态在服务实例内共享（跨租户），
 * 因此「一次大面积故障把邮件网关打挂」这种全局风险才能被挡住；单租户限流挡不住它。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Service
public class AlertNotifyDispatchServiceImpl implements AlertNotifyDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifyDispatchServiceImpl.class);

    private final IotAlertNotificationMapper notificationMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final AlertTenantNotifier tenantNotifier;
    private final AlertMetrics metrics;
    private final AlertProperties properties;

    public AlertNotifyDispatchServiceImpl(IotAlertNotificationMapper notificationMapper,
                                          DeviceLivenessMapper livenessMapper,
                                          AlertTenantNotifier tenantNotifier, AlertMetrics metrics,
                                          AlertProperties properties) {
        this.notificationMapper = notificationMapper;
        this.livenessMapper = livenessMapper;
        this.tenantNotifier = tenantNotifier;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public DispatchOutcome dispatchOnce() {
        if (!properties.isEnabled()) {
            log.warn("[iot] 告警总开关已关闭（{}.enabled=false）：投递器不执行", AlertProperties.PREFIX);
            return DispatchOutcome.skippedOutcome(false);
        }
        if (!properties.isNotifyEnabled()) {
            log.warn("[iot] 告警通知投递已关闭（{}.notify-enabled=false）：判定照常、告警可见，只是不投递",
                AlertProperties.PREFIX);
            return DispatchOutcome.skippedOutcome(true);
        }
        LocalDateTime now = livenessMapper.selectNow();
        List<IotAlertNotification> due = TenantContext.executeIgnore(
            () -> notificationMapper.selectDue(now, properties.getNotifyDispatchBatchSize()));
        if (due.isEmpty()) {
            return new DispatchOutcome(true, false, 0, 0, 0, 0, 0, 0);
        }
        Map<Long, List<IotAlertNotification>> byTenant = new LinkedHashMap<>();
        for (IotAlertNotification item : due) {
            if (item.getTenantId() == null) {
                // 租户表插入时必带 tenant_id；缺失说明数据异常 —— 跳过并留痕，绝不猜一个租户去投递
                log.error("[iot] 告警通知缺少 tenant_id，已跳过投递：notificationId={}", item.getId());
                continue;
            }
            byTenant.computeIfAbsent(item.getTenantId(), key -> new ArrayList<>()).add(item);
        }
        int sent = 0;
        int failed = 0;
        int givenUp = 0;
        int throttled = 0;
        int tenants = 0;
        for (Map.Entry<Long, List<IotAlertNotification>> entry : byTenant.entrySet()) {
            Long tenantId = entry.getKey();
            AlertTenantNotifier.TenantDispatchOutcome outcome;
            try {
                outcome = TenantContext.executeWithTenant(tenantId,
                    () -> tenantNotifier.dispatchTenant(entry.getValue(), now));
            } catch (RuntimeException ex) {
                // 单租户失败不拖累其它租户；**不吞异常**（记完整堆栈）
                log.error("[iot] 告警通知投递：租户 {} 本轮失败（其它租户照常）", tenantId, ex);
                continue;
            }
            tenants++;
            sent += outcome.sent();
            failed += outcome.failed();
            givenUp += outcome.givenUp();
            throttled += outcome.throttled();
        }
        if (sent > 0 || failed > 0 || givenUp > 0 || throttled > 0) {
            log.info("[iot] 告警通知投递完成：到期 {}、成功 {}、失败待重试 {}、放弃 {}、限流延后 {}、租户 {}",
                due.size(), sent, failed, givenUp, throttled, tenants);
        } else {
            log.debug("[iot] 告警通知投递完成：到期 {} 条，无状态变化", due.size());
        }
        return new DispatchOutcome(true, false, due.size(), sent, failed, givenUp, throttled, tenants);
    }
}
