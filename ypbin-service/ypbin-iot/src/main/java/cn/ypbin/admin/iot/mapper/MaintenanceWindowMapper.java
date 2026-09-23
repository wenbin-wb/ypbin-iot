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

import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 维护窗口 Mapper（M-2）。
 *
 * <p>两个聚合都用**原生 SQL**（需要跨表连接/表达式），因此按本仓纪律**显式**写 {@code tenant_id} 与
 * {@code is_deleted = 0}：租户条件其实会被 MP 的租户拦截器重写追加（纵深防御），而逻辑删除**不会**被追加
 * ⇒ 漏写会把已删窗口算进统计。</p>
 *
 * @author wenbin
 * @since 2026-09-23
 */
public interface MaintenanceWindowMapper extends BaseMapper<MaintenanceWindow> {

    /**
     * 数据库当前时间（维护窗口的时间基准：与断档/可用率的比较必须同源）。
     *
     * @return 数据库当前时间
     */
    @Select("SELECT NOW()")
    LocalDateTime selectNow();

    /**
     * 窗口内该设备的维护时长（秒）：分子分母都要扣掉的时间。
     *
     * @param tenantId 租户 ID（调用方从上下文取）
     * @param deviceId 设备 ID
     * @param from     窗口起点
     * @param to       窗口终点
     * @param now      结算时刻（进行中的维护窗口结算到它）
     * @return 维护时长（秒，永不为负）
     */
    // 与断档聚合同一取向：显式写租户与逻辑删除，并关闭租户拦截器（避免复杂 SQL 被 JSqlParser 拒绝执行）
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COALESCE(SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(start_ts, #{from,jdbcType=TIMESTAMP}), "
        + "LEAST(COALESCE(end_ts, #{now,jdbcType=TIMESTAMP}), #{to,jdbcType=TIMESTAMP})))), 0) "
        + "FROM maintenance_window WHERE tenant_id = #{tenantId} AND is_deleted = 0 "
        + "AND (device_id IS NULL OR device_id = #{deviceId}) "
        + "AND start_ts < #{to,jdbcType=TIMESTAMP} "
        + "AND (end_ts IS NULL OR end_ts > #{from,jdbcType=TIMESTAMP})")
    Long sumMaintenanceSecondsInWindow(@Param("tenantId") Long tenantId, @Param("deviceId") Long deviceId,
                                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("now") LocalDateTime now);

    /**
     * 窗口内与该设备重叠的维护窗口（按开始时间倒序，最多 {@code limit} 条；用于响应回显）。
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @param from     窗口起点
     * @param to       窗口终点
     * @param limit    返回上限
     * @return 重叠窗口（无则空列表）
     */
    @Select("SELECT * FROM maintenance_window WHERE tenant_id = #{tenantId} AND is_deleted = 0 "
        + "AND (device_id IS NULL OR device_id = #{deviceId}) "
        + "AND start_ts < #{to,jdbcType=TIMESTAMP} "
        + "AND (end_ts IS NULL OR end_ts > #{from,jdbcType=TIMESTAMP}) "
        + "ORDER BY start_ts DESC LIMIT #{limit}")
    List<MaintenanceWindow> listOverlappingInWindow(@Param("tenantId") Long tenantId,
                                                    @Param("deviceId") Long deviceId,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    @Param("limit") int limit);

    /**
     * 关闭一批「进行中」的租约交接窗口（按来源+租户筛选；接管成功时调用）。
     *
     * <p>只关**没有 end_ts** 的：已人工关闭/已结算的窗口不得被改写（否则会把历史窗口拉长，
     * 让那段时间被凭空排除）。</p>
     *
     * @param tenantIds 租户 ID（调用方保证非空）
     * @param source    来源码
     * @param endTs     结束时刻
     * @return 受影响行数
     */
    @Update("<script>UPDATE maintenance_window SET end_ts = #{endTs,jdbcType=TIMESTAMP}, "
        + "update_time = NOW() WHERE is_deleted = 0 AND end_ts IS NULL AND source = #{source} "
        + "AND tenant_id IN <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int closeOpenWindows(@Param("tenantIds") List<Long> tenantIds, @Param("source") String source,
                         @Param("endTs") LocalDateTime endTs);
}
