/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警规则视图。
 *
 * <p>除规则自身的字段外，还带上了**解析后的展示名**（设备名/编码、产品名、点位名）：列表页要一眼看懂
 * 「这条规则管的是谁」，让前端逐行再查设备/产品就是 N+1（本仓铁律）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRuleResp {

    /** 规则 ID（Long 全局序列化为字符串）。 */
    private Long id;

    /** 规则名。 */
    private String ruleName;

    /** 作用域码。 */
    private String scopeType;

    /** 作用域产品 ID。 */
    private Long scopeProductId;

    /** 作用域设备 ID。 */
    private Long scopeDeviceId;

    /** 产品名（解析后的展示名；可空）。 */
    private String productName;

    /** 设备名（解析后的展示名；可空）。 */
    private String deviceName;

    /** 设备编码（解析后的展示名；可空）。 */
    private String deviceCode;

    /** 级别码。 */
    private String severity;

    /** 是否启用。 */
    private Boolean enabled;

    /** 抖动抑制模式码。 */
    private String triggerMode;

    /** 抖动抑制参数。 */
    private Integer triggerThreshold;

    /** 候选最大挂起秒数。 */
    private Integer pendingTtlSec;

    /** 重复通知间隔秒数。 */
    private Integer repeatIntervalSec;

    /** 规则静默窗口起。 */
    private LocalDateTime silenceStart;

    /** 规则静默窗口止。 */
    private LocalDateTime silenceEnd;

    /** 通知渠道码集合。 */
    private String notifyChannels;

    /** 通知对象。 */
    private String notifyTargets;

    /** 说明。 */
    private String description;

    /** 点位条件行。 */
    private List<AlertRulePointResp> points;

    /** 该规则当前活动告警数（列表页一眼看出「这条规则现在有几条在响」）。 */
    private Integer activeCount;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
