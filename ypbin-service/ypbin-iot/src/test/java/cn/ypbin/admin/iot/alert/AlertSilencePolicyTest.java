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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 静默判定的用例（设计 §3.4 的 S1/S2/S3 与 §2.3「静默不是状态」）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertSilencePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private static IotAlertInstance instance() {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(1L);
        instance.setDeviceId(9L);
        return instance;
    }

    private static MaintenanceWindow window(Long deviceId, LocalDateTime start, LocalDateTime end) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setDeviceId(deviceId);
        window.setStartTs(start);
        window.setEndTs(end);
        return window;
    }

    @Test
    @DisplayName("实例级静默：截止时刻之前静默、之后不静默（静默不改变任何状态）")
    void instanceLevelSilence() {
        AlertSilencePolicy policy = new AlertSilencePolicy(mock(MaintenanceWindowMapper.class));
        IotAlertInstance instance = instance();
        instance.setSilenceUntil(NOW.plusHours(1));
        assertThat(policy.isSilenced(instance, null, 9L, NOW, List.of())).isTrue();
        assertThat(policy.isSilenced(instance, null, 9L, NOW.plusHours(1), List.of())).isFalse();
    }

    @Test
    @DisplayName("S1 规则静默窗口内静默；窗口结束后不再静默（也不补发历史通知）")
    void ruleWindowSilence() {
        AlertSilencePolicy policy = new AlertSilencePolicy(mock(MaintenanceWindowMapper.class));
        IotAlertRule rule = new IotAlertRule();
        rule.setSilenceStart(NOW.minusMinutes(10));
        rule.setSilenceEnd(NOW.plusMinutes(10));
        assertThat(policy.isSilenced(instance(), rule, 9L, NOW, List.of())).isTrue();
        assertThat(policy.isSilenced(instance(), rule, 9L, NOW.plusMinutes(11), List.of())).isFalse();
        // 只有开始没有结束：视为「从起点起一直静默」（页面上会明确展示这个语义）
        rule.setSilenceEnd(null);
        assertThat(policy.isSilenced(instance(), rule, 9L, NOW.plusDays(1), List.of())).isTrue();
    }

    @Test
    @DisplayName("S2 维护窗口 = 静默：设备级窗口只静默该设备，整租户窗口（device_id 为空）静默全部")
    void maintenanceWindowSilence() {
        AlertSilencePolicy policy = new AlertSilencePolicy(mock(MaintenanceWindowMapper.class));
        List<MaintenanceWindow> deviceScoped = List.of(window(9L, NOW.minusHours(1), NOW.plusHours(1)));
        assertThat(policy.isSilenced(instance(), null, 9L, NOW, deviceScoped)).isTrue();
        assertThat(policy.isSilenced(instance(), null, 77L, NOW, deviceScoped)).isFalse();

        List<MaintenanceWindow> tenantWide = List.of(window(null, NOW.minusHours(1), NOW.plusHours(1)));
        assertThat(policy.isSilenced(instance(), null, 77L, NOW, tenantWide)).isTrue();
    }

    // 说明（如实登记，勿夸大）：`loadActiveWindows` 用 MyBatis-Plus 的 LambdaQueryWrapper 构造
    // 「start_ts <= now 且（end_ts 为空 或 end_ts >= now）」的重叠条件，而**纯单测里没有 MyBatis
    // 元数据**（Lambda 缓存未初始化，构造 wrapper 就抛「can not find lambda cache for this entity」）。
    // 该方法的真实语义因此只能由**真库**验证：CI 的 `-Pit` 用例与生产演示（维护窗口静默）共同承担，
    // 本文件的纯函数用例只覆盖「拿到窗口列表之后」的判定，不声称覆盖查询条件本身。
}
