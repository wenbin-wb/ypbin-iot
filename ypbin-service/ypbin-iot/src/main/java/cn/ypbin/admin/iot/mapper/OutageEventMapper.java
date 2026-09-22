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

import cn.ypbin.admin.iot.entity.OutageEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 断档事件 Mapper。
 *
 * <p>窗口查询走 MyBatis-Plus 的条件构造器（而非原生 SQL）：租户条件与逻辑删除都由插件统一处理，
 * 不给「手写租户过滤」留缝。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public interface OutageEventMapper extends BaseMapper<OutageEvent> {

    /**
     * 闭合一条进行中的断档。
     *
     * @param id          断档事件 ID
     * @param endTs       恢复有效数据的时刻
     * @param durationSec 断档时长（秒）；起点缺失时为 {@code null}
     * @return 受影响行数
     */
    @Update("UPDATE outage_event SET end_ts = #{endTs}, duration_sec = #{durationSec}, update_time = NOW() "
        + "WHERE id = #{id}")
    int closeOutage(@Param("id") Long id, @Param("endTs") LocalDateTime endTs,
                    @Param("durationSec") Long durationSec);

    /**
     * 某设备在窗口内的**精确**断档汇总（与明细条数无关）。
     *
     * <p>为什么汇总不能从明细算：明细有返回条数上限（{@code MAX_OUTAGE_ROWS}），一旦窗口内断档超过上限，
     * 「明细求和」就会低估断档 ⇒ **可用率偏高**（只报好的一面）。这里用一次聚合查询拿到精确值，
     * 明细只作为可截断的展示内容。窗口裁剪（{@code GREATEST(start_ts, from)} /
     * {@code LEAST(COALESCE(end_ts, now), to)}）与「单条不得为负」都在 SQL 里完成，
     * 与 {@code AvailabilityCalculator} 的口径一致。</p>
     *
     * <p>与 {@code AiUsageLogMapper.selectSummaryByTenant} 同款：这是**聚合原生 SQL**，绕过了
     * 逻辑删除与租户条件的自动追加，因此这里显式写 {@code tenant_id} 与 {@code is_deleted = 0}
     * （入参 tenantId 由调用方从租户上下文取，绝不来自请求体）。</p>
     *
     * @param tenantId 租户 ID（调用方从上下文取）
     * @param deviceId 设备 ID
     * @param from     窗口起点
     * @param to       窗口终点
     * @param now      当前时刻（进行中的断档结算到它）
     * @return 含 outageCount / outageSeconds / longestOutageSeconds 的映射（永不为 null）
     */
    @Select("SELECT COUNT(*) AS outageCount, "
        + "COALESCE(SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(start_ts, #{from}), "
        + "LEAST(COALESCE(end_ts, #{now}), #{to})))), 0) AS outageSeconds, "
        + "COALESCE(MAX(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(start_ts, #{from}), "
        + "LEAST(COALESCE(end_ts, #{now}), #{to})))), 0) AS longestOutageSeconds "
        + "FROM outage_event WHERE tenant_id = #{tenantId} AND device_id = #{deviceId} "
        + "AND is_deleted = 0 AND start_ts < #{to} AND (end_ts IS NULL OR end_ts > #{from})")
    Map<String, Object> summarizeInWindow(@Param("tenantId") Long tenantId, @Param("deviceId") Long deviceId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("now") LocalDateTime now);
}
