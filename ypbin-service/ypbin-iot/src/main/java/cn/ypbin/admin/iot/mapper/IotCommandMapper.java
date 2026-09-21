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

import cn.ypbin.admin.iot.entity.IotCommand;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 物模型命令 Mapper。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotCommandMapper extends BaseMapper<IotCommand> {

    /**
     * 物理删除（绕过逻辑删除）：物模型结构是「当前草稿」的派生数据，全量替换时必须真正移除。
     *
     * <p>为什么不能沿用逻辑删除：iot_command 上有<b>业务唯一键</b>且不含 is_deleted
     * （沿用 006 的「逻辑删除后业务键不可复用」约定），若只做逻辑删除，被删行仍占用唯一键，
     * 同一产品<b>第二次导入 TSL</b> 就会主键冲突 —— 「全量替换」失效。历史由
     * iot_product_version 记录，结构行本身不需要保留删除历史。</p>
     *
     * <p>租户插件会给该 DELETE 追加 tenant_id 条件，故仍受租户隔离约束。</p>
     *
     * @param serviceIds 待删除主键（调用方保证非空）
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM iot_command WHERE service_id IN "
        + "<foreach collection='serviceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int physicalDeleteByServiceIds(@Param("serviceIds") List<Long> serviceIds);
}
