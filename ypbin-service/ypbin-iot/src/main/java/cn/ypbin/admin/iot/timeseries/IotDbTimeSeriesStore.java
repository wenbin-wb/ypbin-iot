/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import cn.ypbin.starter.core.exception.BusinessException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB 表模型历史查询（§5.2.1 查询路径）。
 *
 * <p>与写入器相反，**查询失败必须报错**（不能让前端把"查询挂了"当成"这段时间没数据"）；
 * 时间范围缺省用哨兵值（from 缺省=0、to 缺省={@link Long#MAX_VALUE}）。</p>
 *
 * <p><b>为什么用字面量 SQL（{@link Statement}）而不是 {@code PreparedStatement} 的 {@code ?}</b>
 * （本机真库实测，2026-09-24，容器 {@code apache/iotdb:2.0.11-standalone} + 驱动
 * {@code iotdb-jdbc:2.0.1-beta}）：驱动对 {@code ?} 做的是**无引号的文本替换**——
 * {@code tenant_id = ?} 用 {@code setString("900001")} 绑定后，真库报
 * {@code 701: Cannot apply operator: STRING = INT32}；{@code property_id = ?} 绑定文本值时该值被当成
 * 标识符解析，报 {@code 616: Column 'temp' cannot be resolved}；只有 {@code time} 谓词可用。
 * 官方表模型 JDBC 文档也只给了 {@link Statement} 的示例。⇒ 本类改为拼字面量，并把**注入防护**做在
 * {@link #propertyIdLiteral(String)}：白名单 + 长度上限 + 字面量转义（非法即报业务错误，不静默过滤）。
 * 数值（tenant/device/limit/time）拼接前由 {@code Long}/{@code int} 类型保证只含数字。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class IotDbTimeSeriesStore implements TimeSeriesStore {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesStore.class);

    /** 时间范围缺省哨兵（与 §5.2.1 的「from 可空」对应）。 */
    static final long MIN_TS = 0L;

    /**
     * 点位标识的合法形态：它会被拼进 SQL 字面量，故用白名单 + 长度上限（1~128）做最外层防线。
     *
     * <p>不合法即**报业务错误**（而不是静默过滤/替换——静默过滤会让"查不到"看起来像"没数据"）。</p>
     */
    static final String PROPERTY_ID_PATTERN = "[A-Za-z0-9_.:-]{1,128}";

    /** 查询列（与 §5.2.1 的 DDL 一致）。 */
    private static final String SELECT_COLUMNS = "time, value_double, value_text, quality";

    private final TimeSeriesProperties properties;

    public IotDbTimeSeriesStore(TimeSeriesProperties properties) {
        // 同写入器：本类可被直接构造，查询前必须先确保驱动已注册（幂等，无副作用）
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
    }

    @Override
    public List<TimeSeriesPointResp> query(Long tenantId, Long deviceId, String propertyId, Long from, Long to,
                                           int limit) {
        long fromTs = from == null ? MIN_TS : from;
        long toTs = to == null ? Long.MAX_VALUE : to;
        // 字面量必须带引号：TAG 列是 STRING，不加引号会被当成 INT32/标识符（真库实测，见类注释）
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + properties.getTableName()
            + " WHERE tenant_id = '" + tenantId + "' AND device_id = '" + deviceId + "'"
            + " AND property_id = " + propertyIdLiteral(propertyId)
            + " AND time >= " + fromTs + " AND time <= " + toTs
            + " ORDER BY time ASC LIMIT " + limit;
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        try (Connection connection = DriverManager.getConnection(properties.getUrl(), credentials);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<TimeSeriesPointResp> points = new ArrayList<>();
            while (resultSet.next()) {
                points.add(toResp(resultSet));
            }
            return points;
        } catch (SQLException ex) {
            log.error("[iot] 历史时序查询失败（如实报错，不返回空列表）：deviceId={} propertyId={}",
                deviceId, propertyId, ex);
            throw new BusinessException("历史时序查询失败，请稍后重试");
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * 点位标识 → SQL 字符串字面量（含校验与转义）。
     *
     * @param propertyId 点位标识
     * @return 带单引号的字面量
     * @throws BusinessException 形态不合法（含 null / 超长 / 白名单外字符）
     */
    static String propertyIdLiteral(String propertyId) {
        if (propertyId == null || !propertyId.matches(PROPERTY_ID_PATTERN)) {
            throw new BusinessException("点位标识不合法（只允许字母、数字、下划线、点、冒号、连字符，长度 1~128）");
        }
        // 白名单已排除单引号；这里仍按 SQL 字面量规则转义，做到「校验 + 转义」双保险
        return "'" + propertyId.replace("'", "''") + "'";
    }

    /** 行 → 响应：数值列优先，其次文本列（写入侧保证二者只有一个非空）。 */
    private static TimeSeriesPointResp toResp(ResultSet resultSet) throws SQLException {
        long ts = resultSet.getTimestamp("time").getTime();
        double numeric = resultSet.getDouble("value_double");
        boolean numericNull = resultSet.wasNull();
        String text = resultSet.getString("value_text");
        String value = !numericNull ? formatNumeric(numeric) : text;
        return new TimeSeriesPointResp(ts, value, resultSet.getString("quality"));
    }

    /** 数值格式化：整数值不带小数点（与上报时的字符串形态尽量一致）。 */
    static String formatNumeric(double numeric) {
        if (numeric == Math.rint(numeric) && !Double.isInfinite(numeric)
            && numeric >= -9.007199254740992E15 && numeric <= 9.007199254740992E15) {
            return String.valueOf((long) numeric);
        }
        return String.valueOf(numeric);
    }
}
