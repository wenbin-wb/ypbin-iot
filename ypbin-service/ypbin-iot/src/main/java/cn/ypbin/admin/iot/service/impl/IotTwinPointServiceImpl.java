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

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.PointRefType;
import cn.ypbin.admin.iot.enums.TwinDisplayRole;
import cn.ypbin.admin.iot.enums.TwinDisplayType;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.req.TwinBindingReq;
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import cn.ypbin.admin.iot.model.resp.TwinBindingResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 数字孪生点位服务实现（锚点 + 属性绑定，编辑时整体替换绑定）。
 *
 * <p><b>绑定为什么整体替换而不是逐条 CRUD</b>：一次点位编辑的用户心智是「这个锚点最终展示
 * 这 N 个属性」，逐条 diff 会引出「先删后加导致唯一键冲突」「前端要维护待删除列表」等
 * 一连串状态问题。整体替换只有一条规则：请求里的列表 = 保存后的列表。</p>
 *
 * <p><b>绑定唯一键不含 is_deleted</b>（沿用 006 的「逻辑删除后业务键不可复用」约定）⇒
 * 替换必须先<b>物理删除</b>旧行再插入，否则「删掉某属性再绑回来」会主键冲突
 * （见 {@code IotTwinPointBindingMapper#physicalDeleteByPointIds}）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinPointServiceImpl extends BaseServiceImpl<IotTwinPointMapper, IotTwinPoint>
    implements IotTwinPointService {

    private final IotTwinSceneMapper twinSceneMapper;

    private final IotTwinPointBindingMapper twinPointBindingMapper;

    private final IotDeviceMapper deviceMapper;

    private final IotProductMapper productMapper;

    private final IotServiceMapper serviceMapper;

    private final IotPropertyMapper propertyMapper;

    private final IotPointMappingMapper pointMappingMapper;

    private final TwinBindingResolver bindingResolver;

    public IotTwinPointServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                   IotTwinPointBindingMapper twinPointBindingMapper,
                                   IotDeviceMapper deviceMapper,
                                   IotProductMapper productMapper,
                                   IotServiceMapper serviceMapper,
                                   IotPropertyMapper propertyMapper,
                                   IotPointMappingMapper pointMappingMapper,
                                   TwinBindingResolver bindingResolver) {
        this.twinSceneMapper = twinSceneMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.serviceMapper = serviceMapper;
        this.propertyMapper = propertyMapper;
        this.pointMappingMapper = pointMappingMapper;
        this.bindingResolver = bindingResolver;
    }

    @Override
    public List<IotTwinPointResp> listByScene(Long sceneId) {
        requireScene(sceneId);
        List<IotTwinPoint> points = baseMapper.selectList(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .orderByAsc(IotTwinPoint::getSort)
            .orderByAsc(IotTwinPoint::getId));
        if (points.isEmpty()) {
            return List.of();
        }
        List<Long> pointIds = points.stream().map(IotTwinPoint::getId).toList();
        // 一次取回本场景全部绑定（不逐点位查）
        List<IotTwinPointBinding> bindings = twinPointBindingMapper.selectList(
            new LambdaQueryWrapper<IotTwinPointBinding>()
                .in(IotTwinPointBinding::getPointId, pointIds)
                .orderByAsc(IotTwinPointBinding::getSort)
                .orderByAsc(IotTwinPointBinding::getId));
        Map<Long, TwinBindingResolver.ResolvedBinding> resolved = bindingResolver.resolve(bindings);
        Map<Long, List<TwinBindingResp>> bindingByPoint = groupBindings(bindings, resolved);
        List<IotTwinPointResp> result = new ArrayList<>(points.size());
        for (IotTwinPoint point : points) {
            result.add(toResp(point, bindingByPoint.getOrDefault(point.getId(), List.of())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long sceneId, IotTwinPointReq req) {
        requireScene(sceneId);
        IotDevice device = requireDevice(req.getDeviceId());
        requirePointCountBelowLimit(sceneId);
        checkDeviceNotBoundInScene(sceneId, device.getId(), null);
        List<TwinBindingReq> bindings = normalizeBindings(req.getBindings());
        Long productId = device.getProductId();
        List<TwinBindingResolver.BindingKey> keys = toBindingKeys(bindings);
        bindingResolver.validateBindings(productId, keys);
        Map<String, Long> propertyIdByKey = bindingResolver.resolvePropertyIds(productId, keys);

        IotTwinPoint point = new IotTwinPoint();
        point.setSceneId(sceneId);
        apply(point, req);
        save(point);
        insertBindings(point.getId(), bindings, propertyIdByKey);
        return point.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long sceneId, Long pointId, IotTwinPointReq req) {
        requireScene(sceneId);
        IotTwinPoint point = requirePointInScene(sceneId, pointId);
        IotDevice device = requireDevice(req.getDeviceId());
        checkDeviceNotBoundInScene(sceneId, device.getId(), pointId);
        List<TwinBindingReq> bindings = normalizeBindings(req.getBindings());
        Long productId = device.getProductId();
        List<TwinBindingResolver.BindingKey> keys = toBindingKeys(bindings);
        bindingResolver.validateBindings(productId, keys);
        Map<String, Long> propertyIdByKey = bindingResolver.resolvePropertyIds(productId, keys);

        apply(point, req);
        updateById(point);
        // 整体替换：先物理删除旧绑定，再插入新绑定（见类注释）
        twinPointBindingMapper.physicalDeleteByPointIds(List.of(pointId));
        insertBindings(pointId, bindings, propertyIdByKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long sceneId, Long pointId) {
        requireScene(sceneId);
        requirePointInScene(sceneId, pointId);
        twinPointBindingMapper.physicalDeleteByPointIds(List.of(pointId));
        removeById(pointId);
    }

    @Override
    public List<IotTwinDevicePropertyResp> listDeviceProperties(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        if (device.getProductId() == null) {
            // 未绑定产品是正常空态：不是错误，页面据此提示「该设备未绑定产品」
            return List.of();
        }
        if (productMapper.selectById(device.getProductId()) == null) {
            return List.of();
        }
        List<IotService> services = serviceMapper.selectList(new LambdaQueryWrapper<IotService>()
            .eq(IotService::getProductId, device.getProductId())
            .orderByAsc(IotService::getSort)
            .orderByAsc(IotService::getId));
        if (services.isEmpty()) {
            return List.of();
        }
        List<Long> serviceIds = services.stream().map(IotService::getId).toList();
        List<IotProperty> properties = propertyMapper.selectList(new LambdaQueryWrapper<IotProperty>()
            .in(IotProperty::getServiceId, serviceIds)
            .orderByAsc(IotProperty::getSort)
            .orderByAsc(IotProperty::getId));
        if (properties.isEmpty()) {
            return List.of();
        }
        // mapped：该属性在本设备上是否已有采集点位映射（一次 IN 查询）
        Set<Long> mappedPropertyIds = new HashSet<>();
        for (IotPointMapping mapping : pointMappingMapper.selectList(
            new LambdaQueryWrapper<IotPointMapping>()
                .eq(IotPointMapping::getDeviceId, device.getId())
                .eq(IotPointMapping::getRefType, PointRefType.PROPERTY.getCode())
                .eq(IotPointMapping::getEnabled, Boolean.TRUE))) {
            mappedPropertyIds.add(mapping.getPropertyId());
        }
        Map<Long, IotService> serviceById = new HashMap<>();
        for (IotService service : services) {
            serviceById.put(service.getId(), service);
        }
        List<IotTwinDevicePropertyResp> result = new ArrayList<>(properties.size());
        for (IotProperty property : properties) {
            IotService service = serviceById.get(property.getServiceId());
            if (service == null) {
                continue;
            }
            IotTwinDevicePropertyResp resp = new IotTwinDevicePropertyResp();
            resp.setServiceId(service.getId());
            resp.setServiceIdentifier(service.getServiceId());
            resp.setServiceName(service.getServiceName());
            resp.setPropertyId(property.getId());
            resp.setPropertyIdentifier(property.getIdentifier());
            resp.setPropertyName(property.getPropertyName());
            resp.setDataType(property.getDataType());
            resp.setAccessMode(property.getAccessMode());
            resp.setUnit(property.getUnit());
            resp.setMinValue(property.getMinValue());
            resp.setMaxValue(property.getMaxValue());
            resp.setMapped(mappedPropertyIds.contains(property.getId()));
            result.add(resp);
        }
        return result;
    }

    // ---------- 校验 ----------

    private IotTwinScene requireScene(Long sceneId) {
        IotTwinScene scene = twinSceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + sceneId);
        }
        return scene;
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private IotTwinPoint requirePointInScene(Long sceneId, Long pointId) {
        IotTwinPoint point = getById(pointId);
        if (point == null || !sceneId.equals(point.getSceneId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位不存在或不属于该场景：" + pointId);
        }
        return point;
    }

    private void requirePointCountBelowLimit(Long sceneId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId));
        if (count != null && count >= TwinLimits.MAX_POINTS_PER_SCENE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单个场景最多 " + TwinLimits.MAX_POINTS_PER_SCENE + " 个设备点位（当前已有 "
                    + count + " 个）。点位过多会让运行态渲染与实时更新都不可用，"
                    + "建议拆成下级场景");
        }
    }

    private void checkDeviceNotBoundInScene(Long sceneId, Long deviceId, Long excludePointId) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .eq(IotTwinPoint::getDeviceId, deviceId)
            .ne(excludePointId != null, IotTwinPoint::getId, excludePointId));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备在本场景已有锚点：同一场景内一台设备只能有一个锚点"
                    + "（否则同一个值会显示在两处，且改一处另一处不同步）");
        }
    }

    /**
     * 归一化并校验绑定列表（枚举白名单 + 重复检测 + primary 唯一）。
     *
     * @param bindings 请求绑定列表（可 null，由本方法显式拒绝）
     * @return 归一后的列表（保留请求顺序）
     */
    private List<TwinBindingReq> normalizeBindings(List<TwinBindingReq> bindings) {
        if (bindings == null) {
            // Controller 的 @NotNull 已拦一次；服务层再拦一次是因为「清空绑定」必须显式传 []，
            // 而 null 是漏字段。若这里放行成 NPE，全局处理器会把它转成「系统内部错误」，
            // 用户拿到的错误信息完全无法行动。
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "属性绑定列表不能为空（清空绑定请传空数组）");
        }
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        int primaryCount = 0;
        List<TwinBindingReq> normalized = new ArrayList<>(bindings.size());
        for (TwinBindingReq binding : bindings) {
            TwinDisplayType displayType = TwinDisplayType.of(binding.getDisplayType());
            if (displayType == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "展示形态不合法：" + binding.getDisplayType()
                        + "（允许值：value / gauge / switch / color / sparkline）");
            }
            TwinDisplayRole displayRole = TwinDisplayRole.of(binding.getDisplayRole());
            if (displayRole == null) {
                if (StringUtils.hasText(binding.getDisplayRole())) {
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "展示角色不合法：" + binding.getDisplayRole()
                            + "（允许值：primary / secondary）");
                }
                displayRole = TwinDisplayRole.SECONDARY;
            }
            if (displayRole == TwinDisplayRole.PRIMARY) {
                primaryCount++;
            }
            String key = TwinBindingResolver.key(binding.getServiceIdentifier().trim(),
                binding.getPropertyIdentifier().trim());
            if (!seen.add(key)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "属性绑定重复：" + binding.getServiceIdentifier() + "."
                        + binding.getPropertyIdentifier());
            }
            TwinBindingReq item = new TwinBindingReq();
            item.setServiceIdentifier(binding.getServiceIdentifier().trim());
            item.setPropertyIdentifier(binding.getPropertyIdentifier().trim());
            item.setDisplayType(displayType.getCode());
            item.setDisplayRole(displayRole.getCode());
            item.setDecimals(binding.getDecimals());
            item.setUnitOverride(binding.getUnitOverride());
            item.setSort(binding.getSort() == null ? normalized.size() : binding.getSort());
            normalized.add(item);
        }
        if (primaryCount > 1) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "每个点位最多只能有 1 个主展示属性（当前 " + primaryCount
                    + " 个）：主展示是锚点旁常显的那一行，多于一个时页面无法决定显示哪个值");
        }
        return normalized;
    }

    private List<TwinBindingResolver.BindingKey> toBindingKeys(List<TwinBindingReq> bindings) {
        List<TwinBindingResolver.BindingKey> keys = new ArrayList<>(bindings.size());
        for (TwinBindingReq binding : bindings) {
            keys.add(new TwinBindingResolver.BindingKey(binding.getServiceIdentifier(),
                binding.getPropertyIdentifier()));
        }
        return keys;
    }

    // ---------- 写入 ----------

    private void apply(IotTwinPoint point, IotTwinPointReq req) {
        point.setDeviceId(req.getDeviceId());
        point.setNodeName(req.getNodeName());
        point.setLocalX(req.getLocalX());
        point.setLocalY(req.getLocalY());
        point.setLocalZ(req.getLocalZ());
        point.setNormalX(req.getNormalX() == null ? BigDecimal.ZERO : req.getNormalX());
        point.setNormalY(req.getNormalY() == null ? BigDecimal.ONE : req.getNormalY());
        point.setNormalZ(req.getNormalZ() == null ? BigDecimal.ZERO : req.getNormalZ());
        point.setIcon(req.getIcon());
        point.setLabelVisible(Boolean.FALSE.equals(req.getLabelVisible()) ? 0 : 1);
        point.setSort(req.getSort() == null ? 0 : req.getSort());
        point.setRemark(req.getRemark());
    }

    /**
     * 批量写入绑定（**一条 INSERT 语句**，不在循环里逐条 insert）。
     *
     * <p>为什么必须批量：一次点位编辑常带 3~12 条绑定，而「拖完坐标保存」是高频操作；
     * 逐条 insert 就是 3~12 次往返。仓内 CRUD 铁律明确禁止循环内 DB 调用，
     * 与 {@code IotDeviceTagServiceImpl#batchUpsertTag} 的 {@code insertBatch} 同款做法。</p>
     *
     * <p><b>显式补 id 与 tenantId</b>：原生注解 SQL 不经 MyBatis-Plus 的
     * {@code IdType.ASSIGN_ID} 注入与租户插件改写（{@code FieldFill} 只对
     * {@code BaseMapper.insert} 生效）⇒ 两者都必须由调用方填好，否则写出
     * {@code tenant_id} 为 NULL 的非法行。</p>
     */
    private void insertBindings(Long pointId, List<TwinBindingReq> bindings,
                                Map<String, Long> propertyIdByKey) {
        if (bindings.isEmpty()) {
            return;
        }
        Long tenantId = resolveTenantId();
        List<IotTwinPointBinding> rows = new ArrayList<>(bindings.size());
        for (TwinBindingReq binding : bindings) {
            IotTwinPointBinding entity = new IotTwinPointBinding();
            entity.setId(IdWorker.getId());
            entity.setTenantId(tenantId);
            entity.setPointId(pointId);
            entity.setServiceIdentifier(binding.getServiceIdentifier());
            entity.setPropertyIdentifier(binding.getPropertyIdentifier());
            entity.setPropertyId(propertyIdByKey.get(TwinBindingResolver.key(
                binding.getServiceIdentifier(), binding.getPropertyIdentifier())));
            entity.setDisplayType(binding.getDisplayType());
            entity.setDisplayRole(binding.getDisplayRole());
            entity.setDecimals(binding.getDecimals());
            entity.setUnitOverride(binding.getUnitOverride());
            entity.setSort(binding.getSort());
            rows.add(entity);
        }
        twinPointBindingMapper.insertBatch(rows);
    }

    /**
     * 取当前租户（原生批量 SQL 必须显式带 tenant_id）。
     *
     * <p>fail-closed：拿不到租户就拒绝写入，绝不写出 tenant_id 为 NULL 的行
     * （那会绕过租户隔离，且 MySQL 的 NOT NULL 约束会以「Column cannot be null」
     * 这种裸 500 形态暴露，对用户毫无意义）。</p>
     *
     * @return 当前租户主键
     */
    private Long resolveTenantId() {
        return TenantContext.getTenantId()
            .orElseThrow(() -> new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "当前登录身份没有租户上下文，无法保存点位绑定："
                    + "平台管理员需要先选择/切换到目标租户"));
    }

    // ---------- 响应转换 ----------

    private Map<Long, List<TwinBindingResp>> groupBindings(
        List<IotTwinPointBinding> bindings,
        Map<Long, TwinBindingResolver.ResolvedBinding> resolved) {
        Map<Long, List<TwinBindingResp>> grouped = new LinkedHashMap<>();
        for (IotTwinPointBinding binding : bindings) {
            TwinBindingResolver.ResolvedBinding hit = resolved.get(binding.getId());
            TwinBindingResp resp = new TwinBindingResp();
            resp.setId(binding.getId());
            resp.setServiceIdentifier(binding.getServiceIdentifier());
            resp.setPropertyIdentifier(binding.getPropertyIdentifier());
            resp.setDisplayType(binding.getDisplayType());
            resp.setDisplayRole(binding.getDisplayRole());
            resp.setDecimals(binding.getDecimals());
            resp.setUnitOverride(binding.getUnitOverride());
            resp.setSort(binding.getSort());
            if (hit != null) {
                resp.setPropertyId(hit.getPropertyId());
                resp.setPropertyName(hit.getPropertyName());
                resp.setDataType(hit.getDataType());
                resp.setUnit(hit.getUnit());
                resp.setStale(hit.getStale());
            } else {
                // 理论上不可达（resolve 对每个入参都产出结果）；保持自洽语义而不是留 null
                resp.setPropertyName(binding.getPropertyIdentifier());
                resp.setStale(Boolean.TRUE);
            }
            grouped.computeIfAbsent(binding.getPointId(), key -> new ArrayList<>()).add(resp);
        }
        for (List<TwinBindingResp> list : grouped.values()) {
            list.sort(Comparator
                .comparing((TwinBindingResp item) -> TwinDisplayRole.PRIMARY.getCode()
                    .equals(item.getDisplayRole()) ? 0 : 1)
                .thenComparing(TwinBindingResp::getSort,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TwinBindingResp::getId));
        }
        return grouped;
    }

    private IotTwinPointResp toResp(IotTwinPoint point, List<TwinBindingResp> bindings) {
        IotTwinPointResp resp = new IotTwinPointResp();
        resp.setId(point.getId());
        resp.setSceneId(point.getSceneId());
        resp.setDeviceId(point.getDeviceId());
        resp.setNodeName(point.getNodeName());
        resp.setLocalX(point.getLocalX());
        resp.setLocalY(point.getLocalY());
        resp.setLocalZ(point.getLocalZ());
        resp.setNormalX(point.getNormalX());
        resp.setNormalY(point.getNormalY());
        resp.setNormalZ(point.getNormalZ());
        resp.setIcon(point.getIcon());
        resp.setLabelVisible(Integer.valueOf(1).equals(point.getLabelVisible()));
        resp.setSort(point.getSort());
        resp.setRemark(point.getRemark());
        resp.setCreateTime(point.getCreateTime());
        resp.setBindings(bindings);
        return resp;
    }
}
