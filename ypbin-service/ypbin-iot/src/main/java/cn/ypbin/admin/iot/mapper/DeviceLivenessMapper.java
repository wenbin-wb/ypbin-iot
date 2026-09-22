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
     * 复活软删行并整体覆盖活性状态（一条语句内完成，避免「先删后插」撞唯一键）。
     *
     * @param row 携带 id 与最新状态的实体
     * @return 受影响行数
     */
    @Update("UPDATE device_liveness SET poll_interval_ms = #{pollIntervalMs}, last_good_at = #{lastGoodAt}, "
        + "first_observed_at = #{firstObservedAt}, last_observed_at = #{lastObservedAt}, "
        + "open_outage_id = #{openOutageId}, is_deleted = 0, update_time = NOW() WHERE id = #{id}")
    int reviveAndUpdate(DeviceLiveness row);

    /**
     * 数据库时钟（断档判定统一用它，避免多节点时钟漂移把断档算错）。
     *
     * @return 数据库当前时间
     */
    @Select("SELECT NOW()")
    LocalDateTime selectNow();

    /**
     * 标记该设备存在进行中的断档。
     *
     * @param id       活性行 ID
     * @param outageId 断档事件 ID
     * @return 受影响行数
     */
    @Update("UPDATE device_liveness SET open_outage_id = #{outageId}, update_time = NOW() WHERE id = #{id}")
    int markOpenOutage(@Param("id") Long id, @Param("outageId") Long outageId);
}
