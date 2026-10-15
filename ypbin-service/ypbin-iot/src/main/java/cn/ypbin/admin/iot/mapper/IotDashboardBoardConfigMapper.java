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

import cn.ypbin.admin.iot.entity.IotDashboardBoardConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * IoT 组态看板配置 Mapper。
 *
 * <p>读写全部走 BaseMapper + QueryWrapper：每租户只有一行（唯一键
 * {@code uk_iot_dashboard_board_config(tenant_id)}），不需要自定义 SQL；租户条件由 MyBatis-Plus
 * 租户插件在真实 SQL 上追加，本接口不写 {@code tenant_id}。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public interface IotDashboardBoardConfigMapper extends BaseMapper<IotDashboardBoardConfig> {
}
