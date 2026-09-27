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

import cn.ypbin.admin.iot.entity.IotCommandInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运行期命令实例 Mapper。
 *
 * <p><b>租户条件</b>：{@link #selectByRequestId} 与 {@link #markTimeout} 都**不手写** {@code tenant_id}
 * （由租户插件按上下文注入）；{@link #selectTimeoutCandidates} 是**跨租户**候选扫描，调用方必须用
 * {@code TenantContext.executeIgnore} 包住（与断档扫描同一做法），随后的 {@link #markTimeout} 按**主键**更新
 * ——主键唯一，因此跨租户批量更新不会串租户。</p>
 *
 * <p><b>超时判据用数据库时钟</b>（{@code #{now}} 由服务层传入一次算好的值），逐条用自己的
 * {@code timeout_ms} 与 {@code COALESCE(sent_at, create_time)} 比较；不做"读出来在 Java 里判"，
 * 也**不在循环里 update**——候选一次查、更新一条语句。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
public interface IotCommandInstanceMapper extends BaseMapper<IotCommandInstance> {

    /**
     * 按请求 ID 查实例（租户条件由插件注入）。
     *
     * @param requestId 请求 ID
     * @return 实例；无则 {@code null}
     */
    @Select("SELECT id, tenant_id, device_id, command_id, identifier, kind, request_id, topic, payload, "
        + "reply_payload, status_code, error_code, error_msg, timeout_ms, retry_count, emqx_message_id, "
        + "source, operator_user_id, sent_at, finished_at, create_time, update_time "
        + "FROM iot_command_instance WHERE is_deleted = 0 AND request_id = #{requestId} LIMIT 1")
    IotCommandInstance selectByRequestId(@Param("requestId") String requestId);

    /**
     * 取超时候选（**跨租户**；调用方用 {@code runIgnore} 包住）。
     *
     * @param now   数据库/平台当前时刻（只算一次）
     * @param limit 单批上限（防一次扫描把库拖住，下轮继续）
     * @return 候选行（只取更新所需列）
     */
    @Select("SELECT id, tenant_id, device_id, request_id, status_code, timeout_ms, sent_at, create_time "
        + "FROM iot_command_instance "
        + "WHERE is_deleted = 0 AND status_code IN ('pending', 'sent') "
        + "AND DATE_ADD(COALESCE(sent_at, create_time), INTERVAL timeout_ms * 1000 MICROSECOND) < #{now} "
        + "ORDER BY COALESCE(sent_at, create_time) ASC LIMIT #{limit}")
    List<IotCommandInstance> selectTimeoutCandidates(@Param("now") LocalDateTime now,
                                                     @Param("limit") int limit);

    /**
     * 批量置超时（一条语句；只改仍是 pending/sent 的行，天然防重入）。
     *
     * @param ids 主键集合（非空；调用方必须先判空短路）
     * @param now 平台当前时刻
     * @return 受影响行数
     */
    @Update("<script>UPDATE iot_command_instance SET status_code = 'timeout', error_code = 'TIMEOUT', "
        + "error_msg = '等待回执超时', finished_at = #{now}, update_time = #{now} "
        + "WHERE is_deleted = 0 AND status_code IN ('pending', 'sent') AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int markTimeout(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
