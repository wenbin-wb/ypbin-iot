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
import java.util.Map;
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
    /**
     * 批量自愈回写 {@code property_id} 缓存（单条 CASE WHEN，无 N+1）。
     *
     * <p><b>为什么 `WHERE` 里必须有 {@code oldPropertyId} 条件（工单 #307）</b>：
     * 本方法在写事务内执行，而「读绑定 → 解析 → 回写」之间存在并发窗口
     * （另一请求可能已完成整体替换绑定，或完成了一次自愈）。若无旧值条件，
     * 本事务会用**读到的旧结论**无条件覆盖，把别人刚写的新值改回旧值 ⇒
     * 自愈结论与库中值长期不一致、来回抖动，日志重复刷「已自愈回写」掩盖真实问题。</p>
     *
     * <p>加旧值条件后，被并发改过的行自然 0 行受影响（调用方据此记 WARN，不静默）。</p>
     *
     * <p>取值为 {@code NULL} 的旧值需要单独分支：SQL 里 {@code property_id = NULL} 恒为
     * UNKNOWN，必须用 {@code IS NULL}。</p>
     *
     * @param list 每项是一个 Map，含 {@code id} / {@code propertyId}（新值）/
     *             {@code oldPropertyId}（读到的旧值）。
     *             用 Map 而不是给实体加字段：{@code oldPropertyId} 不是表列，
     *             塞进实体会被 MyBatis-Plus 当成不存在的列。
     * @return 实际更新行数（可能小于入参，说明发生并发改写）
     */
    @Update("<script>UPDATE iot_twin_point_binding SET property_id = CASE "
        + "<foreach collection='list' item='item'>"
        + "WHEN id = #{item.id} THEN #{item.propertyId} "
        + "</foreach>"
        + "END, update_time = NOW() WHERE id IN "
        + "<foreach collection='list' item='item' open='(' separator=',' close=')'>"
        + "#{item.id}"
        + "</foreach>"
        // 旧值条件：只更新「仍是我们读到的那一版」的行。
        // 注意 NULL 与 NOT NULL 必须分开写：SQL 的三值逻辑里 `col = NULL` 永远不成立。
        + " AND ( "
        + "<foreach collection='list' item='item' separator=' OR '>"
        + "(id = #{item.id} AND ("
        + "(#{item.oldPropertyId} IS NULL AND property_id IS NULL) OR "
        + "(#{item.oldPropertyId} IS NOT NULL AND property_id = #{item.oldPropertyId})"
        + "))"
        + "</foreach>"
        + " )</script>")
    int batchHealPropertyId(@Param("list") List<Map<String, Object>> list);
}
