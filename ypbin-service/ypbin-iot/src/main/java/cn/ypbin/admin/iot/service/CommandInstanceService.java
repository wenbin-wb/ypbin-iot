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

import cn.ypbin.admin.iot.command.CommandReplyReq;
import cn.ypbin.admin.iot.command.CommandReplyResult;
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.starter.crud.model.PageResult;

/**
 * 运行期命令实例服务（段 B：下行 + 在线调试）。
 *
 * <p><b>不自动重试</b>：MQTT 设备的属性写/服务调用不保证幂等，自动重试会把"设备已执行但回执丢了"
 * 变成"执行两次"；改为人工重发**同一条实例**（{@code requestId} 不变、{@code retry_count+1}）。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
public interface CommandInstanceService {

    /**
     * 下发命令/属性设置（含物模型校验、payload 构造、投递与状态推进）。
     *
     * @param deviceId       设备 ID
     * @param req            下发请求
     * @param source         来源
     * @param operatorUserId 下发人（控制台来源时）
     * @return 实例视图（含 requestId 与初始状态）
     */
    CommandInstanceResp send(Long deviceId, CommandSendReq req, CommandSource source,
                             Long operatorUserId);

    /**
     * 分页查询某设备的命令实例（按创建时刻倒序）。
     *
     * @param deviceId 设备 ID
     * @param query    查询条件
     * @return 分页结果
     */
    PageResult<CommandInstanceResp> page(Long deviceId, CommandQuery query);

    /**
     * 人工重发（**同一 requestId**；仅失败/超时可重发）。
     *
     * @param deviceId  设备 ID
     * @param requestId 请求 ID
     * @return 更新后的实例视图
     */
    CommandInstanceResp resend(Long deviceId, String requestId);

    /**
     * 周期扫描：把超时未回执的实例置为 {@code timeout}（**不自动重试**）。
     *
     * @return 本次置为超时的条数
     */
    int scanTimeouts();

    /**
     * 受理设备回执（幂等：重复回执不改终态）。
     *
     * @param req 回执（{@code up/reply} 的载荷）
     * @return 受理结果
     */
    CommandReplyResult applyReply(CommandReplyReq req);
}
