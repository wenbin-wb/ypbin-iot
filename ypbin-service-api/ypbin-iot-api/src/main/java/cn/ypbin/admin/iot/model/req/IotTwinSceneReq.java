/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生场景新增/编辑请求。
 *
 * <p>注解校验只覆盖「单字段能不能填」，跨字段与跨行规则（父链成环、深度上限、资产归属、
 * 缩放 > 0、viewJson 合法性）一律在 service 侧完成——注解拿不到库里的父链，也不该让
 * 一个需要查库的规则伪装成声明式校验。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinSceneReq {

    /** 场景编码（租户内唯一）。 */
    @NotBlank(message = "场景编码不能为空")
    @Pattern(regexp = TwinLimits.SCENE_CODE_PATTERN,
        message = "场景编码仅允许字母、数字、下划线和中划线，长度 1-64")
    private String sceneCode;

    /** 场景名称。 */
    @NotBlank(message = "场景名称不能为空")
    @Size(max = TwinLimits.MAX_SCENE_NAME_LENGTH, message = "场景名称长度不能超过 128")
    private String sceneName;

    /** 父场景 ID（null=根场景）。 */
    private Long parentId;

    /** 场景类型：park | building | floor | room。 */
    @NotBlank(message = "场景类型不能为空")
    private String sceneType;

    /** 绑定的模型资产 ID（null=纯容器节点，不渲染模型）。 */
    private Long modelAssetId;

    /** 锚点平移 X（null 视为 0）。 */
    private BigDecimal anchorX;

    /** 锚点平移 Y（null 视为 0）。 */
    private BigDecimal anchorY;

    /** 锚点平移 Z（null 视为 0）。 */
    private BigDecimal anchorZ;

    /** 锚点旋转 X（度，null 视为 0）。 */
    private BigDecimal anchorRx;

    /** 锚点旋转 Y（度，null 视为 0）。 */
    private BigDecimal anchorRy;

    /** 锚点旋转 Z（度，null 视为 0）。 */
    private BigDecimal anchorRz;

    /** 锚点缩放系数（null 视为 1；必须 > 0，service 侧校验）。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON（可空；非空时必须是合法 JSON 且 ≤ 16KB）。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 备注。 */
    @Size(max = TwinLimits.MAX_SCENE_REMARK_LENGTH, message = "备注长度不能超过 500")
    private String remark;
}
