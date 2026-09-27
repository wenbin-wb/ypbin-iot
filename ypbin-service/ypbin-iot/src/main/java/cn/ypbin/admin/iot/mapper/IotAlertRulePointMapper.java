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

import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警规则点位条件 Mapper。
 *
 * <p>唯一的批量读入口 {@link #selectByRuleIds}：评估器与断档映射都**一次**取回本轮涉及的全部条件行
 * （严禁逐规则查——那就是 N+1，架构门禁 {@code SourceConventionTest} 会拦）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface IotAlertRulePointMapper extends BaseMapper<IotAlertRulePoint> {

    /** 显式列清单。 */
    String COLUMNS = "id, tenant_id, rule_id, property_id, operator, threshold, value_type, deadband, "
        + "create_user, create_time, update_user, update_time, status, is_deleted";

    /**
     * 按规则 ID 批量取条件行（租户条件由插件追加）。
     *
     * @param ruleIds 规则 ID 列表（调用方必须先判空短路，避免生成 `IN ()` 语法错误）
     * @return 条件行（按 rule_id、id 升序）
     */
    @Select("<script>SELECT " + COLUMNS + " FROM iot_alert_rule_point "
        + "WHERE is_deleted = 0 AND rule_id IN "
        + "<foreach collection='ruleIds' item='ruleId' open='(' separator=',' close=')'>#{ruleId}</foreach> "
        + "ORDER BY rule_id ASC, id ASC</script>")
    List<IotAlertRulePoint> selectByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    /**
     * 批量插入条件行（单条多值 INSERT；条件行数量有上限 {@code ypbin.alert.max-points-per-rule}）。
     *
     * <p>审计字段由调用方显式给出：自定义 SQL 不走 MyBatis-Plus 的自动填充（规则保存路径**有**登录态，
     * 但把填充交给 SQL 之外的显式赋值更能让「谁改的」在代码里一眼可见）。</p>
     *
     * @param points 条件行（非空；调用方先判空短路）
     * @return 影响行数
     */
    @Insert("<script>"
        + "INSERT INTO iot_alert_rule_point"
        + " (id, tenant_id, rule_id, property_id, operator, threshold, value_type, deadband,"
        + "  create_user, create_time, update_user, update_time, status, is_deleted)"
        + " VALUES "
        + "<foreach collection='points' item='p' separator=','>"
        + " (#{p.id}, #{p.tenantId}, #{p.ruleId}, #{p.propertyId}, #{p.operator}, #{p.threshold},"
        + "  #{p.valueType}, #{p.deadband}, #{p.createUser}, #{p.createTime,jdbcType=TIMESTAMP},"
        + "  #{p.updateUser}, #{p.updateTime,jdbcType=TIMESTAMP}, #{p.status}, #{p.isDeleted})"
        + "</foreach>"
        + "</script>")
    int insertBatch(@Param("points") List<IotAlertRulePoint> points);

    /**
     * 物理删除某规则的全部条件行（修改规则时「先删后插」，只在该规则内，不影响其它规则）。
     *
     * @param ruleId 规则 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM iot_alert_rule_point WHERE rule_id = #{ruleId}")
    int deleteByRuleId(@Param("ruleId") Long ruleId);
}
