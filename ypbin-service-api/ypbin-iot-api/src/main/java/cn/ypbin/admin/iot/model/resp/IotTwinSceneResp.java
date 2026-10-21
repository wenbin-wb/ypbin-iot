/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import cn.ypbin.starter.json.ref.RefText;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生场景响应模型。
 *
 * <p>{@code pointCount} / {@code childCount} 由 {@code IotTwinSceneMapper#countPointsAndChildren}
 * 一次分组查询批量填充，不在响应转换时逐行查库。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinSceneResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 场景编码。 */
    private String sceneCode;

    /** 场景名称。 */
    private String sceneName;

    /** 父场景 ID（根场景为 0）。 */
    @RefText("twinScene")
    private Long parentId;

    /** 场景类型。 */
    private String sceneType;

    /** 模型资产 ID。 */
    @RefText("twinModelAsset")
    private Long modelAssetId;

    /** 锚点平移 X。 */
    private BigDecimal anchorX;

    /** 锚点平移 Y。 */
    private BigDecimal anchorY;

    /** 锚点平移 Z。 */
    private BigDecimal anchorZ;

    /** 锚点旋转 X（度）。 */
    private BigDecimal anchorRx;

    /** 锚点旋转 Y（度）。 */
    private BigDecimal anchorRy;

    /** 锚点旋转 Z（度）。 */
    private BigDecimal anchorRz;

    /** 锚点缩放系数。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 本场景点位数。 */
    private Integer pointCount;

    /** 直接子场景数。 */
    private Integer childCount;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
