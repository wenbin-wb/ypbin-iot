/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 一条完成「点位映射」的读数（协议地址已解析为平台属性）。
 *
 * <p>这是 §5.1「协议解析 → 点位映射 → DataSink 入队」中**点位映射之后**的产物：
 * 协议栈按地址回调，本平台把它翻译成「哪个属性、什么值、质量如何、何时采到」。
 * M-2 的数据面（微批出口 → EMQX → business 落 IoTDB/Redis）从这里接。</p>
 *
 * @param deviceId   设备主键（字符串形式，与协议栈一致）
 * @param propertyId 属性主键
 * @param value      已应用缩放/偏移后的值（原始类型无法线性变换时保持原样）
 * @param quality    质量码（GOOD 才算「有效数据」，断档判定依赖它）
 * @param timestamp  采集时刻（设备时钟不可信，用框架给出的时刻）
 * @author wenbin
 * @since 2026-09-21
 */
public record AccessReading(
        String deviceId,
        String propertyId,
        Object value,
        String quality,
        Instant timestamp) {

    /** 质量码 GOOD。 */
    public static final String QUALITY_GOOD = "GOOD";

    /** 线性缩放用的十进制标度。 */
    public static final int SCALE = 6;

    /**
     * 应用线性缩放：{@code value * scaleFactor + offsetValue}。
     *
     * <p>只在原值为数值且确实配了缩放/偏移时变换；否则原样返回——**不猜测、不做类型兜底转换**，
     * 避免把「配置错了」伪装成「值算过了」。</p>
     *
     * @param raw         原始值（协议栈给的对象）
     * @param scaleFactor 缩放系数（可空）
     * @param offsetValue 偏移（可空）
     * @return 变换后的值；无法变换时返回原始值
     */
    public static Object applyScale(Object raw, BigDecimal scaleFactor, BigDecimal offsetValue) {
        if (raw == null || (scaleFactor == null && offsetValue == null)) {
            return raw;
        }
        BigDecimal numeric = toDecimal(raw);
        if (numeric == null) {
            return raw;
        }
        BigDecimal scaled = scaleFactor == null ? numeric : numeric.multiply(scaleFactor);
        BigDecimal shifted = offsetValue == null ? scaled : scaled.add(offsetValue);
        return shifted.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal toDecimal(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    /** 是否为「有效数据」（断档判定只看 GOOD）。 */
    public boolean isGood() {
        return QUALITY_GOOD.equals(quality);
    }
}
