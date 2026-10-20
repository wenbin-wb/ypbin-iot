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
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生点位属性绑定（一个锚点展示哪几个物模型属性）。
 *
 * <p><b>为什么权威键是 {@code (serviceIdentifier, propertyIdentifier)} 而不是
 * {@code propertyId}</b>：TSL 重新导入会**物理删除**属性行再重建
 * （{@code IotThingModelServiceImpl#replaceTsl}），且 {@code iot_product_version} 不含结构快照
 * ⇒ 属性主键没有任何跨版本稳定性。{@code propertyId} 仅作联查加速的缓存，<b>可悬空</b>，
 * 读取时按业务标识重解析并可自愈回写。</p>
 *
 * <p>这与「最新值」的规范坐标口径（属性标识）天然一致，不需要额外映射层。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_point_binding")
public class IotTwinPointBinding extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属点位 ID。 */
    private Long pointId;

    /** 物模型服务标识（iot_service.service_id）。 */
    private String serviceIdentifier;

    /** 物模型属性标识（iot_property.identifier）。 */
    private String propertyIdentifier;

    /** 属性主键缓存（仅作联查加速，可能悬空）。 */
    private Long propertyId;

    /** 展示形态：value | gauge | switch | color | sparkline。 */
    private String displayType;

    /** 展示角色：primary | secondary。 */
    private String displayRole;

    /** 小数位（0~6；null=原样展示）。 */
    private Integer decimals;

    /** 单位覆盖（null=用物模型属性自带单位）。 */
    private String unitOverride;

    /** 排序。 */
    private Integer sort;
}
