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

import cn.ypbin.admin.iot.entity.AccessNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 接入节点 Mapper。
 *
 * @author wenbin
 * @since 2026-09-21
 */
public interface AccessNodeMapper extends BaseMapper<AccessNode> {

    /**
     * 按节点标识精确查询（未注册返回 {@code null}）。
     *
     * <p>用显式 SQL 而不是 Lambda 包装器：列少、命中唯一键，且不必依赖实体的 lambda 缓存
     * （单测里少一处易碎的初始化）。</p>
     *
     * @param accessNode 节点标识
     * @return 节点行；不存在返回 {@code null}
     */
    @Select("SELECT * FROM access_node WHERE access_node = #{accessNode} AND is_deleted = 0 "
        + "LIMIT 1")
    AccessNode selectByNode(@Param("accessNode") String accessNode);

    /**
     * 锁定节点行（容量判定的原子前提：同一节点的并发分配在此串行化）。
     *
     * <p>必须在事务内调用；返回 {@code null} 表示该节点未注册。</p>
     *
     * @param accessNode 节点标识
     * @return 节点行（已加锁）
     */
    @Select("SELECT * FROM access_node WHERE access_node = #{accessNode} AND is_deleted = 0 "
        + "FOR UPDATE")
    AccessNode selectForUpdate(@Param("accessNode") String accessNode);
}
