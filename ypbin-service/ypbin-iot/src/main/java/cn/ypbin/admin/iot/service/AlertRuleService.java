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

import cn.ypbin.admin.iot.model.req.AlertRuleQuery;
import cn.ypbin.admin.iot.model.req.AlertRuleSaveReq;
import cn.ypbin.admin.iot.model.resp.AlertPresetResp;
import cn.ypbin.admin.iot.model.resp.AlertRuleResp;
import cn.ypbin.starter.crud.model.PageResult;
import java.util.List;

/**
 * 告警规则服务（设计 §2.1 表 A/B 的读写面）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertRuleService {

    /**
     * 分页查询规则。
     *
     * @param query 查询条件
     * @return 分页结果（含点位条件、解析后的设备/产品名、活动告警数）
     */
    PageResult<AlertRuleResp> page(AlertRuleQuery query);

    /**
     * 规则详情。
     *
     * @param id 规则 ID
     * @return 规则视图
     */
    AlertRuleResp detail(Long id);

    /**
     * 新建规则。
     *
     * @param req 保存请求
     * @return 新规则视图
     */
    AlertRuleResp create(AlertRuleSaveReq req);

    /**
     * 修改规则（**不影响已产生的实例**：实例上存了级别与阈值快照，设计 §3.3-U4）。
     *
     * @param id  规则 ID
     * @param req 保存请求
     * @return 更新后的规则视图
     */
    AlertRuleResp update(Long id, AlertRuleSaveReq req);

    /**
     * 批量启用/停用（**停用不删除**；停用会收口该规则的活动实例为
     * {@code RESOLVED/RULE_DISABLED}，否则会留下永不消解的幽灵告警）。
     *
     * @param ids     规则 ID
     * @param enabled 目标状态
     * @return 实际变更的规则数
     */
    int setEnabled(List<Long> ids, boolean enabled);

    /**
     * 一键预设模板清单（用户口径 ①：选设备+点位+数字即可，不用懂任何术语）。
     *
     * @return 模板清单（含默认值，标题与说明由前端按 i18n 渲染）
     */
    List<AlertPresetResp> presets();
}
