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
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生点位响应模型（含全部属性绑定）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinPointResp {

    /** 主键。 */
    private Long id;

    /** 所属场景 ID。 */
    private Long sceneId;

    /** 设备 ID。 */
    @RefText("device")
    private Long deviceId;

    /** 绑定的模型节点名。 */
    private String nodeName;

    /** 场景局部坐标 X。 */
    private BigDecimal localX;

    /** 场景局部坐标 Y。 */
    private BigDecimal localY;

    /** 场景局部坐标 Z。 */
    private BigDecimal localZ;

    /** 锚点朝向法线 X。 */
    private BigDecimal normalX;

    /** 锚点朝向法线 Y。 */
    private BigDecimal normalY;

    /** 锚点朝向法线 Z。 */
    private BigDecimal normalZ;

    /** 图标标识。 */
    private String icon;

    /** 是否常显文字标签。 */
    private Boolean labelVisible;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 属性绑定列表（按主序、sort 升序；无绑定时为空数组，永不为 null）。 */
    private List<TwinBindingResp> bindings;
}
