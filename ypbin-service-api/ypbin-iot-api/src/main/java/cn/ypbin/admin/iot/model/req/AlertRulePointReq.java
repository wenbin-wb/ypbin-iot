/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 点位条件保存请求（设计 §2.1 表 B）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRulePointReq {

    /** 点位标识（属性标识）。 */
    @NotBlank(message = "点位不能为空")
    @Size(max = 64, message = "点位标识不能超过 64 个字符")
    private String propertyId;

    /** 比较符码：GT / GTE / LT / LTE / EQ / NE。 */
    @NotBlank(message = "比较符不能为空")
    private String operator;

    /** 阈值（字符串接收后显式解析为 DECIMAL；布尔点位用 1/0）。 */
    @NotBlank(message = "阈值不能为空")
    @Size(max = 40, message = "阈值过长")
    private String threshold;

    /** 比较域码：NUMERIC（默认）/ BOOLEAN。 */
    private String valueType;

    /** 回差（可空；用于抑制阈值附近的反复触发/恢复）。 */
    @Size(max = 40, message = "回差过长")
    private String deadband;
}
