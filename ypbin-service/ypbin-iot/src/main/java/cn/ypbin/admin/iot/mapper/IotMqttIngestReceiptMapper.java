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

import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MQTT 入站幂等回执 Mapper。
 *
 * <p><b>幂等的两条防线</b>（与 {@code IotEventLogMapper} 同构，见该类的返回值口径说明）：</p>
 * <ol>
 *   <li>先查（{@link #selectByRequestId}）：命中即整批跳过落库，省掉重复写；</li>
 *   <li>插入用 {@code ON DUPLICATE KEY UPDATE id = id}：命中唯一键时不改任何列、并发重投不抛异常。
 *       刻意不用 {@code INSERT IGNORE}——它会把非唯一键错误（如 NOT NULL 违反）一并降级成警告。</li>
 * </ol>
 *
 * <p><b>返回值不得用于判断</b>：Connector/J 默认带 {@code CLIENT_FOUND_ROWS}，「命中已有行」也返回 1，
 * 因此无法据此区分「新插入」与「并发重投」。幂等结论一律以先查结果为准。</p>
 *
 * <p><b>租户条件由插件追加</b>：两条语句都**不**手写 {@code tenant_id}（列里也没有它），
 * 由 MyBatis-Plus 租户插件按当前租户上下文注入；因此调用方必须已进入该设备的租户上下文
 * （见 {@code MqttReadingIngestServiceImpl} 的租户解析）。{@code is_deleted = 0} 必须显式写：
 * 原生 SQL 不会被逻辑删除注入器追加条件。</p>
 *
 * <p><b>二批：{@code receipt_status} 的两种结局</b>（消息跟踪二批）：预查返回**任何**同键行，
 * 由调用方按 {@code receipt_status} 决策——命中 {@code accepted} 才整批跳过；命中 {@code rejected}
 * 则先 {@link #deleteRejectedByRequestId 物理删除}再按新批次继续（不能用 UPDATE 复活，
 * 否则唯一键会吞掉本次受理回执）。删除必须是**物理**删除（{@code DELETE}）而非逻辑删除：
 * 唯一键不含 {@code is_deleted}，逻辑删除会继续占着键位。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public interface IotMqttIngestReceiptMapper extends BaseMapper<IotMqttIngestReceipt> {

    /**
     * 按设备 + 请求 ID 查回执（租户条件由插件追加）。
     *
     * <p><b>刻意不过滤 {@code receipt_status}</b>（消息跟踪二批）：预查要能看见 {@code rejected} 行，
     * 调用方才能「删除旧行后按新批次继续」。是否算幂等命中由调用方按 {@code receipt_status} 判定
     * （只认 {@code accepted}）——把状态条件放 Java 侧是必需的，否则命中 rejected 行时无法触发删除。
     * 代价：预查仍是**同一条语句**（只多选了非键列），accepted 高频路径 0 新增语句。</p>
     *
     * @param deviceId  设备 ID
     * @param requestId 设备侧请求 ID
     * @return 回执行；不存在返回 {@code null}
     */
    @Select("SELECT id, tenant_id, device_id, request_id, item_count, receipt_status, received_count, "
        + "persisted_rows, persisted_at, discard_reason, discard_detail "
        + "FROM iot_mqtt_ingest_receipt "
        + "WHERE is_deleted = 0 AND device_id = #{deviceId} AND request_id = #{requestId} LIMIT 1")
    IotMqttIngestReceipt selectByRequestId(@Param("deviceId") Long deviceId,
                                           @Param("requestId") String requestId);

    /**
     * 写入回执（幂等：命中唯一键的行不改动、不抛异常）。
     *
     * @param receipt 回执行（{@code id}/{@code tenantId} 由调用方预生成）
     * @return 受影响行数；**不得**当作「新插入条数」——见类注释
     */
    @Insert("INSERT INTO iot_mqtt_ingest_receipt "
        + "(id, tenant_id, device_id, request_id, item_count, receipt_status, received_count, "
        + "persisted_rows, persisted_at, discard_reason, discard_detail, "
        + "create_time, update_time, status, is_deleted) "
        + "VALUES (#{id}, #{tenantId}, #{deviceId}, #{requestId}, #{itemCount}, #{receiptStatus}, "
        + "#{receivedCount}, #{persistedRows}, #{persistedAt}, #{discardReason}, #{discardDetail}, "
        + "NOW(), NOW(), 1, 0) "
        + "ON DUPLICATE KEY UPDATE id = id")
    int insertReceipt(IotMqttIngestReceipt receipt);

    /**
     * 物理删除被拒回执（消息跟踪二批：让同 requestId 的重试能重新受理）。
     *
     * <p>只删 {@code receipt_status = 'rejected'} 行：{@code accepted} 行是幂等锚点，绝不删除。
     * 必须有物理删除（不是 {@code deleteById} 的逻辑删除）——唯一键
     * {@code (tenant_id, device_id, request_id)} 不含 {@code is_deleted}，逻辑删除会继续占用键位。</p>
     *
     * @param deviceId  设备 ID
     * @param requestId 设备侧请求 ID
     * @return 删除行数（0 表示并发下已被别的请求删除，属正常）
     */
    @Delete("DELETE FROM iot_mqtt_ingest_receipt "
        + "WHERE is_deleted = 0 AND device_id = #{deviceId} AND request_id = #{requestId} "
        + "AND receipt_status = 'rejected'")
    int deleteRejectedByRequestId(@Param("deviceId") Long deviceId,
                                  @Param("requestId") String requestId);
}
