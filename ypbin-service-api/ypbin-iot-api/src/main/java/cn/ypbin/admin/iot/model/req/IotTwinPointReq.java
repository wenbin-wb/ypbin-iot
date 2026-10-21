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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生点位新增/编辑请求（编辑时**整体替换**其绑定）。
 *
 * <p>{@code bindings} 刻意允许空数组但不允许 {@code null}：空数组是用户明确的「这个锚点暂不
 * 展示任何属性」，{@code null} 是调用方漏传字段。两者语义不同，混为一谈会让一次漏字段
 * 静默清空绑定。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinPointReq {

    /** 绑定的设备 ID。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /** 绑定的模型节点名（可空）。 */
    @Size(max = TwinLimits.MAX_NODE_NAME_LENGTH, message = "模型节点名长度不能超过 255")
    private String nodeName;

    /** 场景局部坐标 X。 */
    @NotNull(message = "坐标 X 不能为空")
    private BigDecimal localX;

    /** 场景局部坐标 Y。 */
    @NotNull(message = "坐标 Y 不能为空")
    private BigDecimal localY;

    /** 场景局部坐标 Z。 */
    @NotNull(message = "坐标 Z 不能为空")
    private BigDecimal localZ;

    /** 锚点朝向法线 X（null 视为 0）。 */
    private BigDecimal normalX;

    /** 锚点朝向法线 Y（null 视为 1）。 */
    private BigDecimal normalY;

    /** 锚点朝向法线 Z（null 视为 0）。 */
    private BigDecimal normalZ;

    /** 图标标识（可空）。 */
    @Size(max = TwinLimits.MAX_ICON_LENGTH, message = "图标标识长度不能超过 64")
    private String icon;

    /** 是否在运行态常显文字标签（null 视为 true）。 */
    private Boolean labelVisible;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    @Size(max = TwinLimits.MAX_POINT_REMARK_LENGTH, message = "备注长度不能超过 500")
    private String remark;

    /**
     * 属性绑定列表（**整体替换**语义）。
     *
     * <p>非 null：清空绑定请传空数组 {@code []}；传 {@code null} 是调用方漏字段，必须显式报错。</p>
     */
    @NotNull(message = "属性绑定列表不能为空（清空绑定请传空数组）")
    @Size(max = TwinLimits.MAX_BINDINGS_PER_POINT, message = "单个点位最多绑定 12 个属性")
    @Valid
    private List<TwinBindingReq> bindings;
}
