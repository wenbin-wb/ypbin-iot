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

import cn.ypbin.admin.iot.entity.IotPointMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 点位映射 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotPointMappingMapper extends BaseMapper<IotPointMapping> {

    /**
     * 统计**孤儿映射**行数：映射行仍在，但它引用的 {@code iot_property} 行已不存在（或被逻辑删除）。
     *
     * <p><b>为什么需要它</b>：物模型重导入（{@code IotThingModelServiceImpl#replaceTsl}）会**物理删除**
     * {@code iot_property} 行，而 {@code iot_point_mapping} 行不会跟着消失 ⇒ 库里会累积悬空引用。
     * 这类映射不再产出任何合法坐标（入站已按「孤儿」丢弃并计数
     * {@code iot.ingest.propertyid.orphan}），但**行本身还在**，需要一个库级计数让运维看见存量规模。</p>
     *
     * <p><b>只读计数，不做删除</b>（刻意）：物理删除映射行会改变设备的采集配置，属需要人确认的动作；
     * 巡检只把规模暴露成指标 + 日志，处置方式见 {@code docs/IOT-ROADMAP.md} 四点十七。</p>
     *
     * <p>{@code is_deleted = 0} 写在 ON 条件里（而不是 WHERE）：逻辑删除的属性行与「物理缺失」
     * 对点位坐标是同一件事——都拿不到 {@code identifier}。</p>
     *
     * <p>外层的 {@code m.is_deleted = 0} **必须显式写**（独立复核指出）：自定义 {@code @Select} 不会被
     * MyBatis-Plus 自动附加 {@code @TableLogic} 条件 ⇒ 少了它，「已逻辑删除的映射行 + 属性行也缺失」
     * 会被算成孤儿，gauge 偏大、把运维引向不存在的清理对象。</p>
     *
     * <p>租户插件会给两侧表追加租户条件；调用方在跨租户巡检路径上必须显式
     * {@code TenantContext.executeIgnore}（否则 fail-closed 直接抛）。</p>
     *
     * @return 孤儿映射行数
     */
    @Select("SELECT COUNT(*) FROM iot_point_mapping m LEFT JOIN iot_property p"
        + " ON p.id = m.property_id AND p.is_deleted = 0"
        + " WHERE m.is_deleted = 0 AND p.id IS NULL")
    long countOrphanMappings();
}
