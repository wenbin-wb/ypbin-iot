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

import cn.ypbin.admin.iot.entity.IotDevice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 设备台账 Mapper。
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface IotDeviceMapper extends BaseMapper<IotDevice> {

    /**
     * 批量插入设备（CSV 批量导入用）。
     *
     * <p><b>为什么必须有它</b>：批量导入一次最多 1 万行。逐台 {@code insert} 就是 1 万次往返
     * ——既是 N+1，也是本能力的性能上限所在。分批 {@code INSERT ... VALUES (...),(...)}
     * 把往返次数降到「批数」量级。</p>
     *
     * <p><b>为什么显式写 {@code tenant_id}</b>：调用方（服务层，在当前租户上下文里）逐行给出
     * {@code tenantId}；租户拦截器见到列已存在会跳过注入。与 {@code IotEventLogMapper.insertBatch}
     * 同构（那边由真库 IT 实证了插件不会重复注入 tenant_id）。</p>
     *
     * <p><b>为什么显式写 {@code create_time}/{@code update_time}/{@code status}/{@code is_deleted}</b>：
     * 审计字段自动填充与逻辑删除条件由 BaseMapper 的注入器生成，<b>原生 SQL 不会被自动追加</b>；
     * 漏写会让设备以「已删除 / 无创建时间」入库——页面上既查不到、也说不清是什么时候建的。
     * {@code status} 由调用方逐行给出（CSV 里的启停列，留空即启用），**不在这里兜默认值**：
     * 掩掉调用方的取值会让「CSV 写了 0 却建成启用」这种缺陷无从发现。</p>
     *
     * @param list 待插入设备（调用方保证非空，且每行的 {@code id}/{@code tenantId} 已赋值）
     * @return 受影响行数
     */
    @Insert("<script>INSERT INTO iot_device "
        + "(id, tenant_id, device_code, device_name, protocol, endpoint, product_id, product_version, "
        + "remark, create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.deviceCode}, #{item.deviceName}, #{item.protocol}, "
        + "#{item.endpoint}, #{item.productId}, #{item.productVersion}, #{item.remark}, "
        + "NOW(), NOW(), #{item.status}, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<IotDevice> list);
}
