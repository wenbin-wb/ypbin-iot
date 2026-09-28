/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.AlertAckReq;
import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.req.AlertRuleEnableReq;
import cn.ypbin.admin.iot.model.req.AlertRuleQuery;
import cn.ypbin.admin.iot.model.req.AlertRuleSaveReq;
import cn.ypbin.admin.iot.model.req.AlertSilenceReq;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertPresetResp;
import cn.ypbin.admin.iot.model.resp.AlertRuleResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.service.AlertRuleService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.log.annotation.Log;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警与阈值接口（全局告警列表 + 规则管理 + 一键操作）。
 *
 * <p>路径与网关口径：客户端调 {@code /iot/alerts/**}，网关 {@code StripPrefix=1} 后落到本控制器的
 * {@code /alerts/**}（与 {@code IotDeviceController} 同构）。</p>
 *
 * <p>🔴 <b>维持「HTTP 200 + {@code R.code}」惯例</b>：全仓唯一的真状态码例外只属于
 * {@code /internal/mqtt/**}。业务失败（未启用告警、规则组合非法、状态码非法）一律走 {@code R.code}
 * 信封，由全局异常处理器统一成 200；前端据 {@code code} 判定三态。</p>
 *
 * <p><b>权限码四个，语义边界清晰</b>：{@code iot:alert:list}（查告警）、{@code iot:alert:ack}
 * （确认与静默——两者都是「通知侧」动作）、{@code iot:alert:rule-list}（查规则）、
 * {@code iot:alert:rule-save}（建/改/启停规则）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
public class IotAlertController {

    private final AlertInstanceService alertInstanceService;

    private final AlertRuleService alertRuleService;

    /**
     * 分页查询告警（全局列表）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:alert:list")
    public R<PageResult<AlertInstanceResp>> page(@Valid AlertInstanceQuery query) {
        return R.ok(alertInstanceService.page(query));
    }

    /**
     * 告警详情（含投递记录）。
     *
     * @param id 实例 ID
     * @return 实例视图
     */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:alert:list")
    public R<AlertInstanceResp> detail(@PathVariable("id") Long id) {
        return R.ok(alertInstanceService.detail(id));
    }

    /**
     * 概览摘要（整租户）。
     *
     * @return 摘要
     */
    @GetMapping("/summary")
    @SaCheckPermission("iot:alert:list")
    public R<AlertSummaryResp> summary() {
        return R.ok(alertInstanceService.summary(null));
    }

    /**
     * 批量取设备的活动告警数（设备台账列表的标记列；传当前页的设备 ID，一次查完）。
     *
     * @param deviceIds 设备 ID（逗号分隔）
     * @return 设备 ID → 活动告警数（无告警的设备不出现）
     */
    @GetMapping("/active-counts")
    @SaCheckPermission("iot:alert:list")
    public R<Map<Long, Integer>> activeCounts(@RequestParam("deviceIds") List<Long> deviceIds) {
        return R.ok(alertInstanceService.activeCountsByDevice(deviceIds));
    }

    /**
     * 一键确认（单个也走这里：{@code ids} 传一个元素）。
     *
     * @param req 确认请求
     * @return 实际确认条数
     */
    @PostMapping("/ack")
    @SaCheckPermission("iot:alert:ack")
    @Log("确认 IoT 告警")
    public R<Integer> ack(@Valid @RequestBody AlertAckReq req) {
        return R.ok(alertInstanceService.ack(req.getIds()));
    }

    /**
     * 一键静默（**不是状态**：只推迟通知，判定与状态机不受影响）。
     *
     * @param req 静默请求
     * @return 实际生效条数
     */
    @PostMapping("/silence")
    @SaCheckPermission("iot:alert:ack")
    @Log("静默 IoT 告警")
    public R<Integer> silence(@Valid @RequestBody AlertSilenceReq req) {
        return R.ok(alertInstanceService.silence(req.getIds(), req.getMinutes()));
    }

    /**
     * 一键预设模板清单（「傻瓜式」入口：选设备+点位+数字即可）。
     *
     * @return 模板清单
     */
    @GetMapping("/presets")
    @SaCheckPermission("iot:alert:rule-list")
    public R<List<AlertPresetResp>> presets() {
        return R.ok(alertRuleService.presets());
    }

    /**
     * 分页查询规则。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping("/rules")
    @SaCheckPermission("iot:alert:rule-list")
    public R<PageResult<AlertRuleResp>> rulePage(@Valid AlertRuleQuery query) {
        return R.ok(alertRuleService.page(query));
    }

    /**
     * 规则详情。
     *
     * @param id 规则 ID
     * @return 规则视图
     */
    @GetMapping("/rules/{id}")
    @SaCheckPermission("iot:alert:rule-list")
    public R<AlertRuleResp> ruleDetail(@PathVariable("id") Long id) {
        return R.ok(alertRuleService.detail(id));
    }

    /**
     * 新建规则。
     *
     * @param req 保存请求
     * @return 新规则视图
     */
    @PostMapping("/rules")
    @SaCheckPermission("iot:alert:rule-save")
    @Log("新建 IoT 告警规则")
    public R<AlertRuleResp> createRule(@Valid @RequestBody AlertRuleSaveReq req) {
        return R.ok(alertRuleService.create(req));
    }

    /**
     * 修改规则（不影响已产生的实例：实例上存了级别与阈值快照）。
     *
     * @param id  规则 ID
     * @param req 保存请求
     * @return 更新后的规则视图
     */
    @PutMapping("/rules/{id}")
    @SaCheckPermission("iot:alert:rule-save")
    @Log("修改 IoT 告警规则")
    public R<AlertRuleResp> updateRule(@PathVariable("id") Long id,
                                       @Valid @RequestBody AlertRuleSaveReq req) {
        return R.ok(alertRuleService.update(id, req));
    }

    /**
     * 批量启用/停用规则（**停用不删除**；停用会收口其活动实例）。
     *
     * @param req 启停请求
     * @return 实际变更的规则数
     */
    @PostMapping("/rules/enabled")
    @SaCheckPermission("iot:alert:rule-save")
    @Log("启停 IoT 告警规则")
    public R<Integer> setRuleEnabled(@Valid @RequestBody AlertRuleEnableReq req) {
        return R.ok(alertRuleService.setEnabled(req.getIds(), Boolean.TRUE.equals(req.getEnabled())));
    }
}
