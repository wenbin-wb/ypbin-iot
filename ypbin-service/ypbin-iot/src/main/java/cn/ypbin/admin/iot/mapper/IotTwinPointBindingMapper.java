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

import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * IoT 数字孪生点位属性绑定 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinPointBindingMapper extends BaseMapper<IotTwinPointBinding> {

    /**
     * 按点位批量**物理删除**绑定（整体替换语义）。
     *
     * <p>为什么物理删除而不是逻辑删除：绑定的唯一键
     * {@code uk_iot_twin_point_binding(tenant_id, point_id, service_identifier,
     * property_identifier)} 不含 {@code is_deleted}（沿用 006 的「逻辑删除后业务键不可复用」
     * 约定）。若只做逻辑删除，被删行仍占用唯一键 ⇒ <b>同一属性删掉后再绑回来会主键冲突</b>。</p>
     *
     * <p>代价（已知且接受）：绑定没有行级历史留痕。绑定是展示配置而非业务事实，
     * 编辑动作由 {@code @Log} 记录。</p>
     *
     * <p>租户插件会给该 DELETE 追加 tenant_id 条件，故仍受租户隔离约束。</p>
     *
     * @param pointIds 点位主键（调用方保证非空）
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM iot_twin_point_binding WHERE point_id IN "
        + "<foreach collection='pointIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int physicalDeleteByPointIds(@Param("pointIds") List<Long> pointIds);

    /**
     * 批量插入绑定（**一条语句**，不在循环里逐条 insert）。
     *
     * <p>一次点位编辑通常带 3~12 条绑定。逐条 <code>insert</code> 就是 3~12 次往返，
     * 而这是「保存点位」这条高频路径（每次拖坐标后保存都会走）。本仓的 CRUD 铁律要求
     * 循环内禁止 DB 调用，故改为单条多值 INSERT。</p>
     *
     * <p><b>显式写 tenant_id 与审计列</b>：{@code @Insert} 注解 SQL 不会被 MyBatis-Plus 的
     * 自动填充与租户插件处理（原生 SQL 不走 {@code FieldFill} 与
     * {@code TenantLineInnerInterceptor} 的 INSERT 改写路径），所以逐列写明。
     * 与 {@code IotDeviceMapper#insertBatch} / {@code IotEventLogMapper#insertBatch} 同构。</p>
     *
     * @param list 绑定实体（调用方保证非空，且每行 tenantId 已填）
     * @return 影响行数
     */
    @Insert("<script>INSERT INTO iot_twin_point_binding "
        + "(id, tenant_id, point_id, service_identifier, property_identifier, property_id, "
        + "display_type, display_role, decimals, unit_override, sort, "
        + "create_time, update_time, status, is_deleted) VALUES "
        + "<foreach collection='list' item='item' separator=','>"
        + "(#{item.id}, #{item.tenantId}, #{item.pointId}, #{item.serviceIdentifier}, "
        + "#{item.propertyIdentifier}, #{item.propertyId}, #{item.displayType}, "
        + "#{item.displayRole}, #{item.decimals}, #{item.unitOverride}, #{item.sort}, "
        + "NOW(), NOW(), 1, 0)"
        + "</foreach></script>")
    int insertBatch(@Param("list") List<IotTwinPointBinding> list);

    /**
     * 批量回写属性主键缓存（**一条语句**，按 (point_id, service_identifier, property_identifier) 定位）。
     *
     * <p>用于「属性行因 TSL 重导入换了主键」的自愈：读侧一次解析可能同时命中多条绑定，
     * 逐条 UPDATE 是读路径上的写放大。这里用 {@code CASE WHEN} 单语句完成——
     * 代价是 SQL 稍长，收益是**读路径最多只发一次写**。</p>
     *
     * @param list 绑定实体（每行需带 id / pointId / serviceIdentifier / propertyIdentifier /
     *             propertyId；调用方保证非空）
     * @return 影响行数
     */
    @Update("<script>UPDATE iot_twin_point_binding SET property_id = CASE "
        + "<foreach collection='list' item='item'>"
        + "WHEN id = #{item.id} THEN #{item.propertyId} "
        + "</foreach>"
        + "END, update_time = NOW() WHERE id IN "
        + "<foreach collection='list' item='item' open='(' separator=',' close=')'>"
        + "#{item.id}"
        + "</foreach></script>")
    int batchHealPropertyId(@Param("list") List<IotTwinPointBinding> list);
}
