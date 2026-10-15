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

import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * IoT 仪表盘每日在线快照 Mapper。
 *
 * <p>读写全部走 BaseMapper + QueryWrapper：不做 `@Select` 注解 SQL 或 XML。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public interface IotDashboardDailySnapshotMapper extends BaseMapper<IotDashboardDailySnapshot> {
}
