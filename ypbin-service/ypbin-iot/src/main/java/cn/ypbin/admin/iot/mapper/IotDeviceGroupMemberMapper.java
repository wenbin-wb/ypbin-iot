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

import cn.ypbin.admin.iot.entity.IotDeviceGroupMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 设备分组-设备成员 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotDeviceGroupMemberMapper extends BaseMapper<IotDeviceGroupMember> {

    /**
     * 批量插入分组成员（CSV 批量导入的「建完顺手归组」用）。
     *
     * <p>一次导入可能有几百台设备 × 每组都归 ⇒ 逐条 insert 同样是 N+1；
     * 分批一条语句把往返降到批数量级。与 {@code IotDeviceMapper.insertBatch} 同一约定：
     * 显式写 {@code tenant_id} / {@code create_time} / {@code update_time} /
     * {@code status} / {@code is_deleted}（原生 SQL 不经注入器补列）。</p>
     *
     * @param list 待插入成员（调用方保证非空，且每行 {@code id}/{@code tenantId} 已赋值）
     * @return 受影响行数
     */
    @Insert("<script>INSERT INTO iot_device_group_member "
        + "(id, tenant_id, group_id, device_id, create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.groupId}, #{item.deviceId}, NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<IotDeviceGroupMember> list);
}
