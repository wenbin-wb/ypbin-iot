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

import cn.ypbin.admin.iot.entity.OutageEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 断档事件 Mapper。
 *
 * <p>窗口查询走 MyBatis-Plus 的条件构造器（而非原生 SQL）：租户条件与逻辑删除都由插件统一处理，
 * 不给「手写租户过滤」留缝。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public interface OutageEventMapper extends BaseMapper<OutageEvent> {

    /**
     * 闭合一条进行中的断档。
     *
     * @param id          断档事件 ID
     * @param endTs       恢复有效数据的时刻
     * @param durationSec 断档时长（秒）；起点缺失时为 {@code null}
     * @return 受影响行数
     */
    @Update("UPDATE outage_event SET end_ts = #{endTs}, duration_sec = #{durationSec}, update_time = NOW() "
        + "WHERE id = #{id}")
    int closeOutage(@Param("id") Long id, @Param("endTs") LocalDateTime endTs,
                    @Param("durationSec") Long durationSec);
}
