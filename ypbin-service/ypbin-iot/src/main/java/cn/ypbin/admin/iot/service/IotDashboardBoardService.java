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

import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.admin.iot.model.resp.DashboardBoardResp;

/**
 * IoT 组态看板配置服务。
 *
 * <p>按租户存取一份共享看板配置；租户隔离由 MyBatis-Plus 租户插件统一追加，服务内不手写
 * {@code tenant_id} 过滤。</p>
 *
 * <p>方法名刻意用 {@code getBoard}/{@code saveBoard} 而不是 {@code get}/{@code save}：
 * 实现类继承 {@code BaseServiceImpl}，其 {@code save(T entity)} 是实体级插入；
 * 同名会让「保存看板配置」与「插入实体行」在调用处无法区分（读代码时极易看错）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
public interface IotDashboardBoardService {

    /**
     * 读取当前租户的看板配置。
     *
     * @return 看板配置；从未配置时 {@code widgets} 为空数组（永不为 null）
     */
    DashboardBoardResp getBoard();

    /**
     * 整体替换当前租户的看板配置。
     *
     * <p>校验（结构/必填/上限/id 去重/配置体积）全部在写入前完成，不合法一律抛出带人话消息的
     * {@code BusinessException}，绝不写入半合法数据。空数组表示「清空看板」，仍保留本租户这一行。</p>
     *
     * @param req 保存请求
     */
    void saveBoard(DashboardBoardSaveReq req);
}
