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

import cn.ypbin.admin.iot.entity.IotEventLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运行期事件实例 Mapper（G6）。
 *
 * <p><b>幂等的两条防线都在这里</b>：</p>
 * <ol>
 *   <li>先查（{@link #selectExistingKeys}）：把「本批里已落库的幂等键」一次问出来，省掉重复写入；</li>
 *   <li>插入用 {@code ON DUPLICATE KEY UPDATE id = id}（{@link #insertBatch}）：命中唯一键时
 *       <b>不改任何列</b>，且并发重投<b>不抛异常</b>（应用层先查后插存在竞态窗口，只有唯一键拦得住）。
 *       刻意不用 {@code INSERT IGNORE}：后者会把「非唯一键的其它错误」（如 NOT NULL 违反）一并降级成
 *       警告并静默丢行，与「禁静默降级」冲突。</li>
 * </ol>
 *
 * <p><b>返回值口径的坑（如实登记）</b>：{@code ON DUPLICATE KEY UPDATE} 的受影响行数受 JDBC 驱动
 * 的 {@code useAffectedRows} 影响——Connector/J 默认 {@code false} 会带 {@code CLIENT_FOUND_ROWS}，
 * 此时「命中已有行」也计 1（与「新插入」不可区分）。因此调用方<b>不得</b>把返回值当作「新插入条数」的
 * 唯一依据：幂等条数以先查的结果为准，返回值只用于「小于提交行数 ⇒ 发生过并发重投」这一告警判据。</p>
 *
 * <p><b>为什么显式写 {@code tenant_id}</b>：批量插入的 {@code id} 与 {@code tenant_id} 都由调用方
 * 逐行给（{@code IdWorker} 预生成 id + 按设备解析出的租户），这样一条语句可以服务同一租户下的多台设备；
 * 租户拦截器见到列已存在会跳过注入（不重复加列）。这与 {@code MaintenanceWindowMapper.insertBatch}
 * 同构，并由真库 IT（{@code EventLogIngestIT}）实证「插件不会重复注入 tenant_id」。</p>
 *
 * <p><b>为什么必须显式写 {@code is_deleted = 0}</b>：逻辑删除条件由 BaseMapper 注入器生成，
 * <b>原生 SQL 不会被自动追加</b>——漏写会把已删事件重新「复活」成可见行。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
public interface IotEventLogMapper extends BaseMapper<IotEventLog> {

    /**
     * 查「这批幂等键里已经落库的那些」。
     *
     * <p>调用方必须保证 {@code keys} 非空（空集合会生成 {@code IN ()} 语法错误，本仓铁律要求
     * 批量 IN 查询前先判空短路）。租户条件由租户拦截器追加；{@code is_deleted = 0} 必须显式写。</p>
     *
     * @param deviceId 设备 ID
     * @param keys     幂等键（非空）
     * @return 已存在的幂等键（可能为空集合，绝不返回 null）
     */
    @Select("<script>SELECT idempotent_key FROM iot_event_log WHERE is_deleted = 0 "
        + "AND device_id = #{deviceId} AND idempotent_key IN "
        + "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>"
        + "</script>")
    List<String> selectExistingKeys(@Param("deviceId") Long deviceId, @Param("keys") List<String> keys);

    /**
     * 批量插入事件实例（幂等：命中 {@code uk_iot_event_log_idem} 的行不改动、不抛异常）。
     *
     * @param list 待插入行（调用方保证非空，且每行的 {@code id}/{@code tenantId} 已赋值）
     * @return 受影响行数；**不得**当作「新插入条数」——见类注释的返回值口径说明
     *         （Connector/J 默认带 {@code CLIENT_FOUND_ROWS}，命中已有行也返回 1）
     */
    @Insert("<script>INSERT INTO iot_event_log "
        + "(id, tenant_id, device_id, event_code, event_name, level, params, event_ts, idempotent_key, "
        + "create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.deviceId}, #{item.eventCode}, #{item.eventName}, "
        + "#{item.level}, #{item.params}, #{item.eventTs,jdbcType=TIMESTAMP}, #{item.idempotentKey}, "
        + "NOW(), NOW(), 1, 0)"
        + "</foreach> "
        + "ON DUPLICATE KEY UPDATE id = id</script>")
    int insertBatch(@Param("list") List<IotEventLog> list);
}
