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

import cn.ypbin.admin.iot.enums.AlertOperator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 条件组快照的**纯编解码**（2026-10-15 真 AND 语义配套）。
 *
 * <p><b>为什么需要它</b>：真 AND 之后「一条规则 × 一台设备」只产生**一条**实例，而触发时到底每个条件
 * 读到了多少、各自判成什么，是用户最需要看到的信息（否则「为什么这条告警响了」无法解释）。这些信息必须
 * 能随实例落库、并出现在通知正文里。实例表已有 {@code trigger_value}（只装得下一个值）与
 * {@code threshold_snapshot}（只装得下一个阈值），故新增
 * {@link cn.ypbin.admin.iot.entity.IotAlertInstance#getConditionSnapshot()} 承载整组快照，
 * 本类负责它的**唯一**编码与解码。</p>
 *
 * <p><b>格式（面向机器可解析、同时人也能读）</b>：每行一个条件，行内 5 个字段以 {@code |} 分隔，
 * 行间以 {@code ;} 连接：</p>
 * <pre>
 * propertyId|operatorCode|threshold|verdictCode|value
 * 例：temperature|GT|30|OUT_OF_RANGE|85.5;humidity|LT|20|IN_RANGE|25
 * </pre>
 *
 * <p><b>为什么 {@code value} 放最后</b>：读数原值是**设备上报的任意字符串**（可能含 {@code |} 或 {@code ;}），
 * 把它放在最后一个字段可以让「按前 4 个分隔符切分」永远拿到正确的字段边界；同时编码时对每个字段做
 * {@link #sanitize(String)}（分隔符与控制字符替换为空格）——快照是**平台自产的展示文本**，
 * 清洗不会影响任何判定（判定用的是实时读数，不是快照）。</p>
 *
 * <p><b>长度有上界且截断显式</b>：列宽 {@code VARCHAR(4000)}，条件行数上限 {@code max-points-per-rule}
 * （默认 50）。超长时按**整行**截断并追加 {@code ;…(+N)} 说明，绝不静默丢弃（与
 * {@code iot_mqtt_ingest_receipt.discard_detail} 的 {@code ,...(+N)} 同一形态）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public final class AlertConditionSnapshot {

    /** 行内字段分隔符。 */
    static final String FIELD_SEPARATOR = "|";

    /** 行分隔符。 */
    static final String ROW_SEPARATOR = ";";

    /** 截断标记前缀。 */
    static final String TRUNCATION_PREFIX = "…";

    /** 快照列宽（与 {@code iot_alert_instance.condition_snapshot} 的 DDL 一致）。 */
    public static final int MAX_LENGTH = 4000;

    /** 单行字段数。 */
    private static final int FIELD_COUNT = 5;

    private AlertConditionSnapshot() {
    }

    /**
     * 一个条件的快照行（不可变）。
     *
     * @param propertyId   点位标识（编码时已清洗）
     * @param operatorCode 比较符码（未知时为空串）
     * @param threshold    阈值（可空：条件非法时为空）
     * @param verdict      判定结果
     * @param value        读数原值（可空：无最新值时为空）
     */
    public record ConditionReading(String propertyId, String operatorCode, BigDecimal threshold,
                                   AlertValueVerdict verdict, String value) {

        /**
         * 人类可读的一行（通知正文与页面展示用）。
         *
         * @return 形如 {@code temperature > 30，读到 85.5（越界）}
         */
        public String describe() {
            AlertOperator operator = AlertOperator.of(operatorCode);
            String symbol = operator == null ? orDash(operatorCode) : operator.symbol();
            String thresholdText = threshold == null ? "-" : threshold.stripTrailingZeros().toPlainString();
            String verdictText = verdict == null ? "不可判定" : verdict.getDesc();
            return orDash(propertyId) + " " + symbol + " " + thresholdText + "，读到 "
                + orDash(value) + "（" + verdictText + "）";
        }

        private static String orDash(String raw) {
            return raw == null || raw.isBlank() ? "-" : raw;
        }
    }

    /**
     * 编码条件组快照（长度上界 {@link #MAX_LENGTH}）。
     *
     * @param readings 条件快照行（可为空）
     * @return 快照文本；无行时返回空串（**不返回 null**）
     */
    public static String encode(List<ConditionReading> readings) {
        return encode(readings, MAX_LENGTH);
    }

    /**
     * 编码条件组快照（指定长度上界，便于用例钉住截断形态）。
     *
     * @param readings  条件快照行（可为空）
     * @param maxLength 长度上界（非正数时取 1，保证至少能放下截断标记）
     * @return 快照文本；无行时返回空串
     */
    public static String encode(List<ConditionReading> readings, int maxLength) {
        if (readings == null || readings.isEmpty()) {
            return "";
        }
        int limit = Math.max(maxLength, 1);
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        for (ConditionReading reading : readings) {
            String row = encodeRow(reading);
            int extra = (builder.length() == 0 ? 0 : ROW_SEPARATOR.length()) + row.length();
            if (builder.length() + extra > limit) {
                break;
            }
            if (builder.length() > 0) {
                builder.append(ROW_SEPARATOR);
            }
            builder.append(row);
            appended++;
        }
        int omitted = readings.size() - appended;
        if (omitted > 0) {
            String marker = ROW_SEPARATOR + TRUNCATION_PREFIX + "(+" + omitted + ")";
            while (builder.length() > 0 && builder.length() + marker.length() > limit) {
                int cut = builder.lastIndexOf(ROW_SEPARATOR);
                builder.setLength(Math.max(cut, 0));
            }
            builder.append(marker);
        }
        return builder.toString();
    }

    /**
     * 解码条件组快照（**宽容**：畸形行跳过、不抛异常——快照是展示数据，不该让页面或投递因它失败）。
     *
     * @param snapshot 快照文本（可空）
     * @return 条件快照行（保序）；无内容时返回空列表
     */
    public static List<ConditionReading> decode(String snapshot) {
        List<ConditionReading> readings = new ArrayList<>();
        if (snapshot == null || snapshot.isBlank()) {
            return readings;
        }
        for (String row : snapshot.split(ROW_SEPARATOR)) {
            if (row.isBlank() || row.startsWith(TRUNCATION_PREFIX)) {
                continue;
            }
            // 限 5 段：value 是最后一段，自身含分隔符也不会错位（编码侧已清洗，这里是第二道防线）
            String[] fields = row.split("\\|", FIELD_COUNT);
            if (fields.length != FIELD_COUNT) {
                continue;
            }
            readings.add(new ConditionReading(fields[0], fields[1], parseThreshold(fields[2]),
                AlertValueVerdict.of(fields[3]), fields[4]));
        }
        return readings;
    }

    /** 单行编码（含清洗）。 */
    private static String encodeRow(ConditionReading reading) {
        return sanitize(reading.propertyId()) + FIELD_SEPARATOR
            + sanitize(reading.operatorCode()) + FIELD_SEPARATOR
            + (reading.threshold() == null ? "" : reading.threshold().stripTrailingZeros().toPlainString())
            + FIELD_SEPARATOR + sanitize(reading.verdict() == null ? null : reading.verdict().getCode())
            + FIELD_SEPARATOR + sanitize(reading.value());
    }

    /** 阈值解析：畸形返回 {@code null}（不猜值）。 */
    private static BigDecimal parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            // 快照里的阈值来自平台自产编码，解析失败只可能是数据被人为改动 ⇒ 返回 null（展示为 -），不抛
            return null;
        }
    }

    /**
     * 清洗字段：分隔符与控制字符替换为空格（换行尤其危险：伪造通知正文的多行结构）。
     *
     * @param raw 原值
     * @return 清洗结果（非 null）
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '|' || ch == ';' || Character.isISOControl(ch)) {
                out.append(' ');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
