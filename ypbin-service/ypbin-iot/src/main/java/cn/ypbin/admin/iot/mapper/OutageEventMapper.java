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
     * <p><b>为什么显式写 {@code tenant_id} 与 {@code is_deleted = 0}</b>（措辞已按实测更正）：</p>
     * <ul>
     *   <li><b>租户条件</b>：MyBatis-Plus 的 {@code TenantLineInnerInterceptor} 会解析并重写原生 SQL，
     *       实测**确实会**追加 {@code tenant_id = ?}（所以显式写是**纵深防御 + 可读性**：多了个同值谓词，
     *       不改变结果；但在 {@code executeIgnore} 这类显式跨租户场景下，它是唯一还能收敛租户的护栏）；</li>
     *   <li><b>逻辑删除</b>：{@code is_deleted = 0} **必须**显式写——逻辑删除由 BaseMapper 的注入器实现，
     *       原生 SQL 不会被追加该条件（漏写会把已删断档算进可用率）。</li>
     *   <li><b>时间参数一律显式 {@code jdbcType=TIMESTAMP}</b>：不写不报错，但会按 {@code jdbcTypeForNull}
     *       的默认 {@code OTHER} 绑定（实测 {@code setNull(idx, 1111)}）；显式写才绑成 TIMESTAMP。</li>
     * </ul>
     *
     * <p>口径细节：{@code COUNT(*)} 统计的是**满足窗口重叠条件的行数**，包含极少数「裁剪后重叠为 0 秒」
     * 的行（例如进行中断档的 {@code now} 恰好落在窗口起点之前）；它只影响展示用的次数，
     * 不影响秒数与可用率。时间列为 {@code DATETIME}（秒精度），{@code TIMESTAMPDIFF(SECOND, …)} 无小数截断问题。</p>
     *
     * @param tenantId 租户 ID（调用方从上下文取）
     * @param deviceId 设备 ID
     * @param from     窗口起点
     * @param to       窗口终点
     * @param now      当前时刻（进行中的断档结算到它）
     * @return 含 outageCount / outageSeconds / longestOutageSeconds 的映射（永不为 null）
     */
    @Select("SELECT COUNT(*) AS outageCount, "
        + "COALESCE(SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(start_ts, #{from,jdbcType=TIMESTAMP}), "
        + "LEAST(COALESCE(end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), #{to,jdbcType=TIMESTAMP})))), 0) "
        + "AS outageSeconds, "
        + "COALESCE(MAX(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(start_ts, #{from,jdbcType=TIMESTAMP}), "
        + "LEAST(COALESCE(end_ts, COALESCE(#{now,jdbcType=TIMESTAMP}, NOW())), #{to,jdbcType=TIMESTAMP})))), 0) "
        + "AS longestOutageSeconds "
        + "FROM outage_event WHERE tenant_id = #{tenantId} AND device_id = #{deviceId} "
        + "AND is_deleted = 0 AND start_ts < #{to,jdbcType=TIMESTAMP} "
        + "AND (end_ts IS NULL OR end_ts > #{from,jdbcType=TIMESTAMP})")
    Map<String, Object> summarizeInWindow(@Param("tenantId") Long tenantId, @Param("deviceId") Long deviceId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("now") LocalDateTime now);
}
