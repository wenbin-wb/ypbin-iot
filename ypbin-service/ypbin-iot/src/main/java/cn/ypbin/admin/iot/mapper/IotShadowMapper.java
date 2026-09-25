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

import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.shadow.ShadowReportedRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 设备影子 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotShadowMapper extends BaseMapper<IotShadow> {

    /**
     * 把一批设备上报值**按 propertyId 合并**进影子 {@code reported}（G2：让设备当前状态由上报驱动）。
     *
     * <p><b>为什么是一条语句</b>：整批一次性发出，往返次数与批大小无关（逐条/逐设备发语句是 N+1）。</p>
     *
     * <p><b>为什么合并在 SQL 里做</b>：{@code reported} 与 {@code desired} 是同一行的两列，
     * 「先读出来、Java 里 put、再整份写回」在多副本并发上报同一设备的不同点位时会丢更新。
     * {@code JSON_MERGE_PATCH} 在**行锁内**按顶层键合并，配合唯一键
     * {@code uk_iot_shadow(tenant_id, device_id)} 的 {@code ON DUPLICATE KEY UPDATE}：
     * 按点位原子合并、重放同一批读数结果不变（幂等）。</p>
     *
     * <p><b>为什么用 {@code VALUES(col)} 而不是 MySQL 8.0.19+ 的行别名 {@code AS new}</b>：本条 SQL 会被
     * MyBatis-Plus 的租户拦截器解析（{@code TenantLineInnerInterceptor} 经 JSqlParser），实测
     * JSqlParser 5.2 解析 {@code INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE} 直接抛
     * {@code ParseException}（{@code Encountered unexpected token: "AS"}）⇒ 运行时整条语句不可用；
     * {@code VALUES(col)} 形式可解析，且在 MySQL 8.4.11 上实测正常工作。</p>
     *
     * <p><b>{@code report_ts} 只前进</b>：{@code GREATEST(现有, 本批)}——乱序/重放的旧批次不得把
     * 「最近上报时刻」改小（否则页面上的「最近上报」会倒退）。</p>
     *
     * <p><b>租户与逻辑删除</b>：{@code tenant_id} 由调用方（{@code DbShadowReportedWriter}，用服务端
     * 从 iot_device 解析出的租户）显式写成插入值；此处不经租户插件注入，理由见写入器的类注释。
     * 命中唯一键时同时把 {@code is_deleted}/{@code status} 复位，等价于既有「复活软删行」的语义。</p>
     *
     * @param rows 待合并的行（每个 (租户, 设备) 至多一条；非空）
     * @return 受影响行数（MySQL 对 {@code ON DUPLICATE KEY UPDATE} 命中的行按 2 计数，仅供观测，不作判据）
     */
    @Insert("<script>"
        + "INSERT INTO iot_shadow (id, tenant_id, device_id, reported, report_ts, create_time, update_time,"
        + " status, is_deleted) VALUES "
        + "<foreach collection='rows' item='row' separator=','>"
        + "(#{row.id}, #{row.tenantId}, #{row.deviceId}, #{row.reportedJson},"
        + " #{row.reportTs,jdbcType=TIMESTAMP}, NOW(), NOW(), 1, 0)"
        + "</foreach>"
        + " ON DUPLICATE KEY UPDATE"
        + " reported = CAST(JSON_MERGE_PATCH(COALESCE(NULLIF(iot_shadow.reported, ''), '{}'),"
        + " VALUES(reported)) AS CHAR),"
        + " report_ts = GREATEST(COALESCE(iot_shadow.report_ts, VALUES(report_ts)), VALUES(report_ts)),"
        + " update_time = NOW(), status = 1, is_deleted = 0"
        + "</script>")
    int mergeReported(@Param("rows") List<ShadowReportedRow> rows);
}
