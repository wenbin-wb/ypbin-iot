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

import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生属性绑定响应模型。
 *
 * <p><b>{@code stale} 是必须被页面显式处理的字段</b>：它表示该绑定指向的属性在物模型里已
 * 不存在（典型成因是 TSL 重新导入物理删除了属性行）。此时 {@code propertyName} 回落为
 * 属性标识本身、{@code dataType}/{@code unit} 为空。页面必须标红并提示
 * 「请重新选择属性」——静默丢弃会让用户以为「我明明绑过」。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinBindingResp {

    /** 绑定主键。 */
    private Long id;

    /** 物模型服务标识。 */
    private String serviceIdentifier;

    /** 物模型属性标识。 */
    private String propertyIdentifier;

    /** 属性主键缓存（可能悬空）。 */
    private Long propertyId;

    /** 属性显示名（解析不到时回落属性标识本身）。 */
    private String propertyName;

    /** 物模型数据类型（解析不到时为空）。 */
    private String dataType;

    /** 单位（unit_override 优先，否则物模型单位）。 */
    private String unit;

    /** 展示形态。 */
    private String displayType;

    /** 展示角色。 */
    private String displayRole;

    /** 小数位。 */
    private Integer decimals;

    /** 单位覆盖。 */
    private String unitOverride;

    /** 排序。 */
    private Integer sort;

    /** 该绑定是否已失效（属性在物模型里已不存在）。 */
    private Boolean stale;
}
