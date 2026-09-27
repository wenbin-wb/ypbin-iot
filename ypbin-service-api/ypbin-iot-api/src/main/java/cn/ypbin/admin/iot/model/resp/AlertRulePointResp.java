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
 * 告警点位条件视图。
 *
 * <p>{@code threshold} / {@code deadband} 用**字符串**输出：它们在后端是 {@code DECIMAL}
 * （{@code write-big-number-as-string} 下本来就会变成字符串），显式声明为字符串可以让前端不必猜
 * 「这次拿到的是数字还是字符串」，也避免把 {@code 0.1} 变成二进制近似值后再显示。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRulePointResp {

    /** 条件行 ID。 */
    private Long id;

    /** 点位标识。 */
    private String propertyId;

    /** 比较符码。 */
    private String operator;

    /** 阈值（字符串形态的十进制数）。 */
    private String threshold;

    /** 比较域码。 */
    private String valueType;

    /** 回差（字符串形态；可空）。 */
    private String deadband;
}
