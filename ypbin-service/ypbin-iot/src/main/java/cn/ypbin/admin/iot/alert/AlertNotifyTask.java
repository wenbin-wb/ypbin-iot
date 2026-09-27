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
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import java.time.LocalDateTime;

/**
 * 一条「待入队的通知」（供 {@link AlertNotifyScheduler} 与调用方之间传递）。
 *
 * <p>{@code now} 必须**整轮一致**：实例上的 {@code last_notified_ts} 与通知行上的
 * {@code next_retry_ts} 若来自两个不同的时钟读数，「为什么这条早了一秒」会成为无法复现的悬案。</p>
 *
 * @param instance 实例（新实例必须已预生成 id）
 * @param rule     规则（断档类用覆盖规则；为 {@code null} 时按平台默认渠道与收件人回落）
 * @param event    通知事件
 * @param now      本轮时刻
 * @author wenbin
 * @since 2026-10-03
 */
public record AlertNotifyTask(IotAlertInstance instance, IotAlertRule rule, AlertNotifyEvent event,
                              LocalDateTime now) {
}
