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
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
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
     * 在这些租户里，哪些已经有**与给定区间重叠**的维护窗口（自动开窗前的重叠判定/幂等）。
     *
     * <p>为什么用「重叠」而不是「进行中」：人工窗口与自动交接窗口一旦重叠，聚合会**重复计数**
     * （分母重复扣 ⇒ 统计总时长偏小，极端下 fail-open），而人工路径 {@code open} 是拒绝重叠的
     * ⇒ 自动路径必须同一不变量。区间取本批次的**并集范围**（{@code [from, to)}）做保守判定：
     * 只要与该范围有交集就跳过——宁可少开一个窗口（那段空档按断档计，方向保守），也不制造重叠。</p>
     *
     * @param tenantIds 租户 ID（调用方保证非空）
     * @param from      批次最早起点
     * @param to        批次最晚终点（含上界）
     * @return 已有重叠窗口的租户 ID
     */
    @Select("<script>SELECT DISTINCT tenant_id FROM maintenance_window WHERE is_deleted = 0 "
        + "AND start_ts &lt; #{to,jdbcType=TIMESTAMP} "
        + "AND (end_ts IS NULL OR end_ts &gt; #{from,jdbcType=TIMESTAMP}) AND tenant_id IN "
        + "<foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Long> findTenantsWithOverlappingWindow(@Param("tenantIds") List<Long> tenantIds,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /**
     * 批量插入维护窗口（租约交接自动窗口用；id 由调用方用 {@code IdWorker} 预生成——
     * 批量语句绕过 MP 的字段填充，不显式给 id 会写入 0 并在第二行冲突，与租约分配同一处理）。
     *
     * @param list 待插入窗口（调用方保证非空）
     * @return 受影响行数
     */
    @Insert("<script>INSERT INTO maintenance_window "
        + "(id, tenant_id, device_id, start_ts, end_ts, source, reason, create_time, update_time, "
        + "status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, NULL, #{item.startTs}, #{item.endTs}, #{item.source}, #{item.reason}, "
        + "NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<MaintenanceWindow> list);

    /**
     * 关闭一批「进行中」的租约交接窗口（接管成功时调用）。
     *
     * <p>只关**没有 end_ts** 的：已人工关闭/已结算的窗口不得被改写（否则会把历史窗口拉长，
     * 让那段时间被凭空排除）。</p>
     *
     * @param tenantIds 租户 ID（调用方保证非空）
     * @param source    来源码
     * @param endTs     结束时刻
     * @return 受影响行数
     */
    @Update("<script>UPDATE maintenance_window SET "
        // **只缩短**：交接窗口开出时已带 TTL 上界，接管更早发生就把它缩到接管时刻；
        // 绝不延长（延长会把接管之后的真实采集时间也排除掉）
        + "end_ts = LEAST(COALESCE(end_ts, #{endTs,jdbcType=TIMESTAMP}), #{endTs,jdbcType=TIMESTAMP}), "
        + "update_time = NOW() WHERE is_deleted = 0 AND source = #{source} "
        + "AND (end_ts IS NULL OR end_ts &gt; #{endTs,jdbcType=TIMESTAMP}) "
        + "AND tenant_id IN <foreach collection='tenantIds' item='id' open='(' separator=',' close=')'>"
        + "#{id}</foreach></script>")
    int closeOpenWindows(@Param("tenantIds") List<Long> tenantIds, @Param("source") String source,
                         @Param("endTs") LocalDateTime endTs);

    /**
     * 窗口内**断档落在维护里**的秒数（分子里要剔除的部分）。
     *
     * <p>为什么单独一条 SQL 而不是把剔除写进断档聚合：断档聚合一旦引入派生表/相关子查询，就会被
     * MP 的 JSqlParser 与 MySQL 拒绝（CI 真库实测）⇒ 维护剔除在 Java 侧做，两段 SQL 都保持简单可解析。</p>
     *
     * <p><b>前提：同一设备（含租户级窗口）的维护窗口之间不得重叠</b>——本查询对每个断档行与所有重叠窗口
     * 求交后求和，窗口互相重叠会重复计数。写入侧 {@code MaintenanceWindowServiceImpl.open} 已拒绝重叠声明，
     * 直接改库绕过校验会破坏该前提（已登记在 ROADMAP 四点十五）。</p>
     *
     * @param tenantId 租户 ID（调用方从上下文/租户提供者取）
     * @param deviceId 设备 ID
     * @param from     窗口起点
     * @param to       窗口终点
     * @param now      结算时刻（进行中的断档维护窗口结算到它）
     * @return 断档∩维护 的秒数（永不为负）
     */
    @Select("SELECT COALESCE(SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, "
        + "GREATEST(o.start_ts, w.start_ts, #{from,jdbcType=TIMESTAMP}), "
        + "LEAST(COALESCE(o.end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), "
        + "COALESCE(w.end_ts, #{to,jdbcType=TIMESTAMP}), #{to,jdbcType=TIMESTAMP})))), 0) "
        + "FROM outage_event o JOIN maintenance_window w "
        + "ON w.tenant_id = o.tenant_id AND w.is_deleted = 0 "
        + "AND (w.device_id IS NULL OR w.device_id = o.device_id) "
        + "AND w.start_ts < #{to,jdbcType=TIMESTAMP} "
        + "AND (w.end_ts IS NULL OR w.end_ts > #{from,jdbcType=TIMESTAMP}) "
        + "WHERE o.tenant_id = #{tenantId} AND o.device_id = #{deviceId} AND o.is_deleted = 0 "
        + "AND o.start_ts < #{to,jdbcType=TIMESTAMP} "
        + "AND (o.end_ts IS NULL OR o.end_ts > #{from,jdbcType=TIMESTAMP})")
    Long sumOutageInMaintenanceSeconds(@Param("tenantId") Long tenantId, @Param("deviceId") Long deviceId,
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
}
