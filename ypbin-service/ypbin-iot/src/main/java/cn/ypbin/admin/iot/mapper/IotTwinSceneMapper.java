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

import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.twin.TwinLimits;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 数字孪生场景 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinSceneMapper extends BaseMapper<IotTwinScene> {

    /**
     * 按场景统计点位数量与直接子场景数（**一次分组查询**，不逐场景 selectCount）。
     *
     * <p>为什么需要它：场景列表页每行都要显示「N 个点位 / M 个子场景」。逐行两次 count
     * 会随场景数线性放大查询次数（N+1），而场景树是每次进页面必拉的列表。</p>
     *
     * @param sceneIds 场景主键（调用方保证非空）
     * @return 每行 {@code sceneId} / {@code pointCount} / {@code childCount}
     */
    @Select("<script>"
        + "SELECT s.id AS sceneId, "
        + "(SELECT COUNT(*) FROM iot_twin_point p WHERE p.scene_id = s.id AND p.is_deleted = 0) "
        + "AS pointCount, "
        + "(SELECT COUNT(*) FROM iot_twin_scene c WHERE c.parent_id = s.id AND c.is_deleted = 0) "
        + "AS childCount "
        + "FROM iot_twin_scene s WHERE s.is_deleted = 0 AND s.id IN "
        + "<foreach collection='sceneIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> countPointsAndChildren(@Param("sceneIds") List<Long> sceneIds);

    /**
     * 递归 CTE 的深度余量（在 {@link TwinLimits#MAX_SCENE_DEPTH} 之上多留的层数）。
     *
     * <p>留余量是为了让「层级过深」与「父子成环」都能在业务侧被识别并给出明确错误，
     * 而不是在 CTE 层撞 MySQL 的递归上限（默认 1000）后抛出无业务含义的异常。</p>
     */
    int CTE_DEPTH_MARGIN = 2;

    /**
     * 用**递归 CTE 一次取回**指定场景的完整父链（含自身），按「自身 → 根」倒序。
     *
     * <p><b>为什么必须是单条语句</b>：树深校验要沿 parentId 逐层上溯。若在 Java 里
     * 「查一层、再查下一层」，就是循环内 DB 调用（本仓 {@code SourceConventionTest}
     * 的 {@code loopsMustNotCallDbOrRpc} 门禁会直接判红）——它既是 N+1，也让
     * 「查询次数随树深增长」失控。MySQL 8.0+ 支持递归 CTE，本项目部署基线是
     * {@code mysql:8.4}，故用一条语句取回。</p>
     *
     * <p><b>深度封顶</b>：{@code depth < MAX_SCENE_DEPTH + 2} 是硬边界——历史脏数据留下的环
     * 若不加限制会让 CTE 无限递归（MySQL 的 {@code cte_max_recursion_depth} 默认 1000，
     * 会先报错而不是返回）。这里多留 2 层余量，让「超深」与「成环」都能被业务侧判定。</p>
     *
     * <p><b>成环的可见性</b>：CTE 遇到环时会在 depth 上限处停下并返回重复 id，
     * 调用方按「链上出现自身」或「层数超限」判定并给出明确错误——不会被误当成正常树。</p>
     *
     * @param sceneId 起始场景主键
     * @return 每行 {@code id} / {@code parentId}，按 {@code depth} 升序（自身在前）
     */
    @Select("WITH RECURSIVE scene_chain AS ("
        + "SELECT s.id, s.parent_id AS parentId, 0 AS depth FROM iot_twin_scene s "
        + "WHERE s.id = #{sceneId} AND s.is_deleted = 0 "
        + "UNION ALL "
        + "SELECT p.id, p.parent_id AS parentId, c.depth + 1 FROM iot_twin_scene p "
        + "INNER JOIN scene_chain c ON p.id = c.parentId "
        + "WHERE p.is_deleted = 0 AND c.depth < "
        // 深度硬上限 = MAX_SCENE_DEPTH + 2：用编译期常量拼接而不是写死 8。
        // 为什么要 +2 余量：留出空间让「超深」与「成环」都能被业务侧判定并给出明确错误，
        // 而不是在 CTE 层直接撞 MySQL 的 cte_max_recursion_depth（默认 1000）后报错。
        // 写成字面量的话，将来调 MAX_SCENE_DEPTH 时此处不会同步（本仓「杜绝魔法值」红线）。
        + (TwinLimits.MAX_SCENE_DEPTH + CTE_DEPTH_MARGIN)
        + ") SELECT id, parentId FROM scene_chain ORDER BY depth")
    List<Map<String, Object>> selectParentChain(@Param("sceneId") Long sceneId);
}
