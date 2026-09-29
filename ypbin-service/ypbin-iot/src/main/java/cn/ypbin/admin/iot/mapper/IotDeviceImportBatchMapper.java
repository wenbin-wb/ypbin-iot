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

import cn.ypbin.admin.iot.entity.IotDeviceImportBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 设备批量导入批次 Mapper。
 *
 * <p>全部读写走 MyBatis-Plus 条件构造器/BaseMapper：租户条件由插件按上下文注入，
 * **不手写** {@code tenant_id}（手写会绕过统一策略，见租户隔离门禁）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public interface IotDeviceImportBatchMapper extends BaseMapper<IotDeviceImportBatch> {
}
