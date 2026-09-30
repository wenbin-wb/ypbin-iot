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
 * <p><b>为什么 `notifyEnabled` 也默认 false 且当前无实现</b>：收件人尚未裁定
 * （设计 §5 属产品决策）。预留该键是为了让"开通通知"这件事**必须显式配置**，
 * 而不是某天被顺带打开。</p>
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

    /** 是否启用平台自告警判定（默认 false：观察期需显式开启）。 */
    private boolean enabled = false;

    /**
     * 是否投递通知（默认 false）。
     *
     * <p>⚠️ **当前实现不消费该键**（一期只落库）。保留它是为了让"开通通知"成为一次
     * 显式配置变更，而不是悄悄发生。</p>
     */
    private boolean notifyEnabled = false;
}
