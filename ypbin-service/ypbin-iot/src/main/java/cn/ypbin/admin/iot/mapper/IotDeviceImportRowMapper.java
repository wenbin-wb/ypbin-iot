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

import cn.ypbin.admin.iot.entity.IotDeviceImportRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备批量导入明细 Mapper。
 *
 * <p><b>为什么要有批量插入</b>：一次导入最多 1 万行，逐行 {@code insert} 就是 1 万次往返；
 * 分批 {@code INSERT ... VALUES (...),(...)} 把往返次数降到「批数」量级。</p>
 *
 * <p><b>为什么显式写 {@code tenant_id}</b>：批量插入的一批行可能跨设备但同租户，
 * 调用方（服务层，在当前租户上下文里）逐行给出 {@code tenantId}；租户拦截器见到列已存在会跳过注入。
 * 与 {@code IotEventLogMapper.insertBatch} 同构（那边由真库 IT 实证了插件不会重复注入 tenant_id）。</p>
 *
 * <p><b>为什么必须显式写 {@code is_deleted = 0} 与 {@code status = 1}</b>：
 * 逻辑删除与默认状态由 BaseMapper 的注入器/DB 默认值生成，<b>原生 SQL 不会被自动追加</b>；
 * 漏写会让明细行以「已删除」入库，页面查不到、用户以为导入丢了。</p>
 *
 * <p><b>不提供单行 insert 的替代路径</b>：明细是一次性写入的历史留痕，不需要更新；
 * 少一条更新路径就少一处「明细与批次头对不上」的可能。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public interface IotDeviceImportRowMapper extends BaseMapper<IotDeviceImportRow> {

    /**
     * 批量插入明细行。
     *
     * @param list 待插入行（调用方保证非空，且每行 {@code id}/{@code tenantId} 已赋值）
     * @return 受影响行数
     */
    @Insert("<script>INSERT INTO iot_device_import_row "
        + "(id, tenant_id, batch_id, row_no, raw_line, row_result, error_code, error_message, "
        + "device_id, device_code, create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.batchId}, #{item.rowNo}, #{item.rawLine}, "
        + "#{item.rowResult}, #{item.errorCode}, #{item.errorMessage}, #{item.deviceId}, "
        + "#{item.deviceCode}, NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<IotDeviceImportRow> list);

    /**
     * 取某批次的**失败行**（按行号升序，供失败行 CSV 下载）。
     *
     * <p>刻意不在服务层用「分页查询 + 循环」拼：下载要的是**全部**失败行，
     * 分页拿不到全量，而循环内查库违反「禁循环内 DB」。（租户条件由插件追加；
     * {@code is_deleted = 0} 必须显式写——原生 SQL 不会被逻辑删除注入器改写。）</p>
     *
     * @param batchId 批次 id
     * @param limit   单次上限（防一个 10 万行的批次把内存撑爆）
     * @return 失败行（可能为空集合，绝不返回 null）
     */
    @Select("SELECT id, tenant_id, batch_id, row_no, raw_line, row_result, "
        + "error_code, error_message, device_id, device_code "
        + "FROM iot_device_import_row WHERE is_deleted = 0 AND batch_id = #{batchId} "
        + "AND row_result = 'failed' ORDER BY row_no ASC LIMIT #{limit}")
    List<IotDeviceImportRow> selectFailedRows(@Param("batchId") Long batchId, @Param("limit") int limit);

    /**
     * 按错误码统计某批次的失败行（错误摘要用；一次聚合，不把失败行全捞回来数）。
     *
     * @param batchId 批次 id
     * @return 每个错误码的行数（按行数倒序）
     */
    @Select("SELECT error_code AS errorCode, COUNT(*) AS rowCount "
        + "FROM iot_device_import_row WHERE is_deleted = 0 AND batch_id = #{batchId} "
        + "AND row_result = 'failed' GROUP BY error_code ORDER BY COUNT(*) DESC")
    List<Map<String, Object>> countFailuresByCode(@Param("batchId") Long batchId);
}
