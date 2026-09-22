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

import cn.ypbin.admin.iot.entity.DeviceLiveness;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 设备活性状态 Mapper。
 *
 * @author wenbin
 * @since 2026-09-22
 */
public interface DeviceLivenessMapper extends BaseMapper<DeviceLiveness> {

    /**
     * 按 (租户, 设备) 查活性行，**包含逻辑删除行**。
     *
     * <p>为什么用原生 SQL 且**显式带 tenant_id**：{@code uk_device_liveness(tenant_id, device_id)}
     * 不包含删除标记 ⇒ 软删后重新 insert 会撞唯一键，所以必须能看到已删除行并「复活」它；
     * 而原生 SQL 绕过了 MyBatis-Plus 的逻辑删除重写，租户条件是否被插件追加不具备可读的保证，
     * 因此这里**不假设**插件会补，直接把租户写成入参（与 {@code AiUsageLogMapper} 的既有做法一致）。</p>
     *
     * @param tenantId 租户 ID
     * @param deviceId 设备 ID
     * @return 活性行（含已删除）；不存在返回 {@code null}
     */
    @Select("SELECT * FROM device_liveness WHERE tenant_id = #{tenantId} AND device_id = #{deviceId} LIMIT 1")
    DeviceLiveness selectByDeviceIncludingDeleted(@Param("tenantId") Long tenantId,
                                                  @Param("deviceId") Long deviceId);

    /**
     * 复活软删行并刷新**观测状态**（一条语句内完成，避免「先删后插」撞唯一键）。
     *
     * <p><b>刻意不写 {@code open_outage_id}</b>：该字段的写权分属两方——扫描负责「开」（从 NULL 改成新值），
     * 上报负责「闭」（清空）。上报侧手里的行是**进入本方法前读到的旧快照**，若无条件整行回写，
     * 就会把扫描刚开的断档覆盖回 NULL（断档变孤儿、永不闭合），或把状态拉回旧值。
     * 这类读-改-写丢失更新在单副本下看不出来，多副本/定时任务交错时才会出现。</p>
     *
     * @param row 携带 id 与最新观测状态的实体
     * @return 受影响行数
     */
    @Update("UPDATE device_liveness SET poll_interval_ms = #{pollIntervalMs}, last_good_at = #{lastGoodAt}, "
        + "first_observed_at = #{firstObservedAt}, last_observed_at = #{lastObservedAt}, "
        + "is_deleted = 0, update_time = NOW() WHERE id = #{id}")
    int reviveAndUpdate(DeviceLiveness row);

    /**
     * 清空进行中断档标记（**只有当前标记仍是那一条时**才清）。
     *
     * <p>{@code AND open_outage_id = #{outageId}} 是必要的：上报侧读到「有断档 A」到写回之间，
     * 扫描可能已经开了**新的**断档 B；无条件清空会把 B 抹掉（孤儿）。</p>
     *
     * @param id       活性行 ID
     * @param outageId 期望仍是该断档事件 ID
     * @return 受影响行数（0 = 已被别的路径改动，本次不清）
     */
    @Update("UPDATE device_liveness SET open_outage_id = NULL, update_time = NOW() "
        + "WHERE id = #{id} AND open_outage_id = #{outageId}")
    int clearOpenOutage(@Param("id") Long id, @Param("outageId") Long outageId);

    /**
     * 数据库时钟（断档判定统一用它，避免多节点时钟漂移把断档算错）。
     *
     * @return 数据库当前时间
     */
    @Select("SELECT NOW()")
    LocalDateTime selectNow();

    /**
     * 标记该设备存在进行中的断档（**多副本安全的关键在 {@code AND open_outage_id IS NULL}**）。
     *
     * <p>这个谓词不是可选的：没有它，两个副本并发扫描时都会「成功」把 {@code open_outage_id} 改成自己的
     * 新值（MySQL 的受影响行数只要行匹配就是 1），于是同一段断档被插两条 {@code end_ts IS NULL} 的事件——
     * 可用率被双计（重叠时甚至归零），且后到的有效数据只闭合其中一条，另一条**永久进行中**。</p>
     *
     * @param id       活性行 ID
     * @param outageId 断档事件 ID
     * @return 受影响行数（1 = 本副本抢到；0 = 别的副本已开，调用方必须撤销刚插入的事件）
     */
    @Update("UPDATE device_liveness SET open_outage_id = #{outageId}, update_time = NOW() "
        + "WHERE id = #{id} AND open_outage_id IS NULL")
    int markOpenOutage(@Param("id") Long id, @Param("outageId") Long outageId);
}
