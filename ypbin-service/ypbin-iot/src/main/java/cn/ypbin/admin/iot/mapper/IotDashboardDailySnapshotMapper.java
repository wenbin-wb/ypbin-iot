/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 仪表盘每日在线快照 Mapper。
 *
 * <p>除 {@link #upsertBatch(List)} 外，读写全部走 BaseMapper + QueryWrapper；该批量 upsert 是每日
 * 跨租户定时任务的唯一出口——一条语句覆盖所有租户，避免逐租户往返（N+1 零容忍）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public interface IotDashboardDailySnapshotMapper extends BaseMapper<IotDashboardDailySnapshot> {

    /**
     * 批量 upsert 各租户当天快照（命中唯一键 {@code uk_iot_dashboard_daily_snapshot} 时只更新四计数）。
     *
     * <p><b>为什么是一条语句</b>：租户数量不可控，逐租户 insert/update 就是 N 次往返；多行
     * {@code INSERT ... VALUES (...),(...)} + {@code ON DUPLICATE KEY UPDATE} 把往返降到 1 次。</p>
     *
     * <p><b>为什么显式写 id/tenant_id/create_time/update_time/status/is_deleted</b>：调用方运行在
     * {@code TenantContext.executeIgnore/runIgnore} 下（跨租户批处理，无租户身份），租户拦截器不会
     * 注入这些值；漏写 tenant_id 会被库以 NOT NULL 拒绝，漏写 is_deleted/status 会写出对页面不可见
     * 或停用的行。主键由调用方 {@code IdWorker} 预生成。</p>
     *
     * <p><b>为什么用 {@code VALUES(col)} 而不是 MySQL 8.0.19+ 的行别名 {@code AS new}</b>：本语句会被
     * MyBatis-Plus 租户拦截器经 JSqlParser 解析，行别名形式实测直接抛 {@code ParseException}
     * （与 {@code IotShadowMapper} 同款已知坑）。</p>
     *
     * <p>返回值为受影响行数（MySQL 对命中行按 2 计数，受 JDBC {@code useAffectedRows} 影响），
     * <b>不得</b>当作「新插入条数」判据。</p>
     *
     * @param list 待 upsert 的快照（调用方保证非空；每行的 id/tenantId/snapshotDate/counts 已赋值）
     * @return 受影响行数
     */
    @Insert("<script>"
        + "INSERT INTO iot_dashboard_daily_snapshot"
        + " (id, tenant_id, snapshot_date, device_total, device_online, device_offline, device_disabled,"
        + " create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.snapshotDate,jdbcType=DATE},"
        + " #{item.deviceTotal}, #{item.deviceOnline}, #{item.deviceOffline}, #{item.deviceDisabled},"
        + " #{item.createTime,jdbcType=TIMESTAMP}, #{item.updateTime,jdbcType=TIMESTAMP},"
        + " #{item.status}, #{item.isDeleted})"
        + "</foreach>"
        + " ON DUPLICATE KEY UPDATE"
        + " device_total = VALUES(device_total),"
        + " device_online = VALUES(device_online),"
        + " device_offline = VALUES(device_offline),"
        + " device_disabled = VALUES(device_disabled),"
        + " update_time = VALUES(update_time)"
        + "</script>")
    int upsertBatch(@Param("list") List<IotDashboardDailySnapshot> list);
}
