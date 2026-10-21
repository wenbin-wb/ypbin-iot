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

import cn.ypbin.admin.iot.entity.IotService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 物模型服务 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotServiceMapper extends BaseMapper<IotService> {

    /**
     * 物理删除（绕过逻辑删除）：物模型结构是「当前草稿」的派生数据，全量替换时必须真正移除。
     *
     * <p>为什么不能沿用逻辑删除：iot_service 上有<b>业务唯一键</b>且不含 is_deleted
     * （沿用 006 的「逻辑删除后业务键不可复用」约定），若只做逻辑删除，被删行仍占用唯一键，
     * 同一产品<b>第二次导入 TSL</b> 就会主键冲突 —— 「全量替换」失效。</p>
     *
     * <p>代价（已知且接受）：结构行被物理删除后<b>无法回查</b>其历史定义。现有的
     * iot_product_version 只记录版本号/状态/发布时间，<b>不含结构快照</b>，因此已发布版本的
     * TSL 内容在当前模型下本就不可重建 —— 这是 M-1「结构表存当前草稿」模型的既定缺口，
     * 不是本删除方式引入的。</p>
     *
     * <p>租户插件会给该 DELETE 追加 tenant_id 条件，故仍受租户隔离约束。</p>
     *
     * @param ids 待删除主键（调用方保证非空）
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM iot_service WHERE id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 按**服务业务标识**批量查服务行（自愈链路专用）。
     *
     * <p>为什么必须有它：孪生绑定的权威键是 {@code (serviceIdentifier, propertyIdentifier)}，
     * 而属性主键只是可能悬空的缓存。若只按主键查库，属性行被 TSL 重导入物理删除后
     * 就永远查不到 ⇒ 自愈永不发生（独立复核实测发现的真实缺陷）。</p>
     *
     * <p>租户插件会给该 SELECT 追加 tenant_id 条件，故仍受租户隔离约束。</p>
     *
     * <p><b>为什么必须同时按 {@code productIds} 收敛</b>：{@code service_id} 只在**产品内**唯一
     * （对齐 IoTDA 语义）。同一租户下两个产品都定义 {@code Environment} 服务是常态
     * （例如「温湿度变送器」和「空气质量仪」各有自己的 Environment）。
     * 若只按 service_id 查，二者会同时命中并互相覆盖业务标识索引，导致绑定被解析到
     * **另一个产品的同名属性**——页面显示的是错误的值，比 stale 更危险
     * （stale 用户至少看得见「这里坏了」，错配会让用户以为数据正常）。
     * 独立复核实测发现过该错配（期望 2001 实得 1001）。</p>
     *
     * @param serviceIdentifiers 服务业务标识（调用方保证非空）
     * @param productIds         限定的产品主键集合（调用方保证非空）
     * @return 服务行（无匹配时为空列表）
     */
    @Select("<script>SELECT * FROM iot_service WHERE is_deleted = 0 AND service_id IN "
        + "<foreach collection='serviceIdentifiers' item='sid' open='(' separator=',' close=')'>"
        + "#{sid}</foreach>"
        + " AND product_id IN "
        + "<foreach collection='productIds' item='pid' open='(' separator=',' close=')'>"
        + "#{pid}</foreach></script>")
    List<IotService> selectByServiceIdentifiers(
        @Param("serviceIdentifiers") Collection<String> serviceIdentifiers,
        @Param("productIds") Collection<Long> productIds);
}
