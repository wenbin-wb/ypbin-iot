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

import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 租户节点归属 Mapper（平台表，租户插件按 {@code ignore-tables} 忽略它）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface TenantNodeAssignmentMapper extends BaseMapper<TenantNodeAssignment> {

    /**
     * 批量首次分配（单条语句 + 唯一键冲突即跳过）。
     *
     * <p>为什么不用循环单条 insert：那是 N+1（架构门禁会拦），而且在多副本抢占时
     * 一条冲突会让整轮分配中断。{@code INSERT IGNORE} 让「谁先到谁赢」在一条语句里定论，
     * 冲突行被跳过、其余行照常写入。</p>
     *
     * <p>⚠️ {@code INSERT IGNORE} 是 MySQL 语义：它也会忽略除唯一键外的其它可忽略错误
     * （如数据截断）。本表字段都由服务端生成、无外部输入，风险可控；换数据库时需要同步改这里。</p>
     *
     * @param assignments 待写入的归属（id/时间戳由调用方生成或由 SQL 补齐）
     * @return 实际写入行数（不含被跳过者）
     */
    @Insert("<script>INSERT IGNORE INTO tenant_node_assignment "
        + "(id, tenant_id, access_node, epoch, state, lease_expire_at, create_time, update_time, "
        + "status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.accessNode}, #{item.epoch}, #{item.state}, "
        + "#{item.leaseExpireAt}, NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertIgnoringDuplicates(@Param("list") List<TenantNodeAssignment> assignments);
}
