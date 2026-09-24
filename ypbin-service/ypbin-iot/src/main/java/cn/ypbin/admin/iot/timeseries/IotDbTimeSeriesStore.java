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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IoTDB 表模型历史查询（§5.2.1 查询路径）。
 *
 * <p>与写入器相反，**查询失败必须报错**（不能让前端把"查询挂了"当成"这段时间没数据"）；
 * 参数用哨兵值（from 缺省=0、to 缺省=Long.MAX_VALUE）保持语句静态、可复用预编译。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class IotDbTimeSeriesStore implements TimeSeriesStore {

    private static final Logger log = LoggerFactory.getLogger(IotDbTimeSeriesStore.class);

    /** 时间范围缺省哨兵（与 §5.2.1 的「from 可空」对应）。 */
    static final long MIN_TS = 0L;

    private final TimeSeriesProperties properties;

    public IotDbTimeSeriesStore(TimeSeriesProperties properties) {
        // 同写入器：本类可被直接构造，查询前必须先确保驱动已注册（幂等，无副作用）
        IotDbDriverRegistrar.ensureRegistered();
        this.properties = properties;
    }

    @Override
    public List<TimeSeriesPointResp> query(Long tenantId, Long deviceId, String propertyId, Long from, Long to,
                                           int limit) {
        String sql = "SELECT time, value_double, value_text, quality FROM " + properties.getTableName()
            + " WHERE tenant_id = ? AND device_id = ? AND property_id = ? AND time >= ? AND time <= ?"
            + " ORDER BY time ASC LIMIT ?";
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        try (Connection connection = DriverManager.getConnection(properties.getUrl(), credentials);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(tenantId));
            statement.setString(2, String.valueOf(deviceId));
            statement.setString(3, propertyId);
            statement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(from == null ? MIN_TS : from)));
            statement.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(to == null ? Long.MAX_VALUE : to)));
            statement.setInt(6, limit);
            List<TimeSeriesPointResp> points = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    points.add(toResp(resultSet));
                }
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
