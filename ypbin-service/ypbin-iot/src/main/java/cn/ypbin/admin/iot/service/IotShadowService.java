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

import cn.ypbin.admin.iot.model.req.IotShadowReq;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;
import java.util.Map;

/**
 * IoT 设备影子服务（§3.10）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotShadowService {

    /**
     * 读取影子（reported 优先、无则回退 desired 的合并视图）。
     *
     * @param deviceId 设备主键
     * @return 影子响应（未初始化时返回空 Map，字段不返回 null）
     */
    IotShadowResp get(Long deviceId);

    /**
     * 整份写入期望值（desired；reported 由设备上报，不在本方法写入）。
     *
     * <p><b>只写期望、不下发</b>（明确的产品边界）：本方法服务于配置面（先落期望、设备侧何时生效
     * 由后续下发决定）。需要「下发并等待设备确认」时走
     * {@code POST /devices/{id}/commands} 的 {@code property_set + writeDesired=true}——那条路径
     * 复用命令实例/EMQX 发布链路，并调用 {@link #mergeDesired(Long, Map)} 落期望。</p>
     *
     * @param req 期望值（整份置换：不在本次 {@code desired} 里的旧键会被清掉）
     */
    void updateDesired(IotShadowReq req);

    /**
     * 合并写入期望值（desired；命令携带 desired 的落库口径）。
     *
     * <p>与 {@link #updateDesired(IotShadowReq)} 的差别是**按属性键合并**：一次属性设置只动它那一个点位，
     * 不会把上一批量下去、仍未确认的其它期望键抹掉（多副本并发也不同丢更新，合并交给数据库行锁）。</p>
     *
     * @param deviceId 设备主键（按租户隔离校验；不存在/跨租户一律业务错误）
     * @param desired  期望增量（键=属性标识，值=属性值；为空时不写库，直接返回）
     */
    void mergeDesired(Long deviceId, Map<String, Object> desired);
}
