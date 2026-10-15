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
import cn.ypbin.admin.iot.model.req.CommandBatchSendReq;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandBatchResp;
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
     * 批量下发：**一个命令模板**应用到多台设备，逐台返回结果（部分失败如实返回，不整体失败）。
     *
     * <p><b>并发与事务</b>：本方法刻意**不加 {@code @Transactional}**——单台下发的语义就是"每台各自
     * 即时提交"（见 {@link #send}），批量若包一个大事务，一台设备失败就会把已成功下发的实例一起回滚，
     * 与"单台失败不影响其它"直接矛盾。</p>
     *
     * <p><b>幂等键逐设备派生</b>：请求级 {@code clientRequestId} 会被拼成 {@code <key>:<deviceId>}
     * 落到各设备（见实现注释），因此 A 设备命中幂等不会短路 B 设备。</p>
     *
     * <p><b>耗时上界不是严丝合缝的预算</b>：{@code ypbin.emqx.command-batch.total-budget-ms} 是
     * **循环入口检查点**（每台开跑前判一次），故实际总耗时可超出该值最多约一台设备的耗时
     * （≈ 单台 EMQX 写超时）。详见 {@link CommandBatchProperties#getTotalBudgetMs()}。</p>
     *
     * @param req            批量请求（设备清单非空、模板必填）
     * @param source         来源
     * @param operatorUserId 下发人
     * @return 逐设备结果回执（含 total/accepted/rejected）
     */
    CommandBatchResp sendBatch(CommandBatchSendReq req, CommandSource source, Long operatorUserId);

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
     * @param req                 回执（{@code up/reply} 的载荷）
     * @param authenticatedDevice 主题派生的认证设备 ID（可空 = 直接 HTTP 调用；非空时必须与载荷一致）
     * @return 受理结果
     */
    CommandReplyResult applyReply(CommandReplyReq req, Long authenticatedDevice);
}
