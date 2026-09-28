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

import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertValueType;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * 点位阈值判定的**纯逻辑**（设计 §2.2.3 / §2.3）。
 *
 * <p>刻意做成无状态、无 Spring、无 IO 的静态工具：判定语义是本设计里**最容易出错**的一段
 * （「不可判定」当越界 → 误报；当未越界 → 漏报且静默），必须能被单测逐条钉死，
 * 而不是埋在评估器的数据库/Redis 代码里无法单独验证。</p>
 *
 * <p><b>三条不可动摇的口径</b>：</p>
 * <ol>
 *   <li>点位的 {@code value} 是**字符串**（设计 §2.2.3）⇒ 判定前必须显式数值化，
 *       解析失败即「不可判定」，**不得**当作 0、**不得**当作越界；</li>
 *   <li>质量位不是 {@code GOOD} ⇒ 不可判定（否则一次坏质量会被算成“恢复正常”而错误地 resolve 掉告警）；</li>
 *   <li>读数时刻陈旧 ⇒ 不可判定，且**陈旧不等于恢复**——「设备不报数了」应当由断档告警表达，
 *       不能让「温度告警」因为读不到数就自动恢复。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public final class AlertRules {

    /** 有效数据的质量码（与 {@code AvailabilityRules.QUALITY_GOOD} 同源；此处不跨包依赖，故各自声明常量）。 */
    public static final String QUALITY_GOOD = "GOOD";

    /**
     * 断档映射产生的告警实例所用的**保留规则 ID**（{@code iot_alert_instance.rule_id}）。
     *
     * <p>设计表 C 要求 {@code rule_id} 非空，而断档事件没有规则 owner；用保留值 0 而不是 NULL，
     * 可以让唯一键与索引保持简单（MySQL 唯一索引对含 NULL 的行不约束，见 {@link AlertValueVerdict}
     * 同级的设计说明）。页面上把 0 展示成「设备离线（平台断档链路）」。</p>
     */
    public static final long RULE_ID_OUTAGE = 0L;

    /** 去重键分隔符。 */
    public static final String DEDUP_SEPARATOR = ":";

    /** 断档去重键前缀。 */
    public static final String OUTAGE_DEDUP_PREFIX = "OUTAGE";

    /** 去重键里 {@code propertyId} 为空时的占位（设备级规则/断档类规则）。 */
    public static final String NO_POINT_TOKEN = "-";

    /** 布尔点位在 {@code DECIMAL} 阈值列里的表示（{@code true}）。 */
    public static final BigDecimal BOOLEAN_TRUE = BigDecimal.ONE;

    /** 布尔点位在 {@code DECIMAL} 阈值列里的表示（{@code false}）。 */
    public static final BigDecimal BOOLEAN_FALSE = BigDecimal.ZERO;

    private AlertRules() {
    }

    /**
     * 规则实例的去重键：{@code ruleId:deviceId:propertyId}（{@code propertyId} 为空时用
     * {@link #NO_POINT_TOKEN}）。
     *
     * <p>规范化是必要的：它同时进 {@code dedup_key}（留痕）与 {@code active_dedup_key}（唯一键），
     * 两处必须逐字一致，否则「去重生效与否」会随拼接点而漂移。</p>
     *
     * @param ruleId     规则 ID
     * @param deviceId   设备 ID
     * @param propertyId 点位标识（可空）
     * @return 去重键
     */
    public static String dedupKey(long ruleId, long deviceId, String propertyId) {
        return ruleId + DEDUP_SEPARATOR + deviceId + DEDUP_SEPARATOR + normalizePropertyId(propertyId);
    }

    /**
     * 断档映射实例的去重键：{@code OUTAGE:deviceId:outageEventId}。
     *
     * <p>键里带**事件 ID**（而不是只带设备）是「同一事件不重复建实例、新事件可以新建实例」的实现方式：
     * 同一个 {@code outage_event} 在活动期内只可能有一条告警实例；该事件闭合后再次断档是**新事件**，
     * 应当新建一条实例（这与「清除 ≠ 删除」不冲突）。</p>
     *
     * @param deviceId     设备 ID
     * @param outageEventId 断档事件 ID
     * @return 去重键
     */
    public static String outageDedupKey(long deviceId, long outageEventId) {
        return OUTAGE_DEDUP_PREFIX + DEDUP_SEPARATOR + deviceId + DEDUP_SEPARATOR + outageEventId;
    }

    /**
     * 点位标识归一（空白视同为空 ⇒ 用 {@link #NO_POINT_TOKEN}）。
     *
     * @param propertyId 点位标识
     * @return 归一后的形态
     */
    public static String normalizePropertyId(String propertyId) {
        return propertyId == null || propertyId.isBlank() ? NO_POINT_TOKEN : propertyId;
    }

    /**
     * 判定单次读数的结果（**唯一判定入口**，评估器不得另写一套 if）。
     *
     * @param condition       点位条件（可空；空表示条件非法）
     * @param rawValue        读数原值（字符串，可空）
     * @param quality         质量码（可空）
     * @param ts              读数时刻（epoch 毫秒；可空）
     * @param now             当前时刻（epoch 毫秒）
     * @param stalenessTtlMs  陈旧判定阈值（毫秒；{@code <= 0} 表示不做陈旧判定——只允许配置校验通过后出现）
     * @return 判定结果
     */
    public static AlertValueVerdict verdict(IotAlertRulePoint condition, String rawValue, String quality,
                                            Long ts, long now, long stalenessTtlMs) {
        // ① 条件本身是否合法：非法条件与数据无关，先判它就对了（否则规则配错会被伪装成「设备数据有问题」）
        if (condition == null) {
            return AlertValueVerdict.INVALID_CONDITION;
        }
        AlertOperator operator = AlertOperator.of(condition.getOperator());
        AlertValueType valueType = AlertValueType.of(condition.getValueType());
        if (operator == null || valueType == null || condition.getThreshold() == null) {
            return AlertValueVerdict.INVALID_CONDITION;
        }
        if (valueType == AlertValueType.BOOLEAN && operator != AlertOperator.EQ
            && operator != AlertOperator.NE) {
            // 布尔点位只允许「等于/不等于」：把 true 当 1 去做大小比较是隐式转换，会静默产生没人能解释的告警
            return AlertValueVerdict.INVALID_CONDITION;
        }
        // ② 显式数值化：解析失败即不可判定（不得当 0、不得当越界）
        BigDecimal value = parseValue(valueType, rawValue);
        if (value == null) {
            return AlertValueVerdict.NON_NUMERIC;
        }
        // ③ 质量位：非 GOOD 不可判定（否则坏质量会被算成「恢复正常」而错误 resolve）
        if (quality == null || !QUALITY_GOOD.equalsIgnoreCase(quality.trim())) {
            return AlertValueVerdict.BAD_QUALITY;
        }
        // ④ 时效：陈旧不可判定，且**陈旧不等于恢复**
        if (ts == null) {
            return AlertValueVerdict.STALE;
        }
        if (stalenessTtlMs > 0 && now - ts > stalenessTtlMs) {
            return AlertValueVerdict.STALE;
        }
        // ⑤ 比较
        return conditionHolds(operator, condition.getThreshold(), value)
            ? AlertValueVerdict.OUT_OF_RANGE : AlertValueVerdict.IN_RANGE;
    }

    /**
     * 触发条件是否成立（严格按比较符语义，边界值由用例逐条钉住：{@code GT 30} 读到 {@code 30} 不触发）。
     *
     * @param operator  比较符
     * @param threshold 阈值（非空）
     * @param value     判定值（非空）
     * @return 成立返回 {@code true}
     */
    public static boolean conditionHolds(AlertOperator operator, BigDecimal threshold, BigDecimal value) {
        int comparison = value.compareTo(threshold);
        return switch (operator) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
        };
    }

    /**
     * 恢复条件是否成立（设计 §2.3 的**回差**）。
     *
     * <p>不配 {@code deadband} 时恢复条件就是「不满足触发条件」；配了则要求**反向越过回差**。
     * 只防触发侧抖动、不防恢复侧抖动的话，会在阈值附近产生「触发/恢复/触发」的通知风暴。</p>
     *
     * <ul>
     *   <li>上界类（{@code GT}/{@code GTE}）：触发门槛往回退 {@code deadband} ⇒ 恢复门槛 = 阈值 − 回差；</li>
     *   <li>下界类（{@code LT}/{@code LTE}）：恢复门槛 = 阈值 + 回差；</li>
     *   <li>{@code EQ}：偏离超过回差才恢复（回差 0 时等价于「不等于即恢复」）；</li>
     *   <li>{@code NE}：回到等于即恢复（回差对「不等于」没有可退的方向，故忽略）。</li>
     * </ul>
     *
     * @param condition 点位条件（非空，且已通过合法性校验）
     * @param value     判定值（非空）
     * @return 满足恢复条件返回 {@code true}
     */
    public static boolean recovered(IotAlertRulePoint condition, BigDecimal value) {
        AlertOperator operator = AlertOperator.of(condition.getOperator());
        if (operator == null || condition.getThreshold() == null || value == null) {
            // 条件非法时**不恢复**：宁可把一条配错的规则挂在那里被人看见，也不能让它静默把告警清掉
            return false;
        }
        BigDecimal threshold = condition.getThreshold();
        BigDecimal deadband = condition.getDeadband();
        boolean hasDeadband = deadband != null && deadband.signum() > 0;
        if (!hasDeadband) {
            return !conditionHolds(operator, threshold, value);
        }
        return switch (operator) {
            case GT -> value.compareTo(threshold.subtract(deadband)) < 0;
            case GTE -> value.compareTo(threshold.subtract(deadband)) <= 0;
            case LT -> value.compareTo(threshold.add(deadband)) > 0;
            case LTE -> value.compareTo(threshold.add(deadband)) >= 0;
            case EQ -> value.subtract(threshold).abs().compareTo(deadband) > 0;
            case NE -> value.compareTo(threshold) == 0;
        };
    }

    /**
     * 按比较域解析读数（**唯一解析入口**）。
     *
     * @param valueType 比较域（非空）
     * @param rawValue  读数原值
     * @return 解析结果；无法解析返回 {@code null}
     */
    public static BigDecimal parseValue(AlertValueType valueType, String rawValue) {
        if (valueType == AlertValueType.BOOLEAN) {
            Boolean parsed = parseBoolean(rawValue);
            return parsed == null ? null : (parsed ? BOOLEAN_TRUE : BOOLEAN_FALSE);
        }
        return parseNumeric(rawValue);
    }

    /**
     * 解析数值（十进制；不接受 {@code NaN}/{@code Infinity} 这类 Java 双精度特例写法）。
     *
     * <p>用 {@link BigDecimal} 而不是 {@code Double.parseDouble}：后者会把 {@code 0.1} 变成二进制近似值，
     * 与 {@code DECIMAL} 阈值比较时在边界上给出反直觉结果；并且 {@code Double.parseDouble("NaN")} 是合法的，
     * 而 {@code NaN} 参与任何比较都是 {@code false}，会被静默当成「未越界」（漏报）。</p>
     *
     * @param rawValue 读数原值
     * @return 数值；解析失败返回 {@code null}
     */
    public static BigDecimal parseNumeric(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            // 解析失败是**正常的业务结果**（点位文本值 / 脏数据），由调用方计入不可判定指标；
            // 这里不记日志刷屏（评估器每轮都可能遇到），也不吞异常栈以外的信息
            return null;
        }
    }

    /**
     * 解析布尔（{@code true/false/1/0}，忽略大小写与空白）。
     *
     * @param rawValue 读数原值
     * @return 布尔；无法解析返回 {@code null}
     */
    public static Boolean parseBoolean(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 触发时的**阈值快照**（规则改了阈值后仍能解释当时为何报警）。
     *
     * @param condition 点位条件
     * @return 快照文本（条件非法时返回 {@code null}）
     */
    public static String thresholdSnapshot(IotAlertRulePoint condition) {
        if (condition == null) {
            return null;
        }
        AlertOperator operator = AlertOperator.of(condition.getOperator());
        if (operator == null || condition.getThreshold() == null) {
            return null;
        }
        StringBuilder snapshot = new StringBuilder(operator.symbol()).append(' ')
            .append(condition.getThreshold().stripTrailingZeros().toPlainString());
        if (condition.getDeadband() != null && condition.getDeadband().signum() > 0) {
            snapshot.append("（回差 ").append(condition.getDeadband().stripTrailingZeros().toPlainString())
                .append('）');
        }
        return snapshot.toString();
    }
}
