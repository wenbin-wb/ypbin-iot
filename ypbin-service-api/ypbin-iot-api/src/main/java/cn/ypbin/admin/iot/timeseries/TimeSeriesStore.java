/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import java.util.List;

/**
 * 历史时序查询的**存储缝**（§5.2.1 查询路径）。
 *
 * <p>与写入侧（{@link TimeSeriesWriter}）分开：写入走批量追加，查询要走 TAG 过滤 + 时间范围 + LIMIT，
 * 两者的失败语义也不同（查询失败应当**如实报错**给调用方，而不是像写入那样只计数）。</p>
 *
 * <p>⚠️ 当前仓库内**没有**实现（IoTDB JDBC 实现待补：官方文档在本环境不可达，SQL 语法必须由容器 IT 确认，
 * 见 §5.2.1 的「未能核实」与 ROADMAP 四点二十）。因此本接口的实现装配为「未启用」，
 * 查询端点会**明确报错**而不是返回空列表（空列表会被误读成「这段时间没数据」）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public interface TimeSeriesStore {

    /**
     * 查询某设备某点位在时间范围内的点（按时间升序）。
     *
     * @param tenantId   租户（查询必须显式带租户：下游没有租户上下文）
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @param from       起始时刻（epoch 毫秒，可空）
     * @param to         结束时刻（epoch 毫秒，可空）
     * @param limit      返回条数上限
     * @return 时序点（无数据返回空列表，绝不返回 null）
     */
    List<TimeSeriesPointResp> query(Long tenantId, Long deviceId, String propertyId, Long from, Long to,
                                    int limit);

    /** 存储是否可用（未启用/未实现时查询端点据此报错，而不是静默返回空）。 */
    boolean available();
}
