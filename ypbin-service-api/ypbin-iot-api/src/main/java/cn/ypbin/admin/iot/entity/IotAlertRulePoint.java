/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 规则的点位条件行（设计 §2.1 表 B）。
 *
 * <p><b>为什么阈值用 {@code DECIMAL} 而不是 {@code DOUBLE}</b>：阈值是**配置**，不能有二进制浮点误差
 * ——{@code 0.1} 在 double 里根本不是 0.1，同一份配置在两处比较可能给出不同结果（设计 §2.1 表 B）。</p>
 *
 * <p>{@code deadband}（回差/滞回）是防抖的**另一半**：只防触发侧抖动、不防恢复侧抖动的话，
 * 会在阈值附近产生「触发/恢复/触发」的通知风暴（设计 §2.3，借鉴 EMQX 的 high/low watermark）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
@TableName("iot_alert_rule_point")
public class IotAlertRulePoint extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 指向 {@code iot_alert_rule.id}。 */
    private Long ruleId;

    /**
     * 点位标识（属性标识／历史主键字符串形态）。
     *
     * <p>与既有 {@code PointMappingIndex} 的解析口径一致——本平台点位存在过渡期双形态
     * （见 {@code TimeSeriesQueryService.resolveCoordinateForms}），评估器读 Redis 最新值时会先经
     * {@code PointMappingIndex} 归一。</p>
     */
    private String propertyId;

    /** 比较符码（{@link cn.ypbin.admin.iot.enums.AlertOperator#getCode()}）。 */
    private String operator;

    /** 阈值（{@code DECIMAL(24,6)}）。 */
    private BigDecimal threshold;

    /** 比较域码（{@link cn.ypbin.admin.iot.enums.AlertValueType#getCode()}）：{@code NUMERIC} / {@code BOOLEAN}。 */
    private String valueType;

    /** 回差（滞回）：恢复门槛比触发门槛往回退这么多；可空=不启用回差。 */
    private BigDecimal deadband;
}
