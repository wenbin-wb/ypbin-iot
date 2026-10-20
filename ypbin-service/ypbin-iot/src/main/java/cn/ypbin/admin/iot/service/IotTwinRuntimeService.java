/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;

/**
 * 数字孪生运行态服务。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinRuntimeService {

    /**
     * 组装场景运行态快照（点位 + 设备快照 + 最新值 + 活动告警数）。
     *
     * <p><b>整个过程只有固定次数的查询，与点位数无关</b>：1 次点位 + 1 次绑定 + 1 次设备 +
     * 1 次属性解析 + 1 次 Redis pipeline + 1 次告警分组。</p>
     *
     * @param sceneId 场景主键
     * @return 运行态快照
     */
    IotTwinRuntimeResp runtime(Long sceneId);
}
