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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 告警实例 Mapper（状态机载体）。
 *
 * <p><b>为什么批量写要写成单条语句</b>：一轮评估里的状态变化数量不定（稳态几乎为零，故障时可能成百），
 * 逐行 update 会变成 N 次往返，且架构门禁 {@code SourceConventionTest} 明确禁止「循环内 DB 调用」。
 * 故插入走单条多值 {@code INSERT}，更新走单条 {@code CASE id WHEN ...} 批量更新
 * （同一语句内按主键分别取新值）。</p>
 *
 * <p><b>原生 SQL 的三条纪律</b>（照 {@code OutageEventMapper} 的实测教训）：① 显式写 {@code is_deleted = 0}
 * ——原生 SQL 不会被逻辑删除注入器改写；② 时间参数显式 {@code jdbcType=TIMESTAMP}，否则按
 * {@code jdbcTypeForNull=OTHER} 绑定；③ SQL 形状保持简单可解析（MyBatis-Plus 的租户拦截器要用
 * JSqlParser 解析后追加 {@code tenant_id} 条件）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface IotAlertInstanceMapper extends BaseMapper<IotAlertInstance> {

    /** 显式列清单。 */
    String COLUMNS = "id, tenant_id, rule_id, device_id, property_id, dedup_key, active_dedup_key, "
        + "severity, state, consecutive_count, trigger_value, threshold_snapshot, start_ts, firing_ts, "
        + "resolved_ts, acked_ts, acked_by, last_notified_ts, notify_count, reason, silence_until, "
        + "create_user, create_time, update_user, update_time, status, is_deleted";

    /**
     * 本租户**全部活动实例**（{@code active_dedup_key IS NOT NULL}）。
     *
     * <p>为什么一次取全租户而不是按去重键 IN 查：去重键由「规则 × 设备 × 点位」组合而来，候选集在
     * 判定前不完整（产品级/租户级规则要先反查设备）；而活动实例的数量天然有上界（每个去重键至多一条），
     * 一次取回后在内存里按下标比对，既避免了 N+1，也避免了「漏查导致重复建实例」。</p>
     *
     * @return 活动实例
     */
    @Select("SELECT " + COLUMNS + " FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL")
    List<IotAlertInstance> selectActiveInTenant();

    /**
     * 本租户**断档类**的活动实例（{@code rule_id = 0}）。
     *
     * <p>断档类告警不由点评估器推进状态，而由断档映射链路负责建/收口 —— 它需要一次把自己的活动实例
     * 全部取回，才能判断「这个断档事件是不是已经有实例了」「这条实例对应的事件是否已闭合」。</p>
     *
     * @return 断档类活动实例
     */
    @Select("SELECT " + COLUMNS + " FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND rule_id = 0 AND active_dedup_key IS NOT NULL")
    List<IotAlertInstance> selectActiveOutageAlerts();

    /**
     * 指定规则的活动实例（停用规则时用于收口）。
     *
     * @param ruleIds 规则 ID（调用方必须先判空短路）
     * @return 活动实例
     */
    @Select("<script>SELECT " + COLUMNS + " FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id IN "
        + "<foreach collection='ruleIds' item='ruleId' open='(' separator=',' close=')'>#{ruleId}</foreach>"
        + "</script>")
    List<IotAlertInstance> selectActiveByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    /**
     * 批量插入实例（单条多值 INSERT；调用方分块传入）。
     *
     * <p>{@code create_user} 由调用方显式给出：评估器/断档映射运行在**无 Sa-Token 上下文**的调度线程里，
     * 走 MyBatis-Plus 的自动填充会抛异常（本仓已知形态），故这里用自定义 SQL 完全绕开填充器。</p>
     *
     * @param instances 待插入实例（非空）
     * @return 影响行数
     */
    @Insert("<script>"
        + "INSERT INTO iot_alert_instance"
        + " (id, tenant_id, rule_id, device_id, property_id, dedup_key, active_dedup_key, severity, state,"
        + "  consecutive_count, trigger_value, threshold_snapshot, start_ts, firing_ts, resolved_ts,"
        + "  acked_ts, acked_by, last_notified_ts, notify_count, reason, silence_until,"
        + "  create_user, create_time, update_user, update_time, status, is_deleted)"
        + " VALUES "
        + "<foreach collection='instances' item='i' separator=','>"
        + " (#{i.id}, #{i.tenantId}, #{i.ruleId}, #{i.deviceId}, #{i.propertyId}, #{i.dedupKey},"
        + "  #{i.activeDedupKey}, #{i.severity}, #{i.state}, #{i.consecutiveCount}, #{i.triggerValue},"
        + "  #{i.thresholdSnapshot}, #{i.startTs,jdbcType=TIMESTAMP}, #{i.firingTs,jdbcType=TIMESTAMP},"
        + "  #{i.resolvedTs,jdbcType=TIMESTAMP}, #{i.ackedTs,jdbcType=TIMESTAMP}, #{i.ackedBy},"
        + "  #{i.lastNotifiedTs,jdbcType=TIMESTAMP}, #{i.notifyCount}, #{i.reason},"
        + "  #{i.silenceUntil,jdbcType=TIMESTAMP}, #{i.createUser}, #{i.createTime,jdbcType=TIMESTAMP},"
        + "  #{i.updateUser}, #{i.updateTime,jdbcType=TIMESTAMP}, #{i.status}, #{i.isDeleted})"
        + "</foreach>"
        + "</script>")
    int insertBatch(@Param("instances") List<IotAlertInstance> instances);

    /**
     * 批量更新实例（单条语句，按主键分别取新值）。
     *
     * <p>只更新状态机与通知相关的列；{@code dedup_key} / {@code severity} / {@code start_ts} 等一经写入
     * 不再变化的列刻意不在 SET 里（改不到才叫快照）。</p>
     *
     * @param instances 待更新实例（每条都必须带 id；调用方必须先判空短路）
     * @return 影响行数
     */
    @Update("<script>"
        + "UPDATE iot_alert_instance SET"
        + " state = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.state,jdbcType=VARCHAR} </foreach>ELSE state END,"
        + " consecutive_count = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.consecutiveCount,jdbcType=INTEGER} </foreach>ELSE consecutive_count END,"
        + " trigger_value = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.triggerValue,jdbcType=VARCHAR} </foreach>ELSE trigger_value END,"
        + " firing_ts = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.firingTs,jdbcType=TIMESTAMP} </foreach>ELSE firing_ts END,"
        + " resolved_ts = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.resolvedTs,jdbcType=TIMESTAMP} </foreach>ELSE resolved_ts END,"
        + " acked_ts = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.ackedTs,jdbcType=TIMESTAMP} </foreach>ELSE acked_ts END,"
        + " acked_by = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.ackedBy,jdbcType=BIGINT} </foreach>ELSE acked_by END,"
        + " last_notified_ts = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.lastNotifiedTs,jdbcType=TIMESTAMP} </foreach>ELSE last_notified_ts END,"
        + " notify_count = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.notifyCount,jdbcType=INTEGER} </foreach>ELSE notify_count END,"
        + " silence_until = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.silenceUntil,jdbcType=TIMESTAMP} </foreach>ELSE silence_until END,"
        + " active_dedup_key = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.activeDedupKey,jdbcType=VARCHAR} </foreach>ELSE active_dedup_key END,"
        + " reason = CASE id <foreach collection='instances' item='i'>"
        + "WHEN #{i.id} THEN #{i.reason,jdbcType=VARCHAR} </foreach>ELSE reason END,"
        + " update_time = NOW()"
        + " WHERE is_deleted = 0 AND id IN "
        + "<foreach collection='instances' item='i' open='(' separator=',' close=')'>#{i.id}</foreach>"
        + "</script>")
    int batchUpdate(@Param("instances") List<IotAlertInstance> instances);

    /**
     * 批量确认（用户动作，存在并发点击：CAS 更新，仍是活动且未确认的行才生效）。
     *
     * @param ids    实例 ID（先判空短路）
     * @param userId 确认人
     * @param now    确认时刻
     * @return 实际生效行数
     */
    @Update("<script>UPDATE iot_alert_instance SET state = 'ACKED', acked_ts = #{now}, acked_by = #{userId}, "
        + "update_time = NOW() WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL "
        + "AND state = 'FIRING' AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int batchAck(@Param("ids") List<Long> ids, @Param("userId") Long userId,
                 @Param("now") LocalDateTime now);

    /**
     * 批量静默（**不是状态**：只推迟通知，判定与状态机完全不受影响）。
     *
     * @param ids          实例 ID（先判空短路）
     * @param silenceUntil 静默截止时刻
     * @return 实际生效行数
     */
    @Update("<script>UPDATE iot_alert_instance SET silence_until = #{silenceUntil}, update_time = NOW() "
        + "WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int batchSilence(@Param("ids") List<Long> ids,
                     @Param("silenceUntil") LocalDateTime silenceUntil);

    /**
     * 按设备批量统计**活动告警数**（设备台账列表的标记列与详情概览摘要）。
     *
     * <p>一次聚合查询解决 N 台设备的计数（逐设备 count 就是 N+1）。租户条件由插件追加。</p>
     *
     * @param deviceIds 设备 ID（先判空短路）
     * @return 每行 {@code deviceId} / {@code activeCount}
     */
    @Select("<script>SELECT device_id AS deviceId, COUNT(*) AS activeCount FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND device_id IN "
        + "<foreach collection='deviceIds' item='deviceId' open='(' separator=',' close=')'>#{deviceId}</foreach> "
        + "GROUP BY device_id</script>")
    List<Map<String, Object>> countActiveByDeviceIds(@Param("deviceIds") List<Long> deviceIds);

    /**
     * 按规则批量统计**活动告警数**（规则列表页一眼看出「这条规则现在有几条在响」）。
     *
     * @param ruleIds 规则 ID（先判空短路）
     * @return 每行 {@code ruleId} / {@code activeCount}
     */
    @Select("<script>SELECT rule_id AS ruleId, COUNT(*) AS activeCount FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id IN "
        + "<foreach collection='ruleIds' item='ruleId' open='(' separator=',' close=')'>#{ruleId}</foreach> "
        + "GROUP BY rule_id</script>")
    List<Map<String, Object>> countActiveByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    /**
     * 概览摘要（**一条聚合语句**给出全部计数；不用「拿列表长度当计数」——列表是分页的）。
     *
     * @param deviceId 设备 ID（{@code null} = 整租户）
     * @param since    最近 24 小时起点（数据库时钟算出，与应用时钟解耦）
     * @return 单行聚合结果（键为列别名；永不为 {@code null} 的行——无数据时各项为 0）
     */
    @Select("<script>SELECT "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL THEN 1 ELSE 0 END), 0) AS activeCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND state = 'PENDING' THEN 1 ELSE 0 END), 0) "
        + "AS pendingCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND state = 'FIRING' THEN 1 ELSE 0 END), 0) "
        + "AS firingCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND state = 'ACKED' THEN 1 ELSE 0 END), 0) "
        + "AS ackedCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND severity = 'CRITICAL' THEN 1 ELSE 0 END), 0) "
        + "AS criticalCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND severity = 'WARNING' THEN 1 ELSE 0 END), 0) "
        + "AS warningCount, "
        + "COALESCE(SUM(CASE WHEN active_dedup_key IS NOT NULL AND severity = 'INFO' THEN 1 ELSE 0 END), 0) "
        + "AS infoCount, "
        + "COALESCE(SUM(CASE WHEN state = 'RESOLVED' AND resolved_ts >= #{since,jdbcType=TIMESTAMP} "
        + "THEN 1 ELSE 0 END), 0) AS resolvedLast24h "
        + "FROM iot_alert_instance WHERE is_deleted = 0 "
        + "<if test='deviceId != null'>AND device_id = #{deviceId}</if>"
        + "</script>")
    Map<String, Object> selectSummary(@Param("deviceId") Long deviceId,
                                     @Param("since") LocalDateTime since);

    /**
     * 取一批**已恢复**且早于截止时刻的实例 ID（保留清理第一步；跨租户，调用方用 {@code executeIgnore}）。
     *
     * @param cutoff 截止时刻（数据库时钟算出）
     * @param limit  单批上限（分批删除，避免一次删除把库锁住）
     * @return 实例 ID 列表
     */
    @Select("SELECT id FROM iot_alert_instance WHERE is_deleted = 0 AND state = 'RESOLVED' "
        + "AND resolved_ts IS NOT NULL AND resolved_ts < #{cutoff,jdbcType=TIMESTAMP} "
        + "ORDER BY resolved_ts ASC LIMIT #{limit}")
    List<Long> selectResolvedIdsBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 按活动去重键批量查实例（**并发冲突后的重同步**：唯一键拒绝了一批插入时，用它把「已经被别的轮次
     * 创建的键」查回来，剔除后重试剩余部分，仍然只是一条语句）。
     *
     * @param activeDedupKeys 活动去重键（先判空短路）
     * @return 命中的实例（仅 id 与 active_dedup_key 有用）
     */
    @Select("<script>SELECT id, active_dedup_key FROM iot_alert_instance "
        + "WHERE is_deleted = 0 AND active_dedup_key IN "
        + "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>"
        + "</script>")
    List<IotAlertInstance> selectByActiveDedupKeys(@Param("keys") List<String> keys);

    /**
     * 按 ID 物理删除**候选**实例（抖动被吸收 / pending TTL 到期）。
     *
     * <p>只删仍处于 {@code PENDING} 的行：与「取 ID 和删除之间状态被推进」的窗口对齐——
     * 已经升为 FIRING 的实例**绝不能**被这一步删掉。</p>
     *
     * @param ids 实例 ID（先判空短路）
     * @return 删除行数
     */
    @Delete("<script>DELETE FROM iot_alert_instance WHERE state = 'PENDING' AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int deletePendingByIds(@Param("ids") List<Long> ids);

    /**
     * 按 ID 物理删除实例（保留清理第二步；跨租户，调用方用 {@code executeIgnore}）。
     *
     * <p><b>只删已恢复的行</b>（{@code state = 'RESOLVED'} 写进 WHERE）：清理的判据是「已恢复 + 超保留期」，
     * 把条件同时写进删除语句是为了防「取 ID 与删除之间状态被改回活动」的窗口——
     * 活动告警**永久保留**，任何情况下都不该被清理任务删掉。</p>
     *
     * @param ids 实例 ID（先判空短路）
     * @return 删除行数
     */
    @Delete("<script>DELETE FROM iot_alert_instance WHERE state = 'RESOLVED' AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int deleteResolvedByIds(@Param("ids") List<Long> ids);
}
