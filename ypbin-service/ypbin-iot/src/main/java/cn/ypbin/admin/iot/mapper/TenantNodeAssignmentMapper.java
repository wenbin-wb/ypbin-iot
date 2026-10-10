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
import cn.ypbin.admin.iot.lease.TenantEpochItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户节点归属 Mapper（平台表，租户插件按 {@code ignore-tables} 忽略它）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface TenantNodeAssignmentMapper extends BaseMapper<TenantNodeAssignment> {

    /**
     * 按**节点**取一页 epoch 对账行（含台账 {@code config_epoch}；**不分页**，见下）。
     *
     * <p>为什么必须按节点过滤：接入侧每个节点每 10s 调一次对账，此前接口没有任何入参 ⇒ 每个节点
     * 都拉「全平台 assignment × ledger」（O(节点数 × 全平台租户)）。节点只需要自己名下租户的版本号
     * （客户端本来就按 {@code heldTenants} 丢弃其它行），因此把过滤下沉到 SQL，复杂度降到
     * O(本节点持有租户)，走 {@code idx_tenant_node_assignment_node (access_node, state)}。</p>
     *
     * <p>两条过滤都不能省：{@code a.is_deleted = 0}（手写 SQL 不会自动注入 MyBatis-Plus 的逻辑删除条件）
     * 与 {@code l.is_deleted = 0}（否则已逻辑删除的台账行会把 {@code config_epoch} 复活，
     * 与 {@code bumpConfigEpochMustNotReviveSoftDeletedRow} 的口径冲突）。</p>
     *
     * <p>排序固定 {@code a.tenant_id}：分页必须有序，否则 LIMIT/OFFSET 会漏行或重复。</p>
     *
     * <p>**刻意不分页**：本接口返回集已按节点收敛，规模由节点容量天然约束；分页会把「一次批量调用」
     * 变成每页一次串行 RPC（架构门禁 {@code SourceConventionTest#loopsMustNotCallDbOrRpc} 也拦这种
     * 循环内 RPC），与目标相反。{@code ORDER BY tenant_id} 保留，保证响应稳定可比对。</p>
     *
     * @param accessNode 节点标识（必填，调用方已校验非空）
     * @return 该节点的版本号条目（查无返回空集合）
     */
    @Select("""
        SELECT a.tenant_id AS tenantId,
               a.epoch AS epoch,
               COALESCE(l.config_epoch, 0) AS configEpoch
        FROM tenant_node_assignment a
        LEFT JOIN tenant_ledger l ON l.tenant_id = a.tenant_id AND l.is_deleted = 0
        WHERE a.access_node = #{accessNode}
          AND a.is_deleted = 0
        ORDER BY a.tenant_id
        """)
    List<TenantEpochItem> selectEpochItemsByNode(@Param("accessNode") String accessNode);

    /**
     * 取**数据库时钟**（M0b-4：租约的时间基准统一到 DB，避免多节点时钟漂移）。
     *
     * <p>为什么要用数据库时钟：租约的写入与过期判定若各自读本机时钟，节点间快慢差就会造成
     * 「时钟快的节点提前抢走仍在正常续约的租户」。统一到 DB 时钟后，判定与写入同源。</p>
     *
     * @return 数据库当前时间
     */
    @Select("SELECT NOW()")
    LocalDateTime selectNow();

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
