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

import cn.ypbin.admin.iot.entity.IotAlertNotification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知投递记录 Mapper。
 *
 * <p><b>幂等靠数据库</b>：{@code uk_alert_notification_idem(idempotent_key)} + {@code INSERT IGNORE}
 * ——应用层「先查后插」有竞态窗口，只有唯一键兜得住（与 {@code iot_event_log} 同一结论）。
 * {@code INSERT IGNORE} 会把重复投递静默跳过，这正是幂等键想要的语义（重复行不产生第二次发送），
 * 但「跳过了几条」会通过返回行数与入参条数的差被计数，不会变成不可观测的静默行为。</p>
 *
 * <p><b>列名说明</b>：投递状态列是 {@code notify_status}（不是 {@code status}）——后者被基类的
 * 「业务状态」占用，同名会冲突，见 {@link IotAlertNotification#getNotifyStatus()} 的说明。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface IotAlertNotificationMapper extends BaseMapper<IotAlertNotification> {

    /** 显式列清单。 */
    String COLUMNS = "id, tenant_id, instance_id, channel, target, event, notify_status, attempt, "
        + "next_retry_ts, last_error, idempotent_key, create_user, create_time, update_user, update_time, "
        + "status, is_deleted";

    /**
     * 取到期待投递的通知（**跨租户**；调用方用 {@code executeIgnore} 包住，随后按租户进入上下文投递）。
     *
     * @param now   当前时刻（只算一次）
     * @param limit 单批上限
     * @return 待投递通知
     */
    @Select("SELECT " + COLUMNS + " FROM iot_alert_notification "
        + "WHERE is_deleted = 0 AND notify_status IN ('PENDING', 'FAILED') "
        + "AND (next_retry_ts IS NULL OR next_retry_ts <= #{now,jdbcType=TIMESTAMP}) "
        + "ORDER BY next_retry_ts ASC, id ASC LIMIT #{limit}")
    List<IotAlertNotification> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 某实例的全部投递记录（页面展开行展示「站内信/邮件记录」）。
     *
     * @param instanceIds 实例 ID（先判空短路）
     * @return 投递记录（按 id 升序）
     */
    @Select("<script>SELECT " + COLUMNS + " FROM iot_alert_notification "
        + "WHERE is_deleted = 0 AND instance_id IN "
        + "<foreach collection='instanceIds' item='instanceId' open='(' separator=',' close=')'>#{instanceId}</foreach> "
        + "ORDER BY instance_id ASC, id ASC</script>")
    List<IotAlertNotification> selectByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

    /**
     * 批量写入待投递记录（幂等：重复的 {@code idempotent_key} 被 {@code INSERT IGNORE} 跳过）。
     *
     * @param notifications 待写入记录（非空）
     * @return 实际插入行数（小于入参条数即说明命中了幂等键；其它错误会抛出而不是被吞）
     */
    @Insert("<script>"
        + "INSERT INTO iot_alert_notification"
        + " (id, tenant_id, instance_id, channel, target, event, notify_status, attempt, next_retry_ts,"
        + "  last_error, idempotent_key, create_user, create_time, update_user, update_time,"
        + "  status, is_deleted)"
        + " VALUES "
        + "<foreach collection='notifications' item='n' separator=','>"
        + " (#{n.id}, #{n.tenantId}, #{n.instanceId}, #{n.channel}, #{n.target}, #{n.event},"
        + "  #{n.notifyStatus}, #{n.attempt}, #{n.nextRetryTs,jdbcType=TIMESTAMP}, #{n.lastError},"
        + "  #{n.idempotentKey}, #{n.createUser}, #{n.createTime,jdbcType=TIMESTAMP}, #{n.updateUser},"
        + "  #{n.updateTime,jdbcType=TIMESTAMP}, #{n.status}, #{n.isDeleted})"
        + "</foreach>"
        + " ON DUPLICATE KEY UPDATE id = id"
        + "</script>")
    int insertBatchIdempotent(@Param("notifications") List<IotAlertNotification> notifications);

    /**
     * 批量更新投递记录（单条语句，按主键分别取新值）。
     *
     * <p>只更新投递状态机相关列；{@code idempotent_key} / {@code channel} / {@code target} 一经写入不变。</p>
     *
     * @param notifications 待更新记录（每条带 id；先判空短路）
     * @return 影响行数
     */
    @Update("<script>"
        + "UPDATE iot_alert_notification SET"
        + " notify_status = CASE id <foreach collection='notifications' item='n'>"
        + "WHEN #{n.id} THEN #{n.notifyStatus,jdbcType=VARCHAR} </foreach>ELSE notify_status END,"
        + " attempt = CASE id <foreach collection='notifications' item='n'>"
        + "WHEN #{n.id} THEN #{n.attempt,jdbcType=INTEGER} </foreach>ELSE attempt END,"
        + " next_retry_ts = CASE id <foreach collection='notifications' item='n'>"
        + "WHEN #{n.id} THEN #{n.nextRetryTs,jdbcType=TIMESTAMP} </foreach>ELSE next_retry_ts END,"
        + " last_error = CASE id <foreach collection='notifications' item='n'>"
        + "WHEN #{n.id} THEN #{n.lastError,jdbcType=VARCHAR} </foreach>ELSE last_error END,"
        + " update_time = NOW()"
        + " WHERE is_deleted = 0 AND id IN "
        + "<foreach collection='notifications' item='n' open='(' separator=',' close=')'>#{n.id}</foreach>"
        + "</script>")
    int batchUpdate(@Param("notifications") List<IotAlertNotification> notifications);

    /**
     * 认领一条待投递通知（**防重复投递**：CAS 更新，只有把 {@code next_retry_ts} 推到租约之后的那一次成功）。
     *
     * <p>为什么把「认领」做成一次 UPDATE：单实例部署下并发窗口很小，但部署期间的**滚动重启**会让新旧实例
     * 同时在跑（设计 §6.6 已把「多实例」列为未确认项）。这一步让重复投递的可能性降到「同一瞬间恰好两次
     * 投递」，而 {@code uk_alert_notification_idem} 唯一键兜住重复**行**。</p>
     *
     * @param id      通知 ID
     * @param now     当前时刻
     * @param leaseTo 租约到期时刻（认领后到它之前不再被别的轮次取走）
     * @return 1 = 认领成功
     */
    @Update("UPDATE iot_alert_notification SET attempt = attempt + 1, next_retry_ts = #{leaseTo}, "
        + "update_time = NOW() WHERE is_deleted = 0 AND id = #{id} "
        + "AND notify_status IN ('PENDING', 'FAILED') "
        + "AND (next_retry_ts IS NULL OR next_retry_ts <= #{now,jdbcType=TIMESTAMP})")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("leaseTo") LocalDateTime leaseTo);

    /**
     * 按实例 ID 物理删除投递记录（保留清理：实例被清掉时其投递记录一并清掉）。
     *
     * @param instanceIds 实例 ID（先判空短路）
     * @return 删除行数
     */
    @Delete("<script>DELETE FROM iot_alert_notification WHERE instance_id IN "
        + "<foreach collection='instanceIds' item='instanceId' open='(' separator=',' close=')'>#{instanceId}</foreach>"
        + "</script>")
    int deleteByInstanceIds(@Param("instanceIds") List<Long> instanceIds);
}
