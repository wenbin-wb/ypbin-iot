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

import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.starter.crud.model.PageResult;
import java.util.List;
import java.util.Map;

/**
 * 告警实例服务（设计 §2.1 表 C 的**只读 + 确认 + 静默**面）。
 *
 * <p><b>没有「人工关闭」</b>（用户已批准口径 5：要 ACK，不要人工关闭——关闭交给恢复条件）。
 * 误报的出口是「停用规则」或「静默」：前者停止判定，后者只停止打扰。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertInstanceService {

    /**
     * 分页查询告警实例（全局列表与设备页签共用：带上 {@code deviceId} 即为设备视角）。
     *
     * @param query 查询条件
     * @return 分页结果（含通知投递记录）
     */
    PageResult<AlertInstanceResp> page(AlertInstanceQuery query);

    /**
     * 实例详情（含投递记录与状态迁移时间线）。
     *
     * @param id 实例 ID
     * @return 实例视图
     */
    AlertInstanceResp detail(Long id);

    /**
     * 概览摘要（{@code deviceId} 为 {@code null} 时是整租户）。
     *
     * @param deviceId 设备 ID（可空）
     * @return 摘要（永不为 {@code null}）
     */
    AlertSummaryResp summary(Long deviceId);

    /**
     * 按设备批量取活动告警数（设备台账列表的标记列；一次聚合查询，避免 N+1）。
     *
     * @param deviceIds 设备 ID（空则返回空 Map）
     * @return 设备 ID → 活动告警数（无告警的设备不出现）
     */
    Map<Long, Integer> activeCountsByDevice(List<Long> deviceIds);

    /**
     * 批量确认（一键 ACK）。已恢复或已确认的行**不受影响**（CAS 更新），返回的是实际生效行数。
     *
     * @param ids 实例 ID
     * @return 实际确认条数
     */
    int ack(List<Long> ids);

    /**
     * 批量静默（**不是状态**：只推迟通知；判定与状态机不受影响）。
     *
     * @param ids     实例 ID
     * @param minutes 静默时长（分钟）
     * @return 实际生效条数
     */
    int silence(List<Long> ids, int minutes);

    /**
     * 把指定规则的**活动实例**收口为 {@code RESOLVED/RULE_DISABLED}（停用规则时调用），
     * 并按规则的通知渠道发一次恢复通知。
     *
     * @param ruleIds 规则 ID（空则直接返回 0）
     * @return 收口的实例数
     */
    int resolveByRuleIds(List<Long> ruleIds);
}
