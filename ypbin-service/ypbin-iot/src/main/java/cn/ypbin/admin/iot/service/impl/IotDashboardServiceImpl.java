/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.alert.AlertRules;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.enums.OnlineStatus;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.MessageTrendPoint;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.NameValue;
import cn.ypbin.admin.iot.model.resp.DashboardOverviewResp.RecentAlert;
import cn.ypbin.admin.iot.service.IotDashboardService;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * IoT 运行总览服务实现。
 *
 * <p><b>查询纪律</b>：全部统计都是一次性聚合/计数查询（{@code selectCount} / {@code selectMaps} +
 * {@code QueryWrapper} 分组），循环只做内存合并与名称拼装，**循环体内不碰数据库**；关联名称
 * （设备/规则/产品）按 ID 去重后各一次批量查询。不新增 Mapper 注解 SQL 或 XML。</p>
 *
 * <p><b>字段口径</b>：
 * <ul>
 *   <li>设备：总数含停用；在线/离线取 {@code online_status}（unknown 不进在线率分母）；
 *       停用取 {@code status = 0}；在线率 = 在线 / (在线 + 离线)，保留 1 位小数。</li>
 *   <li>点位数 {@code pointTotal}：物模型属性（{@code iot_property}）行数，即产品物模型里定义的点位总数。</li>
 *   <li>消息：上行取 {@code iot_mqtt_ingest_receipt.received_count} 求和（同 requestId 的重投命中唯一键，
 *       不会重复计）；下行取 {@code iot_command_instance} 条数；今日窗口为
 *       {@code [今日 00:00, 明日 00:00)}，按数据库 {@code create_time}。</li>
 *   <li>告警：待处理 = {@code resolved_ts IS NULL}；今日新增按 {@code create_time}；
 *       今日恢复按 {@code resolved_ts}；级别分布取未恢复告警的 {@code severity} 分组。</li>
 *   <li>级别/状态码在总览响应里转成<b>小写</b>（{@code critical}/{@code firing}）：总览页前端直接以
 *       {@code page.iot.alert.severity.*} / {@code page.iot.alert.state.*} 拼 i18n key，键都是小写；
 *       告警中心接口仍保持库内大写码值，两处不互相改动。</li>
 *   <li>趋势按 {@code DATE(create_time)} 分组，近 30 天升序、缺失日期补 0；MySQL 的
 *       {@code DATE()} 使用数据库时区，与应用 JVM 时区的差异只在跨时区部署时出现（本仓部署同区）。</li>
 * </ul>
 *
 * <p><b>为什么 {@code onlineTrend} 返回空数组</b>：当前没有任何可信的「逐日在线/离线快照」数据源——
 * {@code iot_device.online_status} 只是当下的覆盖值，{@code outage_event} 只表达断档事件，用
 * 「当天有断档的设备数」冒充离线数会把停用、未知、从未接入的设备算错（即编造趋势）。因此这里不猜测，
 * 返回空数组并由前端展示空态；将来若落库在线快照表，再在此补充真实口径。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@Service
@RequiredArgsConstructor
public class IotDashboardServiceImpl implements IotDashboardService {

    /** 消息趋势窗口（天，含今日）。 */
    private static final int MESSAGE_TREND_DAYS = 30;

    /** 新增设备窗口（天，含今日）。 */
    private static final int NEW_DEVICE_WINDOW_DAYS = 7;

    /** 产品 TOP 数量。 */
    private static final int PRODUCT_TOP_LIMIT = 5;

    /** 最近告警条数。 */
    private static final int RECENT_ALERT_LIMIT = 10;

    /** 触发时间展示格式（契约固定为 yyyy-MM-dd HH:mm）。 */
    private static final DateTimeFormatter TRIGGERED_AT_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final IotDeviceMapper iotDeviceMapper;

    private final IotProductMapper iotProductMapper;

    private final IotPropertyMapper iotPropertyMapper;

    private final IotMqttIngestReceiptMapper iotMqttIngestReceiptMapper;

    private final IotCommandInstanceMapper iotCommandInstanceMapper;

    private final IotAlertInstanceMapper iotAlertInstanceMapper;

    private final IotAlertRuleMapper iotAlertRuleMapper;

    @Override
    public DashboardOverviewResp overview() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        int deviceOnline = countToInt(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("online_status", OnlineStatus.ONLINE.getCode())));
        int deviceOffline = countToInt(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("online_status", OnlineStatus.OFFLINE.getCode())));
        int messageUplinkToday = sumUplinkReceivedCount(todayStart, tomorrowStart);
        int messageDownlinkToday = countCommandInstances(todayStart, tomorrowStart);

        DashboardOverviewResp resp = new DashboardOverviewResp();
        resp.setDeviceTotal(countToInt(iotDeviceMapper.selectCount(new QueryWrapper<>())));
        resp.setDeviceOnline(deviceOnline);
        resp.setDeviceOffline(deviceOffline);
        resp.setDeviceDisabled(countToInt(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("status", EntityStatus.DISABLED.getCode()))));
        resp.setOnlineRate(calcOnlineRate(deviceOnline, deviceOffline));
        resp.setProductTotal(countToInt(iotProductMapper.selectCount(new QueryWrapper<>())));
        resp.setPointTotal(countToInt(iotPropertyMapper.selectCount(new QueryWrapper<>())));
        resp.setNewDevicesWeek(countToInt(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .ge("create_time", today.minusDays(NEW_DEVICE_WINDOW_DAYS - 1L).atStartOfDay()))));
        resp.setMessageUplinkToday(messageUplinkToday);
        resp.setMessageDownlinkToday(messageDownlinkToday);
        resp.setMessageToday(Math.addExact(messageUplinkToday, messageDownlinkToday));
        resp.setAlertPending(countToInt(iotAlertInstanceMapper.selectCount(
            new QueryWrapper<IotAlertInstance>().isNull("resolved_ts"))));
        resp.setAlertToday(countToInt(iotAlertInstanceMapper.selectCount(new QueryWrapper<IotAlertInstance>()
            .ge("create_time", todayStart)
            .lt("create_time", tomorrowStart))));
        resp.setAlertResolvedToday(countToInt(iotAlertInstanceMapper.selectCount(
            new QueryWrapper<IotAlertInstance>()
                .ge("resolved_ts", todayStart)
                .lt("resolved_ts", tomorrowStart))));
        resp.setMessageTrend(loadMessageTrend(today));
        resp.setOnlineTrend(List.of());
        resp.setProtocolDistribution(loadProtocolDistribution());
        resp.setSeverityDistribution(loadSeverityDistribution());
        resp.setProductTop(loadProductTop());
        resp.setRecentAlerts(loadRecentAlerts());
        return resp;
    }

    /**
     * 近 30 日消息趋势：上/下行各一次分组聚合，再在内存里按日对齐并补 0。
     *
     * @param today 今日（趋势末位日期）
     * @return 按日升序的 30 个趋势点
     */
    private List<MessageTrendPoint> loadMessageTrend(LocalDate today) {
        LocalDate from = today.minusDays(MESSAGE_TREND_DAYS - 1L);
        Map<LocalDate, Integer> uplinkTotals = loadDailyTotals(iotMqttIngestReceiptMapper,
            "SUM(received_count) AS total", from);
        Map<LocalDate, Integer> downlinkTotals = loadDailyTotals(iotCommandInstanceMapper,
            "COUNT(*) AS total", from);

        List<MessageTrendPoint> points = new ArrayList<>(MESSAGE_TREND_DAYS);
        for (int offset = MESSAGE_TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            MessageTrendPoint point = new MessageTrendPoint();
            point.setDate(day.toString());
            point.setUplink(uplinkTotals.getOrDefault(day, 0));
            point.setDownlink(downlinkTotals.getOrDefault(day, 0));
            points.add(point);
        }
        return points;
    }

    /**
     * 按 {@code DATE(create_time)} 分组取每日合计。
     *
     * @param mapper          提供统计表的 Mapper
     * @param totalExpression 合计表达式（带 {@code AS total} 别名）
     * @param from            窗口起点（含）
     * @param <T>             实体类型
     * @return 日期 → 合计；无数据为空 Map
     */
    private <T> Map<LocalDate, Integer> loadDailyTotals(BaseMapper<T> mapper, String totalExpression,
                                                        LocalDate from) {
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(create_time) AS day", totalExpression)
            .ge("create_time", from.atStartOfDay())
            .groupBy("DATE(create_time)");
        List<Map<String, Object>> rows = mapper.selectMaps(wrapper);
        Map<LocalDate, Integer> totals = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return totals;
        }
        for (Map<String, Object> row : rows) {
            LocalDate day = toLocalDate(requireCell(row, "day"));
            // SUM 在“该分组内 received_count 全为 NULL”时结果是 NULL；MyBatis 对 Map 结果
            // 默认会省略 NULL 单元格，因此这里把“列缺失”按 0 处理（生产实测：只有 day 列、
            // total 被省略会抛“缺少列别名”，但把无消息的日子画成 0 才是正确语义）。
            Object totalCell = findCell(row, "total");
            int total = totalCell == null ? 0 : toInt(totalCell);
            totals.merge(day, total, (left, right) -> Math.addExact(left, right));
        }
        return totals;
    }

    /**
     * 今日上行消息量：MQTT 入站回执的 {@code received_count} 求和（重投命中去重键，不重复计）。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（不含）
     * @return 上行消息量
     */
    private int sumUplinkReceivedCount(LocalDateTime from, LocalDateTime to) {
        QueryWrapper<IotMqttIngestReceipt> wrapper = new QueryWrapper<>();
        wrapper.select("COALESCE(SUM(received_count), 0) AS total")
            .ge("create_time", from)
            .lt("create_time", to);
        List<Map<String, Object>> rows = iotMqttIngestReceiptMapper.selectMaps(wrapper);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return toInt(requireCell(rows.get(0), "total"));
    }

    /**
     * 今日下行消息量：命令实例条数。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（不含）
     * @return 下行消息量
     */
    private int countCommandInstances(LocalDateTime from, LocalDateTime to) {
        return countToInt(iotCommandInstanceMapper.selectCount(new QueryWrapper<IotCommandInstance>()
            .ge("create_time", from)
            .lt("create_time", to)));
    }

    /**
     * 接入协议分布（按设备数降序）。
     *
     * @return 协议码 → 设备数
     */
    private List<NameValue> loadProtocolDistribution() {
        QueryWrapper<IotDevice> wrapper = new QueryWrapper<>();
        wrapper.select("protocol AS name", "COUNT(*) AS value")
            .isNotNull("protocol")
            .ne("protocol", "")
            .groupBy("protocol");
        return toNameValues(iotDeviceMapper.selectMaps(wrapper), false);
    }

    /**
     * 未恢复告警的级别分布（按数量降序，与 {@code alertPending} 同口径）。
     *
     * @return 级别码 → 未恢复数量
     */
    private List<NameValue> loadSeverityDistribution() {
        QueryWrapper<IotAlertInstance> wrapper = new QueryWrapper<>();
        wrapper.select("severity AS name", "COUNT(*) AS value")
            .isNull("resolved_ts")
            .isNotNull("severity")
            .ne("severity", "")
            .groupBy("severity");
        // 总览页用 page.iot.alert.severity.<小写码> 拼 i18n key，故此处统一小写（告警中心接口不受影响）
        return toNameValues(iotAlertInstanceMapper.selectMaps(wrapper), true);
    }

    /**
     * 设备数 TOP 5 产品（按设备数降序，产品名一次批量解析）。
     *
     * @return 产品名 → 设备数；产品不可见时名称回落产品 ID（可见的兜底，不编造名称）
     */
    private List<NameValue> loadProductTop() {
        QueryWrapper<IotDevice> wrapper = new QueryWrapper<>();
        wrapper.select("product_id AS product_id", "COUNT(*) AS device_count")
            .isNotNull("product_id")
            .groupBy("product_id")
            .orderByDesc("COUNT(*)")
            .last("LIMIT " + PRODUCT_TOP_LIMIT);
        List<Map<String, Object>> rows = iotDeviceMapper.selectMaps(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = new ArrayList<>(rows.size());
        List<Integer> counts = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            productIds.add(toLong(requireCell(row, "product_id")));
            counts.add(toInt(requireCell(row, "device_count")));
        }
        Map<Long, String> productNames = loadProductNames(productIds.stream().distinct().toList());
        List<NameValue> items = new ArrayList<>(productIds.size());
        for (int index = 0; index < productIds.size(); index++) {
            Long productId = productIds.get(index);
            String name = productNames.get(productId);
            if (name == null || name.isBlank()) {
                // 产品已删除/不可见：回落到可见的产品 ID，避免图上出现 null 名称
                name = String.valueOf(productId);
            }
            NameValue item = new NameValue();
            item.setName(name);
            item.setValue(counts.get(index));
            items.add(item);
        }
        sortByValueDesc(items);
        return items;
    }

    /**
     * 最近 10 条告警：设备名与规则名按 ID 去重后各一次批量查询，不在循环里查库。
     *
     * @return 按触发时间倒序的最近告警
     */
    private List<RecentAlert> loadRecentAlerts() {
        QueryWrapper<IotAlertInstance> wrapper = new QueryWrapper<>();
        wrapper.select("id", "device_id", "rule_id", "severity", "state", "start_ts", "create_time")
            .orderByDesc("COALESCE(start_ts, create_time)")
            .last("LIMIT " + RECENT_ALERT_LIMIT);
        List<IotAlertInstance> instances = iotAlertInstanceMapper.selectList(wrapper);
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }

        Set<Long> deviceIds = new LinkedHashSet<>();
        Set<Long> ruleIds = new LinkedHashSet<>();
        for (IotAlertInstance instance : instances) {
            if (instance.getDeviceId() != null) {
                deviceIds.add(instance.getDeviceId());
            }
            if (instance.getRuleId() != null && instance.getRuleId() != AlertRules.RULE_ID_OUTAGE) {
                ruleIds.add(instance.getRuleId());
            }
        }
        Map<Long, String> deviceNames = loadDeviceNames(new ArrayList<>(deviceIds));
        Map<Long, String> ruleNames = loadRuleNames(new ArrayList<>(ruleIds));

        List<RecentAlert> items = new ArrayList<>(instances.size());
        for (IotAlertInstance instance : instances) {
            RecentAlert item = new RecentAlert();
            item.setId(instance.getId() == null ? null : String.valueOf(instance.getId()));
            item.setDeviceName(deviceNames.get(instance.getDeviceId()));
            boolean outage = instance.getRuleId() != null
                && instance.getRuleId() == AlertRules.RULE_ID_OUTAGE;
            item.setRuleName(outage ? null : ruleNames.get(instance.getRuleId()));
            // 与总览页前端 i18n key 对齐：小写码（告警中心接口仍用库内大写码）
            item.setSeverity(lowerCode(instance.getSeverity()));
            item.setState(lowerCode(instance.getState()));
            LocalDateTime triggeredAt = instance.getStartTs() != null
                ? instance.getStartTs() : instance.getCreateTime();
            item.setTriggeredAt(triggeredAt == null ? null : triggeredAt.format(TRIGGERED_AT_FORMATTER));
            items.add(item);
        }
        return items;
    }

    /**
     * 批量解析设备名（空 ID 集合短路，不把空集合传进 IN 查询）。
     *
     * @param deviceIds 设备 ID（已去重）
     * @return 设备 ID → 设备名（无名称时回落设备编码；两者皆空则跳过）
     */
    private Map<Long, String> loadDeviceNames(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (IotDevice device : iotDeviceMapper.selectByIds(deviceIds)) {
            if (device.getId() == null) {
                continue;
            }
            String name = device.getDeviceName();
            if (name == null || name.isBlank()) {
                name = device.getDeviceCode();
            }
            if (name != null && !name.isBlank()) {
                names.putIfAbsent(device.getId(), name);
            }
        }
        return names;
    }

    /**
     * 批量解析告警规则名（空 ID 集合短路；断档类实例的保留 ID 0 已在调用方排除）。
     *
     * @param ruleIds 规则 ID（已去重、已排除断档保留 ID）
     * @return 规则 ID → 规则名
     */
    private Map<Long, String> loadRuleNames(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (IotAlertRule rule : iotAlertRuleMapper.selectByIds(ruleIds)) {
            if (rule.getId() != null && rule.getRuleName() != null && !rule.getRuleName().isBlank()) {
                names.putIfAbsent(rule.getId(), rule.getRuleName());
            }
        }
        return names;
    }

    /**
     * 批量解析产品名（空 ID 集合短路）。
     *
     * @param productIds 产品 ID（已去重）
     * @return 产品 ID → 产品名（无名称时回落产品编码；两者皆空则跳过）
     */
    private Map<Long, String> loadProductNames(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (IotProduct product : iotProductMapper.selectByIds(productIds)) {
            if (product.getId() == null) {
                continue;
            }
            String name = product.getProductName();
            if (name == null || name.isBlank()) {
                name = product.getProductCode();
            }
            if (name != null && !name.isBlank()) {
                names.putIfAbsent(product.getId(), name);
            }
        }
        return names;
    }

    /**
     * 把 {@code selectMaps} 的「name/value」分组结果转成响应项并按数量降序。
     *
     * @param rows          分组行（列别名大小写/下划线形态不敏感）
     * @param lowerCaseName 是否把名称转小写（告警级别用于前端 i18n key，必须小写；协议码原样保留）
     * @return 分布项；无数据为空 List
     */
    private static List<NameValue> toNameValues(List<Map<String, Object>> rows, boolean lowerCaseName) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<NameValue> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(requireCell(row, "name"));
            NameValue item = new NameValue();
            item.setName(lowerCaseName ? name.toLowerCase(Locale.ROOT) : name);
            item.setValue(toInt(requireCell(row, "value")));
            items.add(item);
        }
        sortByValueDesc(items);
        return items;
    }

    /**
     * 按数量降序、同数量按名称升序（图表顺序稳定，不受数据库返回顺序影响）。
     *
     * @param items 待排序项
     */
    private static void sortByValueDesc(List<NameValue> items) {
        items.sort(Comparator
            .comparingInt((NameValue item) -> item.getValue() == null ? 0 : item.getValue())
            .reversed()
            .thenComparing(NameValue::getName, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * 在线率 = 在线 / (在线 + 离线) × 100，保留 1 位小数；无有效分母时为 0。
     *
     * @param deviceOnline  在线设备数
     * @param deviceOffline 离线设备数
     * @return 在线率（0-100）
     */
    private static Double calcOnlineRate(int deviceOnline, int deviceOffline) {
        int denominator = deviceOnline + deviceOffline;
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(deviceOnline * 1000.0 / denominator) / 10.0;
    }

    /**
     * 取计数结果并转成 {@code int}（超 {@code Integer.MAX_VALUE} 显式失败，不静默截断）。
     *
     * @param count {@code selectCount} 返回值
     * @return 计数
     */
    private static int countToInt(Long count) {
        if (count == null) {
            throw new IllegalStateException("COUNT 查询返回 null");
        }
        return Math.toIntExact(count);
    }

    /**
     * 按别名（大小写/下划线不敏感）取分组行单元格；缺列显式失败，防止 SQL 别名漂移被静默当成 0。
     *
     * @param row   分组行
     * @param alias 列别名
     * @return 单元格值
     */
    private static Object requireCell(Map<String, Object> row, String alias) {
        Object value = findCell(row, alias);
        if (value == null) {
            throw new IllegalStateException("统计查询结果缺少列别名：" + alias
                + "，实际列：" + (row == null ? List.of() : row.keySet()));
        }
        return value;
    }

    /**
     * 找单元格：列名去掉下划线/反引号并转小写后比较（MySQL、驱动与 MyBatis 对别名大小写保留不一致）。
     *
     * @param row   分组行
     * @param alias 列别名
     * @return 单元格值；找不到返回 {@code null}
     */
    private static Object findCell(Map<String, Object> row, String alias) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        String target = normalizeAlias(alias);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (normalizeAlias(entry.getKey()).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 别名归一化：小写 + 去掉下划线与反引号。
     *
     * @param alias 原始别名
     * @return 归一化别名
     */
    private static String normalizeAlias(String alias) {
        if (alias == null) {
            return "";
        }
        return alias.replace("_", "").replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 码值转小写（总览页前端 i18n key 全小写）；{@code null} 原样返回。
     *
     * @param code 原始码值
     * @return 小写码值
     */
    private static String lowerCode(String code) {
        return code == null ? null : code.toLowerCase(Locale.ROOT);
    }

    /**
     * 分组日期转换：兼容 {@link LocalDate}、{@link LocalDateTime}、{@link Date}、{@link Timestamp}
     * 与 {@code yyyy-MM-dd[ HH:mm:ss]} 字符串。
     *
     * @param value 日期值
     * @return 日期
     */
    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            throw new IllegalStateException("趋势日期不能为 null");
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        String text = value.toString().trim();
        if (text.length() > 10) {
            text = text.substring(0, 10);
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("无法识别的趋势日期值：" + value, ex);
        }
    }

    /**
     * 数值转换：兼容 MySQL 聚合返回的 {@link BigDecimal}/{@link Long}/{@link Double} 等类型；
     * 无法精确转成 int 时显式失败，不静默截断。
     *
     * @param value 数值
     * @return int 值
     */
    private static int toInt(Object value) {
        if (value == null) {
            throw new IllegalStateException("统计值不能为 null");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.intValueExact();
        }
        if (value instanceof BigInteger integer) {
            return integer.intValueExact();
        }
        if (value instanceof Double || value instanceof Float) {
            return Math.toIntExact(Math.round(((Number) value).doubleValue()));
        }
        if (value instanceof Number number) {
            return Math.toIntExact(number.longValue());
        }
        return new BigDecimal(value.toString().trim()).intValueExact();
    }

    /**
     * 数值转换：兼容驱动返回的各种数值类型；无法转成 long 时显式失败。
     *
     * @param value 数值
     * @return Long 值
     */
    private static Long toLong(Object value) {
        if (value == null) {
            throw new IllegalStateException("统计值不能为 null");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.longValueExact();
        }
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }
}
