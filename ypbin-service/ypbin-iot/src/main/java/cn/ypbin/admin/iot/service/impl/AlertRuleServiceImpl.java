/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.alert.AlertGate;
import cn.ypbin.admin.iot.alert.AlertProperties;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertOperator;
import cn.ypbin.admin.iot.enums.AlertRulePreset;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertTriggerMode;
import cn.ypbin.admin.iot.enums.AlertValueType;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRulePointMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.req.AlertRulePointReq;
import cn.ypbin.admin.iot.model.req.AlertRuleQuery;
import cn.ypbin.admin.iot.model.req.AlertRuleSaveReq;
import cn.ypbin.admin.iot.model.resp.AlertPresetResp;
import cn.ypbin.admin.iot.model.resp.AlertRulePointResp;
import cn.ypbin.admin.iot.model.resp.AlertRuleResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.service.AlertRuleService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.security.core.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警规则服务实现（设计 §2.1 表 A/B）。
 *
 * <p><b>「傻瓜式」的后端职责</b>：把「用户填的东西不合法」变成一句人话，而不是让非法组合静默按最宽的
 * 作用域生效（设计 §3.5-N3 明确要求「明确报错，不静默按最宽处理」）。因此本类的校验是**显式且穷举**的：
 * 作用域字段组合、点位条件形态、渠道码、阈值数值形态，逐条给出可操作的中文说明。</p>
 *
 * <p><b>修改规则不影响已有实例</b>：实例上冗余存了 {@code severity} 与 {@code threshold_snapshot}
 * （设计 §3.3-U4）——「上周为什么报警」不能因为今天改了阈值就变样。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Service
public class AlertRuleServiceImpl implements AlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleServiceImpl.class);

    private final IotAlertRuleMapper ruleMapper;
    private final IotAlertRulePointMapper pointMapper;
    private final IotAlertInstanceMapper instanceMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotProductMapper productMapper;
    private final AlertInstanceService alertInstanceService;
    private final AlertGate gate;
    private final AlertProperties properties;

    public AlertRuleServiceImpl(IotAlertRuleMapper ruleMapper, IotAlertRulePointMapper pointMapper,
                                IotAlertInstanceMapper instanceMapper, IotDeviceMapper deviceMapper,
                                IotProductMapper productMapper, AlertInstanceService alertInstanceService,
                                AlertGate gate, AlertProperties properties) {
        this.ruleMapper = ruleMapper;
        this.pointMapper = pointMapper;
        this.instanceMapper = instanceMapper;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.alertInstanceService = alertInstanceService;
        this.gate = gate;
        this.properties = properties;
    }

    @Override
    public PageResult<AlertRuleResp> page(AlertRuleQuery query) {
        gate.requireEnabled();
        int pageNo = positive(query.getPage(), 1);
        int pageSize = Math.min(positive(query.getPageSize(), 10), properties.getMaxPageSize());
        LambdaQueryWrapper<IotAlertRule> wrapper = new LambdaQueryWrapper<>();
        if (query.getScopeType() != null && !query.getScopeType().isBlank()) {
            wrapper.eq(IotAlertRule::getScopeType, query.getScopeType().trim());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(IotAlertRule::getEnabled, query.getEnabled() == 1);
        }
        if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            wrapper.eq(IotAlertRule::getSeverity, query.getSeverity().trim());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(IotAlertRule::getRuleName, query.getKeyword().trim());
        }
        if (query.getDeviceId() != null) {
            wrapper.eq(IotAlertRule::getScopeDeviceId, query.getDeviceId());
        }
        if (query.getProductId() != null) {
            wrapper.eq(IotAlertRule::getScopeProductId, query.getProductId());
        }
        wrapper.orderByDesc(IotAlertRule::getId);
        IPage<IotAlertRule> source = ruleMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<AlertRuleResp> items = toRespList(source.getRecords());
        return PageResult.of(items, source.getTotal(), source.getCurrent(), source.getSize());
    }

    @Override
    public AlertRuleResp detail(Long id) {
        gate.requireEnabled();
        IotAlertRule rule = requireRule(id);
        List<AlertRuleResp> converted = toRespList(List.of(rule));
        return converted.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertRuleResp create(AlertRuleSaveReq req) {
        gate.requireWritable();
        Validated validated = validate(req);
        IotAlertRule rule = new IotAlertRule();
        apply(rule, req, validated);
        rule.setCreateUser(UserContext.getUserId());
        ruleMapper.insert(rule);
        // ⚠️ 租户插件在 INSERT 上**只往 SQL 里加 tenant_id，不会回填实体字段** ⇒
        // 直接拿 rule.getTenantId() 去写条件行会得到 NULL（生产实测：Column 'tenant_id' cannot be null）。
        // 因此这里显式回读一次（主键查询，代价一次点查），拿不准就明确失败、绝不带 NULL 落库。
        insertPoints(rule, validated, requireTenantId(rule));
        // ruleName 是用户输入（含换行即跨行伪造日志），必须 sanitize（CodeQL java/log-injection）
        log.info("[iot] 告警规则已创建：id={} name={} scope={} 点位条件 {} 条", rule.getId(),
            LogSanitizer.sanitize(rule.getRuleName()), rule.getScopeType(), validated.points().size());
        return detail(rule.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertRuleResp update(Long id, AlertRuleSaveReq req) {
        gate.requireWritable();
        IotAlertRule rule = requireRule(id);
        Validated validated = validate(req);
        apply(rule, req, validated);
        rule.setUpdateUser(UserContext.getUserId());
        ruleMapper.updateById(rule);
        // 「先删后插」只作用于本规则：条件行没有外部引用，重建比逐行 diff 更不容易留下半更新状态
        pointMapper.deleteByRuleId(id);
        insertPoints(rule, validated, requireTenantId(rule));
        log.info("[iot] 告警规则已修改：id={} name={} 点位条件 {} 条（已产生的实例不受影响）", id,
            LogSanitizer.sanitize(rule.getRuleName()), validated.points().size());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int setEnabled(List<Long> ids, boolean enabled) {
        gate.requireWritable();
        if (ids == null || ids.isEmpty()) {
            // 空集合直接返回（禁止 `IN ()`，也不做「无参数即全量」的危险默认）
            return 0;
        }
        List<IotAlertRule> rules = ruleMapper.selectByIds(ids);
        if (rules.isEmpty()) {
            return 0;
        }
        // 先算出「真正需要变更」的 id（纯内存判定），再用**一条语句**批量落库——
        // 循环里逐条 updateById 是 N 次往返，且被架构门禁禁止（N+1 零容忍）
        List<Long> toChange = new ArrayList<>();
        List<Long> disabledIds = new ArrayList<>();
        for (IotAlertRule rule : rules) {
            if (Boolean.valueOf(enabled).equals(rule.getEnabled())) {
                continue;
            }
            toChange.add(rule.getId());
            if (!enabled) {
                disabledIds.add(rule.getId());
            }
        }
        if (toChange.isEmpty()) {
            return 0;
        }
        int changed = ruleMapper.batchSetEnabled(toChange, enabled, UserContext.getUserId());
        if (!disabledIds.isEmpty()) {
            // 停用后该规则不再被评估 ⇒ 必须现在就收口它的活动实例，否则会留下永不消解的幽灵告警
            int resolved = alertInstanceService.resolveByRuleIds(disabledIds);
            log.info("[iot] 告警规则停用：{} 条，收口活动实例 {} 条（reason=RULE_DISABLED）",
                disabledIds.size(), resolved);
        }
        return changed;
    }

    @Override
    public List<AlertPresetResp> presets() {
        gate.requireEnabled();
        List<AlertPresetResp> presets = new ArrayList<>();
        for (AlertRulePreset preset : AlertRulePreset.values()) {
            AlertPresetResp resp = new AlertPresetResp();
            resp.setCode(preset.getCode());
            resp.setI18nKey(preset.getI18nKey());
            resp.setDefaultScopeType(preset.getDefaultScopeType().getCode());
            resp.setDefaultOperator(preset.getDefaultOperator() == null
                ? null : preset.getDefaultOperator().getCode());
            resp.setNeedsPointCondition(preset.isNeedsPointCondition());
            resp.setDefaultSeverity(preset.getDefaultSeverity().getCode());
            // 非点位模板没有抖动抑制（判定由断档链路承担），给「立即」以免用户误以为要凑次数
            resp.setDefaultTriggerMode(preset.isNeedsPointCondition()
                ? AlertTriggerMode.CONSECUTIVE_COUNT.getCode() : AlertTriggerMode.IMMEDIATE.getCode());
            resp.setDefaultTriggerThreshold(preset.isNeedsPointCondition()
                ? properties.getDefaultTriggerThreshold() : 0);
            resp.setDefaultPendingTtlSec(properties.getDefaultPendingTtlSec());
            // 预设未指定（0）时回落到**平台默认值**（口径只能有一处来源：AlertProperties + nacos）
            resp.setDefaultRepeatIntervalSec(preset.getDefaultRepeatIntervalSec() > 0
                ? preset.getDefaultRepeatIntervalSec() : properties.getDefaultRepeatIntervalSec());
            resp.setDefaultNotifyChannels(properties.getDefaultNotifyChannels());
            presets.add(resp);
        }
        return presets;
    }

    /** 校验并归一化保存请求。 */
    private Validated validate(AlertRuleSaveReq req) {
        AlertScopeType scope = AlertScopeType.of(req.getScopeType());
        if (scope == null) {
            throw new BusinessException("作用域只能是「租户 / 产品 / 设备 / 点位」之一");
        }
        AlertSeverity severity = AlertSeverity.of(req.getSeverity());
        if (req.getSeverity() != null && !req.getSeverity().isBlank() && severity == null) {
            throw new BusinessException("级别只能是「提示 / 警告 / 严重」之一");
        }
        AlertTriggerMode mode = AlertTriggerMode.of(req.getTriggerMode());
        if (req.getTriggerMode() != null && !req.getTriggerMode().isBlank() && mode == null) {
            throw new BusinessException("触发方式只能是「立即 / 连续 N 次 / 持续 T 秒」之一");
        }
        validateScopeFields(scope, req);
        if (req.getNotifyChannels() != null && !req.getNotifyChannels().isBlank()) {
            try {
                AlertChannel.parse(req.getNotifyChannels());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("通知渠道只支持「站内信 / 邮件」（Webhook 本期不做）："
                    + ex.getMessage());
            }
        }
        List<AlertRulePointReq> rawPoints = req.getPoints() == null ? List.of() : req.getPoints();
        if (rawPoints.size() > properties.getMaxPointsPerRule()) {
            throw new BusinessException("单条规则最多 " + properties.getMaxPointsPerRule()
                + " 个点位条件，当前 " + rawPoints.size() + " 个");
        }
        if (scope == AlertScopeType.POINT && rawPoints.isEmpty()) {
            throw new BusinessException("点位级规则必须至少配置一个点位条件；"
                + "如果只是想让这台设备掉线时告警，请把作用域选成「设备」");
        }
        AlertTriggerMode effectiveMode = mode == null
            ? AlertTriggerMode.CONSECUTIVE_COUNT : mode;
        int threshold = resolveThreshold(effectiveMode, req.getTriggerThreshold());
        List<IotAlertRulePoint> points = new ArrayList<>(rawPoints.size());
        for (AlertRulePointReq item : rawPoints) {
            points.add(buildPoint(item));
        }
        return new Validated(scope, severity, effectiveMode, threshold, points);
    }

    /** 作用域字段组合校验（设计 §3.5-N3：非法组合必须明确报错，不静默按最宽处理）。 */
    private void validateScopeFields(AlertScopeType scope, AlertRuleSaveReq req) {
        switch (scope) {
            case TENANT -> {
                requireAbsent(req.getScopeProductId(), "租户级规则不能指定产品（那会把作用域缩小成产品级）");
                requireAbsent(req.getScopeDeviceId(), "租户级规则不能指定设备（那会把作用域缩小成设备级）");
            }
            case PRODUCT -> {
                requirePresent(req.getScopeProductId(), "产品级规则必须选择产品");
                requireAbsent(req.getScopeDeviceId(), "产品级规则不能指定设备（那会把作用域缩小成设备级）");
            }
            case DEVICE -> {
                requirePresent(req.getScopeDeviceId(), "设备级规则必须选择设备");
                requireAbsent(req.getScopeProductId(), "设备级规则不能指定产品（那会让它作用到该产品下所有设备）");
            }
            case POINT -> {
                requirePresent(req.getScopeProductId(), "点位级规则必须选择产品");
                requirePresent(req.getScopeDeviceId(), "点位级规则必须选择设备");
            }
            default -> throw new BusinessException("不支持的作用域");
        }
        if (req.getScopeDeviceId() != null) {
            IotDevice device = deviceMapper.selectById(req.getScopeDeviceId());
            if (device == null) {
                // 租户插件已按上下文过滤 ⇒ 跨租户设备在这里表现为「不存在」，不会越过租户边界（设计 §3.5-N4）
                throw new BusinessException("设备不存在或不属于当前租户");
            }
            if (req.getScopeProductId() != null && device.getProductId() != null
                && !device.getProductId().equals(req.getScopeProductId())) {
                throw new BusinessException("所选设备不属于所选产品（请重新选择产品或设备）");
            }
        }
        if (req.getScopeProductId() != null && productMapper.selectById(req.getScopeProductId()) == null) {
            throw new BusinessException("产品不存在或不属于当前租户");
        }
        if (req.getSilenceStart() != null && req.getSilenceEnd() != null
            && req.getSilenceEnd().isBefore(req.getSilenceStart())) {
            throw new BusinessException("静默结束时间不能早于开始时间");
        }
    }

    /** 点位条件行校验（阈值必须是数字、布尔点位不能用大小比较等）。 */
    private IotAlertRulePoint buildPoint(AlertRulePointReq req) {
        AlertOperator operator = AlertOperator.of(req.getOperator());
        if (operator == null) {
            throw new BusinessException("比较符只能是「大于 / 大于等于 / 小于 / 小于等于 / 等于 / 不等于」之一");
        }
        AlertValueType valueType = req.getValueType() == null || req.getValueType().isBlank()
            ? AlertValueType.NUMERIC : AlertValueType.of(req.getValueType());
        if (valueType == null) {
            throw new BusinessException("点位类型只能是「数值 / 布尔」之一");
        }
        BigDecimal threshold = parseDecimal(req.getThreshold(), "阈值");
        BigDecimal deadband = req.getDeadband() == null || req.getDeadband().isBlank()
            ? null : parseDecimal(req.getDeadband(), "回差");
        if (deadband != null && deadband.signum() < 0) {
            throw new BusinessException("回差不能是负数");
        }
        if (valueType == AlertValueType.BOOLEAN) {
            if (operator != AlertOperator.EQ && operator != AlertOperator.NE) {
                throw new BusinessException("布尔点位只支持「等于 / 不等于」，不支持大小比较");
            }
            if (threshold.signum() != 0 && threshold.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException("布尔点位的阈值只能是 1（真）或 0（假）");
            }
        }
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setId(IdWorker.getId());
        point.setPropertyId(req.getPropertyId().trim());
        point.setOperator(operator.getCode());
        point.setThreshold(threshold);
        point.setValueType(valueType.getCode());
        point.setDeadband(deadband);
        return point;
    }

    /** 阈值解析：失败时给出**人话**（用户口径 ⑤：不弹原始异常码）。 */
    private static BigDecimal parseDecimal(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(field + "不能为空");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(field + "必须是数字（当前填的是「" + raw.trim() + "」）");
        }
    }

    /** 生效的抖动抑制参数（缺省用平台默认；持续模式要求 ≥ 1 秒）。 */
    private int resolveThreshold(AlertTriggerMode mode, Integer configured) {
        if (mode == AlertTriggerMode.IMMEDIATE) {
            return 0;
        }
        int value = configured == null ? 0 : configured;
        if (value <= 0) {
            value = mode == AlertTriggerMode.CONSECUTIVE_COUNT ? properties.getDefaultTriggerThreshold() : 0;
        }
        if (value <= 0) {
            throw new BusinessException(mode == AlertTriggerMode.CONSECUTIVE_COUNT
                ? "「连续 N 次」的 N 必须大于 0" : "「持续 T 秒」的 T 必须大于 0");
        }
        return value;
    }

    /** 把校验结果写进实体。 */
    private void apply(IotAlertRule rule, AlertRuleSaveReq req, Validated validated) {
        rule.setRuleName(req.getRuleName().trim());
        rule.setScopeType(validated.scope().getCode());
        rule.setScopeProductId(req.getScopeProductId());
        rule.setScopeDeviceId(req.getScopeDeviceId());
        rule.setSeverity(validated.severity() == null
            ? properties.getDefaultSeverity() : validated.severity().getCode());
        rule.setEnabled(req.getEnabled() == null || req.getEnabled());
        rule.setTriggerMode(validated.mode().getCode());
        rule.setTriggerThreshold(validated.threshold());
        rule.setPendingTtlSec(positive(req.getPendingTtlSec(), properties.getDefaultPendingTtlSec()));
        rule.setRepeatIntervalSec(positive(req.getRepeatIntervalSec(),
            properties.getDefaultRepeatIntervalSec()));
        rule.setSilenceStart(req.getSilenceStart());
        rule.setSilenceEnd(req.getSilenceEnd());
        rule.setNotifyChannels(req.getNotifyChannels() == null || req.getNotifyChannels().isBlank()
            ? properties.getDefaultNotifyChannels() : req.getNotifyChannels().trim());
        rule.setNotifyTargets(req.getNotifyTargets() == null || req.getNotifyTargets().isBlank()
            ? null : req.getNotifyTargets().trim());
        rule.setDescription(req.getDescription());
    }

    /**
     * 取规则所属租户（实体字段为空时回读一次）。
     *
     * <p>为什么必须回读：MyBatis-Plus 的租户插件在 INSERT 时只改 SQL、不回填实体 ⇒ 直接读实体的
     * {@code tenantId} 会拿到 {@code null}，而条件行是**带 tenant_id 的显式批量插入**，落库会被拒
     * （生产演示实测：`Column 'tenant_id' cannot be null`）。</p>
     */
    private Long requireTenantId(IotAlertRule rule) {
        Long tenantId = rule.getTenantId();
        if (tenantId != null) {
            return tenantId;
        }
        IotAlertRule saved = ruleMapper.selectById(rule.getId());
        tenantId = saved == null ? null : saved.getTenantId();
        if (tenantId == null) {
            // 拿不到租户就不写：宁可让这次保存失败并明确报错，也不要留下 tenant_id 为空的行
            throw new BusinessException("无法确定规则所属租户，保存已中止（请联系平台管理员检查租户上下文）");
        }
        return tenantId;
    }

    /** 批量插入条件行（单条语句，不在循环里做 DB 调用）。 */
    private void insertPoints(IotAlertRule rule, Validated validated, Long tenantId) {
        if (validated.points().isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Long userId = UserContext.getUserId();
        for (IotAlertRulePoint point : validated.points()) {
            point.setTenantId(tenantId);
            point.setRuleId(rule.getId());
            point.setCreateUser(userId);
            point.setCreateTime(now);
            point.setUpdateUser(userId);
            point.setUpdateTime(now);
            point.setStatus(EntityStatus.ENABLED.getCode());
            point.setIsDeleted(0);
        }
        pointMapper.insertBatch(validated.points());
    }

    /** 批量补齐展示名（设备/产品各一次查询；活动计数一次聚合查询）。 */
    private List<AlertRuleResp> toRespList(List<IotAlertRule> rules) {
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = new ArrayList<>(rules.size());
        Set<Long> deviceIds = new LinkedHashSet<>();
        Set<Long> productIds = new LinkedHashSet<>();
        for (IotAlertRule rule : rules) {
            ruleIds.add(rule.getId());
            if (rule.getScopeDeviceId() != null) {
                deviceIds.add(rule.getScopeDeviceId());
            }
            if (rule.getScopeProductId() != null) {
                productIds.add(rule.getScopeProductId());
            }
        }
        Map<Long, IotDevice> devices = new LinkedHashMap<>();
        if (!deviceIds.isEmpty()) {
            for (IotDevice device : deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .select(IotDevice::getId, IotDevice::getDeviceName, IotDevice::getDeviceCode)
                .in(IotDevice::getId, new ArrayList<>(deviceIds)))) {
                devices.put(device.getId(), device);
            }
        }
        Map<Long, IotProduct> products = new LinkedHashMap<>();
        if (!productIds.isEmpty()) {
            for (IotProduct product : productMapper.selectList(new LambdaQueryWrapper<IotProduct>()
                .select(IotProduct::getId, IotProduct::getProductName)
                .in(IotProduct::getId, new ArrayList<>(productIds)))) {
                products.put(product.getId(), product);
            }
        }
        Map<Long, List<IotAlertRulePoint>> pointsByRuleId = new LinkedHashMap<>();
        for (IotAlertRulePoint point : pointMapper.selectByRuleIds(ruleIds)) {
            pointsByRuleId.computeIfAbsent(point.getRuleId(), key -> new ArrayList<>()).add(point);
        }
        Map<Long, Integer> activeByRule = new LinkedHashMap<>();
        for (Map<String, Object> row : instanceMapper.countActiveByRuleIds(ruleIds)) {
            activeByRule.put(asLong(row.get("ruleId")), asInt(row.get("activeCount")));
        }
        List<AlertRuleResp> result = new ArrayList<>(rules.size());
        for (IotAlertRule rule : rules) {
            result.add(toResp(rule, pointsByRuleId.get(rule.getId()), devices, products, activeByRule));
        }
        return result;
    }

    private AlertRuleResp toResp(IotAlertRule rule, List<IotAlertRulePoint> points,
                                 Map<Long, IotDevice> devices, Map<Long, IotProduct> products,
                                 Map<Long, Integer> activeByRule) {
        AlertRuleResp resp = new AlertRuleResp();
        resp.setId(rule.getId());
        resp.setRuleName(rule.getRuleName());
        resp.setScopeType(rule.getScopeType());
        resp.setScopeProductId(rule.getScopeProductId());
        resp.setScopeDeviceId(rule.getScopeDeviceId());
        resp.setSeverity(rule.getSeverity());
        resp.setEnabled(rule.getEnabled());
        resp.setTriggerMode(rule.getTriggerMode());
        resp.setTriggerThreshold(rule.getTriggerThreshold());
        resp.setPendingTtlSec(rule.getPendingTtlSec());
        resp.setRepeatIntervalSec(rule.getRepeatIntervalSec());
        resp.setSilenceStart(rule.getSilenceStart());
        resp.setSilenceEnd(rule.getSilenceEnd());
        resp.setNotifyChannels(rule.getNotifyChannels());
        resp.setNotifyTargets(rule.getNotifyTargets());
        resp.setDescription(rule.getDescription());
        resp.setCreateTime(rule.getCreateTime());
        resp.setUpdateTime(rule.getUpdateTime());
        resp.setActiveCount(activeByRule.getOrDefault(rule.getId(), 0));
        if (rule.getScopeDeviceId() != null) {
            IotDevice device = devices.get(rule.getScopeDeviceId());
            if (device != null) {
                resp.setDeviceName(device.getDeviceName());
                resp.setDeviceCode(device.getDeviceCode());
            }
        }
        if (rule.getScopeProductId() != null) {
            IotProduct product = products.get(rule.getScopeProductId());
            if (product != null) {
                resp.setProductName(product.getProductName());
            }
        }
        List<AlertRulePointResp> pointResps = new ArrayList<>();
        if (points != null) {
            for (IotAlertRulePoint point : points) {
                AlertRulePointResp item = new AlertRulePointResp();
                item.setId(point.getId());
                item.setPropertyId(point.getPropertyId());
                item.setOperator(point.getOperator());
                item.setThreshold(point.getThreshold() == null
                    ? null : point.getThreshold().stripTrailingZeros().toPlainString());
                item.setValueType(point.getValueType());
                item.setDeadband(point.getDeadband() == null
                    ? null : point.getDeadband().stripTrailingZeros().toPlainString());
                pointResps.add(item);
            }
        }
        resp.setPoints(pointResps);
        return resp;
    }

    private IotAlertRule requireRule(Long id) {
        if (id == null) {
            throw new BusinessException("规则 ID 不能为空");
        }
        IotAlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException("告警规则不存在或不属于当前租户");
        }
        return rule;
    }

    private static void requirePresent(Long value, String message) {
        if (value == null) {
            throw new BusinessException(message);
        }
    }

    private static void requireAbsent(Long value, String message) {
        if (value != null) {
            throw new BusinessException(message);
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 校验结果（避免把一堆局部变量在方法之间来回传）。
     *
     * @param scope     作用域
     * @param severity  级别（{@code null} = 用平台默认）
     * @param mode      抖动抑制模式
     * @param threshold 抖动抑制参数
     * @param points    已校验的条件行
     */
    private record Validated(AlertScopeType scope, AlertSeverity severity, AlertTriggerMode mode,
                             int threshold, List<IotAlertRulePoint> points) {
    }

}
