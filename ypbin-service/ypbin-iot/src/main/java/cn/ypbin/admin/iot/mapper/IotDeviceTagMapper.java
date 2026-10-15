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

import cn.ypbin.admin.iot.entity.IotDeviceTag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 设备标签 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotDeviceTagMapper extends BaseMapper<IotDeviceTag> {

    /**
     * 批量插入设备标签（批量打标签的新增行）。
     *
     * <p>一次打标签最多几百台 × 单个键，逐条 insert 同样是 N+1；一条多值 INSERT 把往返降到 1 次。
     * 与 {@code IotDeviceMapper.insertBatch} 同一约定：原生 SQL 不经注入器补列，显式写
     * {@code tenant_id}/{@code create_time}/{@code update_time}/{@code status}/{@code is_deleted}，
     * 因此调用方必须保证每行的 {@code id}/{@code tenantId} 已赋值。</p>
     *
     * @param list 待插入标签（调用方保证非空）
     * @return 受影响行数
     */
    @Insert("<script>INSERT INTO iot_device_tag "
        + "(id, tenant_id, device_id, tag_key, tag_value, create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.deviceId}, #{item.tagKey}, #{item.tagValue}, NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<IotDeviceTag> list);
}
