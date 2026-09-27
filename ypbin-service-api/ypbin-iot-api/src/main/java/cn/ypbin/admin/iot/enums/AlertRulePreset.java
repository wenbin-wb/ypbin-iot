/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

import java.util.List;

/**
 * 「傻瓜式」一键预设模板（用户口径：功能尽量全面 + 傻瓜式 + 方便）。
 *
 * <p><b>为什么预设放在后端而不是前端写死</b>：预设的默认值（操作符、连续次数、级别、重发间隔）
 * 属于**平台口径**，前端写死会让「换个端侧/换个语言」时同一套模板给出不同行为；放后端也便于
 * 预设演进时不需要发前端版本。前端只负责把 {@code defaults} 渲染成表单与「人话预览」。</p>
 *
 * <p><b>四类模板与「退出机制」的对应关系</b>：</p>
 * <ul>
 *   <li>{@link #POINT_ABOVE_UPPER} / {@link #POINT_BELOW_LOWER}：点位数值阈值（本期唯一的**判定类**规则，
 *       用户口径 2）；</li>
 *   <li>{@link #DEVICE_OFFLINE} / {@link #DATA_INTERRUPT}：**没有点位条件的规则**，判定完全由平台既有的
 *       断档链路（{@code outage_event} + {@code OutageScanner}）承担，规则只决定级别、范围、通知对象与
 *       通知节奏——**不新造判定**（用户口径 4 + 设计 §2.6「设备离线类告警」的取舍）。</li>
 * </ul>
 *
 * <p>两者复用同一个断档判定，区别只在**范围与通知节奏**（单台设备用严重级别+高频重发；大范围数据中断
 * 用警告级别+低频重发，避免一次大面积故障把邮件网关打挂）。这一点必须在界面上如实说明，
 * 不能让用户以为「数据中断」是另一套判定。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertRulePreset {

    /** 点位超上限：{@code 温度 连续 3 次 > 80 ℃ 就告警}。 */
    POINT_ABOVE_UPPER("POINT_ABOVE_UPPER", "点位超上限",
        "page.iot.alert.preset.aboveUpper", AlertScopeType.POINT, AlertOperator.GT, true,
        AlertSeverity.WARNING, 1800),

    /** 点位低于下限：{@code 液位 连续 3 次 < 10 % 就告警}。 */
    POINT_BELOW_LOWER("POINT_BELOW_LOWER", "点位低于下限",
        "page.iot.alert.preset.belowLower", AlertScopeType.POINT, AlertOperator.LT, true,
        AlertSeverity.WARNING, 1800),

    /** 设备离线（单台）：断档类，默认范围 = 指定设备。 */
    DEVICE_OFFLINE("DEVICE_OFFLINE", "设备离线",
        "page.iot.alert.preset.deviceOffline", AlertScopeType.DEVICE, null, false,
        AlertSeverity.CRITICAL, 1800),

    /** 数据中断（大范围）：断档类，默认范围 = 整租户（可改成产品/设备），通知节奏更安静。 */
    DATA_INTERRUPT("DATA_INTERRUPT", "数据中断",
        "page.iot.alert.preset.dataInterrupt", AlertScopeType.TENANT, null, false,
        AlertSeverity.WARNING, 3600);

    private final String code;

    private final String desc;

    /** 模板标题的 i18n 键前缀（前端据此渲染标题、帮助与「人话预览」）。 */
    private final String i18nKey;

    /** 默认作用域。 */
    private final AlertScopeType defaultScopeType;

    /** 默认比较符（断档类为 {@code null}：它没有阈值）。 */
    private final AlertOperator defaultOperator;

    /** 是否需要用户填「点位 + 阈值」。 */
    private final boolean needsPointCondition;

    /** 默认级别。 */
    private final AlertSeverity defaultSeverity;

    /** 默认重复通知间隔（秒）。 */
    private final int defaultRepeatIntervalSec;

    AlertRulePreset(String code, String desc, String i18nKey, AlertScopeType defaultScopeType,
                    AlertOperator defaultOperator, boolean needsPointCondition,
                    AlertSeverity defaultSeverity, int defaultRepeatIntervalSec) {
        this.code = code;
        this.desc = desc;
        this.i18nKey = i18nKey;
        this.defaultScopeType = defaultScopeType;
        this.defaultOperator = defaultOperator;
        this.needsPointCondition = needsPointCondition;
        this.defaultSeverity = defaultSeverity;
        this.defaultRepeatIntervalSec = defaultRepeatIntervalSec;
    }

    /** 稳定码（传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /** 标题的 i18n 键前缀。 */
    public String getI18nKey() {
        return i18nKey;
    }

    /** 默认作用域。 */
    public AlertScopeType getDefaultScopeType() {
        return defaultScopeType;
    }

    /** 默认比较符（{@code null} = 无阈值，断档类）。 */
    public AlertOperator getDefaultOperator() {
        return defaultOperator;
    }

    /** 是否需要用户填「点位 + 阈值」。 */
    public boolean isNeedsPointCondition() {
        return needsPointCondition;
    }

    /** 默认级别。 */
    public AlertSeverity getDefaultSeverity() {
        return defaultSeverity;
    }

    /** 默认重复通知间隔（秒）。 */
    public int getDefaultRepeatIntervalSec() {
        return defaultRepeatIntervalSec;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertRulePreset of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertRulePreset value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 全部预设码（供接口与文档生成）。 */
    public static List<String> codes() {
        return List.of(POINT_ABOVE_UPPER.code, POINT_BELOW_LOWER.code, DEVICE_OFFLINE.code,
            DATA_INTERRUPT.code);
    }
}
