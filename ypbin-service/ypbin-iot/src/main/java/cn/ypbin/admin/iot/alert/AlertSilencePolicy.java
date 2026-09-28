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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 静默判定（设计 §2.3/§2.4/§3.4-S1/S2）。
 *
 * <p><b>静默不是状态</b>：它只影响「发不发通知」，与阈值判定、状态机完全正交。做成状态会让
 * 「静默中的告警」在页面上显得像不存在，而且静默结束后该回哪个状态无法定义（设计 §2.3 的原话）。</p>
 *
 * <p>三级来源，任一层命中即静默：</p>
 * <ol>
 *   <li><b>实例级</b>（{@code iot_alert_instance.silence_until}）——页面上的「一键静默 1 小时」；</li>
 *   <li><b>规则窗口</b>（{@code iot_alert_rule.silence_start/end}）——计划停机这类已知窗口；</li>
 *   <li><b>维护窗口</b>（{@code maintenance_window}，含 {@code device_id IS NULL} 的整租户窗口）——
 *       **直接复用既有能力，不新造静默概念**（设计 §2.0/§2.3）。</li>
 * </ol>
 *
 * <p>维护窗口在评估器里是**一次批量查询**后传进来的（见 {@link #loadActiveWindows}），
 * 因为逐个候选查窗口就是 N+1。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertSilencePolicy {

    private final MaintenanceWindowMapper maintenanceWindowMapper;

    public AlertSilencePolicy(MaintenanceWindowMapper maintenanceWindowMapper) {
        this.maintenanceWindowMapper = maintenanceWindowMapper;
    }

    /**
     * 取当前生效的维护窗口（**一次查询**；含整租户窗口）。
     *
     * @param now 当前时刻（数据库时钟算出）
     * @return 生效窗口（无则空列表）
     */
    public List<MaintenanceWindow> loadActiveWindows(LocalDateTime now) {
        return maintenanceWindowMapper.selectList(new LambdaQueryWrapper<MaintenanceWindow>()
            .select(MaintenanceWindow::getId, MaintenanceWindow::getDeviceId,
                MaintenanceWindow::getStartTs, MaintenanceWindow::getEndTs)
            .le(MaintenanceWindow::getStartTs, now)
            .and(wrapper -> wrapper.isNull(MaintenanceWindow::getEndTs)
                .or().ge(MaintenanceWindow::getEndTs, now)));
    }

    /**
     * 是否处于静默期（**纯函数**，不查库；维护窗口由调用方批量传入）。
     *
     * @param instance      告警实例
     * @param rule          规则（可空；断档映射没有规则时不做规则窗口判定）
     * @param deviceId      设备 ID（用于匹配维护窗口）
     * @param now           当前时刻
     * @param activeWindows 当前生效的维护窗口（{@link #loadActiveWindows} 的结果）
     * @return 静默返回 {@code true}
     */
    public boolean isSilenced(IotAlertInstance instance, IotAlertRule rule, Long deviceId,
                              LocalDateTime now, List<MaintenanceWindow> activeWindows) {
        if (instance.getSilenceUntil() != null && now.isBefore(instance.getSilenceUntil())) {
            return true;
        }
        if (rule != null && rule.getSilenceStart() != null && !now.isBefore(rule.getSilenceStart())
            && (rule.getSilenceEnd() == null || !now.isAfter(rule.getSilenceEnd()))) {
            return true;
        }
        for (MaintenanceWindow window : activeWindows) {
            if (window.getDeviceId() == null || window.getDeviceId().equals(deviceId)) {
                return true;
            }
        }
        return false;
    }
}
