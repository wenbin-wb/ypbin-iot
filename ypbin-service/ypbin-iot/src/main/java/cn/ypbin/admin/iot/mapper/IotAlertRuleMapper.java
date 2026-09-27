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

import cn.ypbin.admin.iot.entity.IotAlertRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 告警规则 Mapper。
 *
 * <p><b>租户条件</b>：{@link #selectEnabledForEvaluation} 是**跨租户**候选加载，调用方必须用
 * {@code TenantContext.executeIgnore} 包住（与断档扫描同一做法），随后按租户分组、逐租户进入各自
 * 租户上下文再查设备与实例。其余读写走 MyBatis-Plus 条件构造器（租户条件由插件统一追加，
 * **不手写** {@code tenant_id}）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface IotAlertRuleMapper extends BaseMapper<IotAlertRule> {

    /** 显式列清单：不用 {@code SELECT *}，让「加了列但忘了改查询」变成可见的编译期文本差异。 */
    String COLUMNS = "id, tenant_id, rule_name, scope_type, scope_product_id, scope_device_id, severity, "
        + "enabled, trigger_mode, trigger_threshold, pending_ttl_sec, repeat_interval_sec, silence_start, "
        + "silence_end, notify_channels, notify_targets, description, "
        + "create_user, create_time, update_user, update_time, status, is_deleted";

    /**
     * 全部**启用**的规则（跨租户；调用方用 {@code executeIgnore} 包住）。
     *
     * <p>为什么一次取全量而不是逐租户查：租户数未知，逐租户查就是 N 次往返；而「规则数百~千级」
     * （设计 §2.2.5 的量级估算）一次 IN/全表扫描的成本远低于 N 次往返。租户隔离不靠这条 SQL，
     * 而靠**后续所有查询都在各自租户上下文里执行**。</p>
     *
     * @return 启用中的规则（按 id 升序，保证每轮处理顺序稳定）
     */
    @Select("SELECT " + COLUMNS + " FROM iot_alert_rule "
        + "WHERE is_deleted = 0 AND enabled = 1 AND status = 1 ORDER BY id ASC")
    List<IotAlertRule> selectEnabledForEvaluation();

    /**
     * 某租户的全部规则（含停用，用于页面列表与「停用规则」的活动实例收口）。
     *
     * <p>租户条件由插件追加；显式 {@code is_deleted = 0} 必须写（原生 SQL 不会被逻辑删除注入器改写）。</p>
     *
     * @return 规则列表（新建在前）
     */
    @Select("SELECT " + COLUMNS + " FROM iot_alert_rule "
        + "WHERE is_deleted = 0 ORDER BY id DESC")
    List<IotAlertRule> selectAllInTenant();

    /**
     * 批量启用/停用（**单条语句**；调用方先查出「需要变更的 id」，再一次性更新）。
     *
     * <p>为什么不做「循环里逐条 updateById」：那是 N 次往返，且被架构门禁明确禁止
     * （{@code SourceConventionTest.loopsMustNotCallDbOrRpc}）。批量更新的另一个好处是
     * 「同一次批量操作要么全改、要么不改」，不会留下改了一半的中间态。</p>
     *
     * @param ids      需要变更的规则 ID（调用方必须先判空短路）
     * @param enabled  目标启用状态
     * @param updateUser 操作人（可空）
     * @return 实际更新行数
     */
    @Update("<script>UPDATE iot_alert_rule SET enabled = #{enabled}, update_user = #{updateUser}, "
        + "update_time = NOW() WHERE is_deleted = 0 AND id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int batchSetEnabled(@Param("ids") List<Long> ids, @Param("enabled") boolean enabled,
                        @Param("updateUser") Long updateUser);
}
