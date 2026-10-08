/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台自告警配置（看板 #10）。
 *
 * <p><b>为什么 `enabled` 默认 false</b>：一期定位是"观察期只落库"（设计 §2.1），
 * 且它是一条**新的后台任务**——不加开关就默认跑，等于把未经验证的阈值直接接进生产。
 * 新增后台任务必须可显式控制，这是运维底线。</p>
 *
 * <p><b>为什么 `notifyEnabled` 也默认 false</b>：收件人尚未裁定（设计 §5 属产品决策）。
 * 预留该键是为了让"开通通知"这件事**必须显式配置**，而不是某天被顺带打开；
 * 开关与收件人配齐后由 {@link PlatformAlertNotifier} 消费（#132 起已接线）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
@ConfigurationProperties(prefix = PlatformAlertProperties.PREFIX)
public class PlatformAlertProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.platform-alert";

    /**
     * 平台告警实例归属的租户 ID（**主租户**）。
     *
     * <p>平台健康规则是企业级全局语义、本身没有租户维度，但
     * {@code iot_platform_alert.tenant_id} 是 **NOT NULL**，且参与去重唯一键
     * {@code uk_platform_alert_active(tenant_id, active_dedup_key)} ⇒ 开单必须显式写主租户：
     * 留空时 `TenantContext.runIgnore` 下租户拦截器不会补值，整条 INSERT 被库以
     * 「Column 'tenant_id' cannot be null」拒绝（2026-10-08 dev 真实触发时实测；
     * 此前观察期没有一次 FIRING，故一直被"没有告警"掩盖）。</p>
     *
     * <p>⚠️ **不能改成"允许 tenant_id 为 NULL"**：`uk_platform_alert_active` 含 tenant_id，
     * 而 MySQL 唯一索引对含 NULL 的行不做约束 ⇒ 活动告警会整批逃出去重（同一类静默刷屏缺陷，
     * 见 {@code IotPlatformAlert} 类注释）。</p>
     */
    public static final long PLATFORM_TENANT_ID = 1L;

    /** 是否启用平台自告警判定（默认 false：观察期需显式开启）。 */
    private boolean enabled = false;

    /**
     * 是否投递通知（默认 false）。
     *
     * <p>由 {@link PlatformAlertNotifier} 消费：为 false 时一律不投递（连收件人都解析）；
     * 为 true 但收件人为空时记 WARN 不投递（让"开关开了却没人收到"可审计）。</p>
     */
    private boolean notifyEnabled = false;

    /**
     * 平台告警收件人：系统用户 ID 列表（用户裁定：选当前系统用户；站内信通道）。默认空 = 不投递站内信。
     */
    private List<Long> recipientUserIds = new ArrayList<>();

    /**
     * 平台告警收件人：额外邮箱列表（选填；EMAIL 通道）。默认空 = 不投递邮件。
     */
    private List<String> recipientExtraEmails = new ArrayList<>();
}
