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

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 运行总览（分析页 / 工作台首屏共用的一份聚合响应）。
 *
 * <p>字段名与运营总览页契约逐一同名（不做改名映射）。所有计数使用 {@code Integer} / {@code Double}
 * 而不是 {@code Long}：全局 Jackson 把 {@code Long} 序列化为字符串，计数器一旦变成字符串，前端图表
 * 会拿到 {@code "2148"} 这种值而静默画不出趋势/饼图。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@Getter
@Setter
public class DashboardOverviewResp {

    /** 设备总数（租户内全部未删除设备，含停用）。 */
    private Integer deviceTotal;

    /** 在线设备数（{@code online_status = online}）。 */
    private Integer deviceOnline;

    /** 离线设备数（{@code online_status = offline}；未知态不计入在线率分母）。 */
    private Integer deviceOffline;

    /** 停用（禁用采集）设备数。 */
    private Integer deviceDisabled;

    /** 在线率（0-100，口径为 {@code 在线 / (在线 + 离线)}；无有效分母时为 0）。 */
    private Double onlineRate;

    /** 产品数。 */
    private Integer productTotal;

    /** 物模型点位（属性）总数。 */
    private Integer pointTotal;

    /** 近 7 日（含今日）新增接入设备数。 */
    private Integer newDevicesWeek;

    /** 今日消息总量（上行 + 下行）。 */
    private Integer messageToday;

    /** 今日上行消息量（MQTT 入站回执的 {@code received_count} 求和，重投不重复计数）。 */
    private Integer messageUplinkToday;

    /** 今日下行消息量（命令实例条数）。 */
    private Integer messageDownlinkToday;

    /** 待处理告警数（{@code resolved_ts IS NULL}：含 PENDING / FIRING / ACKED）。 */
    private Integer alertPending;

    /** 今日新增告警数。 */
    private Integer alertToday;

    /** 今日已恢复告警数。 */
    private Integer alertResolvedToday;

    /** 近 30 日消息趋势（按日升序，缺数据的日期补 0）。 */
    private List<MessageTrendPoint> messageTrend = List.of();

    /** 近 14 日在线趋势；当前无可信历史快照来源，恒为空数组（详见服务实现说明）。 */
    private List<OnlineTrendPoint> onlineTrend = List.of();

    /** 近 30 日告警趋势（按日升序；critical/warning/info 三级分别补齐为 0）。 */
    private List<AlertTrendPoint> alertTrend = List.of();

    /** 近 14 日设备增长趋势（按日升序，缺数据日期补 0；{@code added} = 当日新增设备数）。 */
    private List<DeviceGrowthTrendPoint> deviceGrowthTrend = List.of();

    /** 接入协议分布（按设备数降序）。 */
    private List<NameValue> protocolDistribution = List.of();

    /** 未恢复告警的级别分布（按数量降序；name 为小写级别码，供总览页前端拼 i18n key）。 */
    private List<NameValue> severityDistribution = List.of();

    /** 设备数 TOP 5 产品（按设备数降序）。 */
    private List<NameValue> productTop = List.of();

    /** 近 30 天告警数 TOP 5 规则（按告警数降序；不含断档类 ruleId=0）。 */
    private List<NameValue> alertRuleTop = List.of();

    /** 最近 10 条告警（按触发时间倒序）。 */
    private List<RecentAlert> recentAlerts = List.of();

    /**
     * 通用「名称-数值」分布项。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class NameValue {

        /** 名称（协议码 / 小写告警级别码 / 产品名）。 */
        private String name;

        /** 数量。 */
        private Integer value;
    }

    /**
     * 按日消息量趋势点。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class MessageTrendPoint {

        /** 日期（yyyy-MM-dd）。 */
        private String date;

        /** 当日上行消息量。 */
        private Integer uplink;

        /** 当日下行消息量。 */
        private Integer downlink;
    }

    /**
     * 按日告警趋势点（新建告警，按级别透视）。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class AlertTrendPoint {

        /** 日期（yyyy-MM-dd）。 */
        private String date;

        /** 当日新建的严重告警数。 */
        private Integer critical;

        /** 当日新建的警告告警数。 */
        private Integer warning;

        /** 当日新建的提示告警数。 */
        private Integer info;
    }

    /**
     * 按日设备增长趋势点。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class DeviceGrowthTrendPoint {

        /** 日期（yyyy-MM-dd）。 */
        private String date;

        /** 当日新增设备数。 */
        private Integer added;
    }

    /**
     * 按日在线/离线趋势点。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class OnlineTrendPoint {

        /** 日期（yyyy-MM-dd）。 */
        private String date;

        /** 当日在线设备数。 */
        private Integer online;

        /** 当日离线设备数。 */
        private Integer offline;
    }

    /**
     * 最近告警条目。
     *
     * @author wenbin
     * @since 2026-10-14
     */
    @Getter
    @Setter
    public static class RecentAlert {

        /** 告警实例 ID（字符串，避免 Long 全局序列化口径差异）。 */
        private String id;

        /** 设备名（设备不可见时为空）。 */
        private String deviceName;

        /** 规则名（断档类或规则不可见时为空）。 */
        private String ruleName;

        /** 级别码（小写；与告警中心同一语义，总览页直接按 page.iot.alert.severity.* 翻译）。 */
        private String severity;

        /** 状态码（小写；与告警中心同一语义，总览页直接按 page.iot.alert.state.* 翻译）。 */
        private String state;

        /** 触发时间（yyyy-MM-dd HH:mm；取 startTs，空则回落 createTime）。 */
        private String triggeredAt;
    }
}
