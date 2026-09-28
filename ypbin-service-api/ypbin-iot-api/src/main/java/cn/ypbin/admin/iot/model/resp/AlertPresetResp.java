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

import lombok.Getter;
import lombok.Setter;

/**
 * 一键预设模板视图（用户口径 ①：至少「点位超上限」「点位低于下限」「设备离线」「数据中断」四类，
 * 选设备+点位+数字即可，不用懂任何术语）。
 *
 * <p>后端只给**默认值 + i18n 键**，标题/说明/「人话预览」文案由前端按语言渲染——同一份默认值在任何
 * 语言下行为一致，而文案随语言变化（这是与设计的偏差里唯一一处「把展示层职责放前端」的选择，
 * 因为它本来就是展示层的事）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertPresetResp {

    /** 预设码。 */
    private String code;

    /** 标题 i18n 键（如 {@code page.iot.alert.preset.aboveUpper}）。 */
    private String i18nKey;

    /** 默认作用域码。 */
    private String defaultScopeType;

    /** 默认比较符码（断档类为 {@code null}）。 */
    private String defaultOperator;

    /** 是否需要用户填「点位 + 阈值」。 */
    private Boolean needsPointCondition;

    /** 默认级别码。 */
    private String defaultSeverity;

    /** 默认抖动抑制模式码。 */
    private String defaultTriggerMode;

    /** 默认连续次数 / 持续秒数。 */
    private Integer defaultTriggerThreshold;

    /** 默认候选挂起秒数。 */
    private Integer defaultPendingTtlSec;

    /** 默认重复通知间隔秒数。 */
    private Integer defaultRepeatIntervalSec;

    /** 默认通知渠道码集合。 */
    private String defaultNotifyChannels;
}
