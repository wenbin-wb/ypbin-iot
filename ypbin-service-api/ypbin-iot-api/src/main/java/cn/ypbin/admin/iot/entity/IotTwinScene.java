/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生场景（树形）。
 *
 * <p>{@code parentId} 用 {@code 0} 表示根，不用 {@code null}：MySQL 唯一键不把两个 NULL
 * 视为重复，用可空父 id 无法阻止同租户插入两条同名根节点；用 {@code 0} 与
 * {@code sys_menu.pid} 的既有约定一致。</p>
 *
 * <p>{@code anchor*} 是 <b>后端拥有语义</b>的结构化数据（参与世界坐标链计算，见
 * {@code TwinSceneTransform}）；{@code viewJson} 是<b>展示偏好</b>（相机位姿/背景/光照），
 * 按看板 {@code config_json} 的既有先例走 JSON 透传、后端只校验合法性与大小。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_scene")
public class IotTwinScene extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场景编码（租户内唯一，用于深链与免歧义引用）。 */
    private String sceneCode;

    /** 场景名称。 */
    private String sceneName;

    /** 父场景 ID（0=根场景）。 */
    private Long parentId;

    /** 场景类型：park | building | floor | room。 */
    private String sceneType;

    /** 绑定的模型资产 ID（null=纯容器节点，不渲染模型）。 */
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

    /** 锚点缩放系数（> 0）。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON（空=前端用默认值）。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;
}
