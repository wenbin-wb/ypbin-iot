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

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 数字孪生模型资产 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinModelAssetMapper extends BaseMapper<IotTwinModelAsset> {

    /**
     * 按资产统计被多少个场景引用（**一次分组查询**）。
     *
     * <p>为什么需要它：资产列表要显示「被 N 个场景引用」，删除前也要据此拒绝。
     * 逐资产 count 是 N+1，而资产列表是模型管理面板的必拉数据。</p>
     *
     * @param assetIds 资产主键（调用方保证非空）
     * @return 每行 {@code assetId} / {@code sceneCount}
     */
    @Select("<script>"
        + "SELECT model_asset_id AS assetId, COUNT(*) AS sceneCount FROM iot_twin_scene "
        + "WHERE is_deleted = 0 AND model_asset_id IN "
        + "<foreach collection='assetIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
        + "GROUP BY model_asset_id"
        + "</script>")
    List<Map<String, Object>> countSceneReferences(@Param("assetIds") List<Long> assetIds);
}
