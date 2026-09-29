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

import cn.ypbin.admin.iot.entity.IotPlatformAlert;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 平台自告警实例 Mapper（看板 #10）。
 *
 * <p><b>去重靠库级唯一键，不靠"先查再插"</b>：`uk_platform_alert_active(tenant_id, active_dedup_key)`。
 * "先查有没有活动告警、没有就插入"在多副本并发下会双插（两个副本同时查到"没有"）；
 * 唯一键则保证**同键至多一条活动告警**，命中即更新。</p>
 *
 * <p><b>为什么用 `VALUES(col)` 而不是 MySQL 8.0.19+ 的行别名 `AS new`</b>：本条 SQL 会被
 * MyBatis-Plus 的租户拦截器经 JSqlParser 解析；仓内已实测 JSqlParser 5.2 解析
 * `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE` 直接抛 `ParseException`
 * （`Encountered unexpected token: "AS"`）⇒ 运行时整条语句不可用。
 * `VALUES(col)` 形式可解析，且在 MySQL 8.4 上实测正常（与 {@code IotShadowMapper} 同一结论）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public interface IotPlatformAlertMapper extends BaseMapper<IotPlatformAlert> {

    /**
     * 开单或续期（命中活动去重键则更新观测信息，不新开一条）。
     *
     * <p>命中时**不**改动 `start_ts`（那是"问题从什么时候开始"的答案，续期不该刷新它），
     * 只推进 `firing_ts`/`observed_rounds`/`last_verdict`/`summary`——后者是"现在怎么样"。</p>
     *
     * @param alert 待写入（`active_dedup_key` 已置为去重键、`resolved_ts` 为 NULL）
     * @return 受影响行数（MySQL 对命中行按 2 计数，仅供观测，**不作判据**）
     */
    @Insert("INSERT INTO iot_platform_alert (id, tenant_id, rule_code, dedup_key, active_dedup_key,"
        + " severity, state, summary, metric_snapshot, start_ts, firing_ts, resolved_ts,"
        + " last_verdict, observed_rounds, create_time, update_time, status, is_deleted) VALUES"
        + " (#{id}, #{tenantId}, #{ruleCode}, #{dedupKey}, #{activeDedupKey}, #{severity}, #{state},"
        + " #{summary}, #{metricSnapshot}, #{startTs}, #{firingTs}, NULL, #{lastVerdict},"
        + " #{observedRounds}, NOW(), NOW(), 1, 0)"
        + " ON DUPLICATE KEY UPDATE"
        + " severity = VALUES(severity),"
        + " state = VALUES(state),"
        + " summary = VALUES(summary),"
        + " metric_snapshot = VALUES(metric_snapshot),"
        + " firing_ts = VALUES(firing_ts),"
        + " last_verdict = VALUES(last_verdict),"
        + " observed_rounds = iot_platform_alert.observed_rounds + 1,"
        + " update_time = NOW(), status = 1, is_deleted = 0")
    int upsertActive(IotPlatformAlert alert);

    /**
     * 收口一条活动告警（恢复）。
     *
     * <p>把 `active_dedup_key` 置 NULL 是**去重的关键一步**：唯一索引对含 NULL 的行不做约束，
     * 因此置 NULL 后该键即可再次开单（下一轮若又异常，会开一条新的）。
     * 若忘了置 NULL，问题第二次发生时**不会再告警**（静默失效）。</p>
     *
     * @param id         告警主键
     * @param state      终态码（`RESOLVED`）
     * @param summary    恢复说明
     * @param resolvedTs 恢复时刻
     * @return 受影响行数
     */
    @Update("UPDATE iot_platform_alert SET active_dedup_key = NULL, state = #{state},"
        + " summary = #{summary}, resolved_ts = #{resolvedTs}, last_verdict = #{state},"
        + " update_time = NOW() WHERE id = #{id}")
    int resolve(@Param("id") Long id, @Param("state") String state,
                @Param("summary") String summary,
                @Param("resolvedTs") LocalDateTime resolvedTs);
}
