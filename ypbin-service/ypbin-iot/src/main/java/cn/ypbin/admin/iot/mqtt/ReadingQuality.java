/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mqtt;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 读数质量码白名单（与协议栈 {@code Quality.name()} 对齐）。
 *
 * <p><b>为什么要在这里再列一遍</b>：{@code ReadingObservationDto.quality} 只约束「非空」，
 * 取值域写在注释里。MQTT 入站是**设备直连**的入口（不像 HTTP 通道那样只由受信的 access 调用），
 * 若不在入口把取值域钉死，任何字符串都会进活性/时序链路：{@code quality=good}（小写）不会命中
 * {@code AvailabilityRules.QUALITY_GOOD} 的等值判定，结果是「设备一直在报、可用率一直是 0」——
 * 这类故障从数据上看不出错，只在口径上错。因此入口按枚举白名单拒绝。</p>
 *
 * <p>iot 服务**不依赖**协议栈（见 {@code AvailabilityRules.QUALITY_GOOD} 的同款说明），
 * 故这里是本模块的唯一实现，不复制第三份。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public enum ReadingQuality {

    /** 有效数据。 */
    GOOD("GOOD", "有效"),

    /** 不确定。 */
    UNCERTAIN("UNCERTAIN", "不确定"),

    /** 坏值。 */
    BAD("BAD", "坏值"),

    /** 陈旧值（设备在线但数据过期）。 */
    STALE("STALE", "陈旧"),

    /** 未连接。 */
    NOT_CONNECTED("NOT_CONNECTED", "未连接"),

    /** 配置错误。 */
    CONFIG_ERROR("CONFIG_ERROR", "配置错误");

    /** 合法取值列表（供错误提示与文档生成，避免两处手抄）。 */
    public static final String ALLOWED = "GOOD|UNCERTAIN|BAD|STALE|NOT_CONNECTED|CONFIG_ERROR";

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z_]{1,32}");

    private final String code;

    private final String desc;

    ReadingQuality(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 取值码。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 判断质量码是否合法（{@code null}/空白/大小写不符一律非法）。
     *
     * @param code 质量码
     * @return 合法返回 {@code true}
     */
    public static boolean isValid(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            return false;
        }
        for (ReadingQuality quality : values()) {
            if (quality.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
