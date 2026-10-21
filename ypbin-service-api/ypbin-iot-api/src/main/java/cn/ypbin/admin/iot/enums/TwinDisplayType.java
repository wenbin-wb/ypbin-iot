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

/**
 * 数字孪生属性绑定的展示形态。
 *
 * <p>数据库与接口存/传 {@code code}（value | gauge | switch | color | sparkline）。
 * 形态只决定「运行态怎么画这个值」，不参与取值换算——缩放/偏移仍由采集域的
 * {@code iot_point_mapping} 承担，这里拿到的已经是工程值。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinDisplayType {

    /** 纯文本数值（锚点旁那一行常显文本） */
    VALUE("value", "数值"),

    /** 仪表盘（用物模型 min/max，缺省时由前端自适应量程） */
    GAUGE("gauge", "仪表盘"),

    /** 开关（布尔真值表由前端裁决，无法判定时显示「未知」，不猜） */
    SWITCH("switch", "开关"),

    /** 颜色映射（按枚举值或数值区间着色） */
    COLOR("color", "颜色"),

    /** 迷你折线（前端按实时推送自建最近 N 点） */
    SPARKLINE("sparkline", "迷你折线");

    private final String code;
    private final String desc;

    TwinDisplayType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 展示形态码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinDisplayType of(String code) {
        for (TwinDisplayType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
