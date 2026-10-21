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
 * IoT 数字孪生点位（**设备在场景中的锚点**）。
 *
 * <p>点位的主语是<b>设备</b>而不是属性：现场人员说「3 号泵在哪」指的是一个设备位置，
 * 属性是这台设备的多个读数、天然属于同一位置。若按属性建锚点，一台 20 属性的设备会在模型上
 * 留下 20 个点，用户需要手工把它们摆在一起（必然摆不齐）。展示哪几个属性由
 * {@link IotTwinPointBinding} 表达。</p>
 *
 * <p>坐标一律是<b>本场景局部坐标系</b>内的值，不存世界坐标：整栋楼平移一次只需改场景锚点，
 * 楼上所有点位自动跟着走。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_point")
public class IotTwinPoint extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属场景 ID。 */
    private Long sceneId;

    /** 绑定的设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 绑定的模型节点名（可空；仅用于高亮与按节点名预匹配）。 */
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

    /** 图标标识（图标集内的名字）。 */
    private String icon;

    /** 是否在运行态常显文字标签：1 是 0 否。 */
    private Integer labelVisible;

    /** 排序（同场景内标签叠放顺序）。 */
    private Integer sort;

    /** 备注。 */
    private String remark;
}
