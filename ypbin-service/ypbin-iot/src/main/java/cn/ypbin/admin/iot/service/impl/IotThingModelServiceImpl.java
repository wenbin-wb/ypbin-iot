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

import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotEvent;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.AccessMode;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.enums.ServiceOption;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotEventMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.IotCommandReq;
import cn.ypbin.admin.iot.model.req.IotEventReq;
import cn.ypbin.admin.iot.model.req.IotPropertyReq;
import cn.ypbin.admin.iot.model.req.IotServiceReq;
import cn.ypbin.admin.iot.model.resp.IotCommandResp;
import cn.ypbin.admin.iot.model.resp.IotEventResp;
import cn.ypbin.admin.iot.model.resp.IotPropertyResp;
import cn.ypbin.admin.iot.model.resp.IotServiceResp;
import cn.ypbin.admin.iot.model.resp.TslImportResult;
import cn.ypbin.admin.iot.model.tsl.TslCommand;
import cn.ypbin.admin.iot.model.tsl.TslDocument;
import cn.ypbin.admin.iot.model.tsl.TslEvent;
import cn.ypbin.admin.iot.model.tsl.TslPara;
import cn.ypbin.admin.iot.model.tsl.TslProperty;
import cn.ypbin.admin.iot.model.tsl.TslService;
import cn.ypbin.admin.iot.service.IotThingModelService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IoT 物模型服务实现（§3.3–§3.7）。
 *
 * <p>写操作要求产品处于草稿状态（已发布同版本不可变，§3.8）；
 * TSL 导入为全量替换当前草稿结构，校验失败逐项报错且整体不落库（§3.7）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotThingModelServiceImpl extends BaseServiceImpl<IotServiceMapper, IotService>
    implements IotThingModelService {

    private static final Logger log = LoggerFactory.getLogger(IotThingModelServiceImpl.class);

    /** 属性/事件标识 camelCase：小写字母开头，仅字母数字。 */
    private static final Pattern PATTERN_CAMEL = Pattern.compile("[a-z][A-Za-z0-9]*");

    /** 服务标识 PascalCase：大写字母开头，仅字母数字。 */
    private static final Pattern PATTERN_PASCAL = Pattern.compile("[A-Z][A-Za-z0-9]*");

    /** 命令标识 UPPER_SNAKE：大写字母开头，仅大写字母/数字/下划线。 */
    private static final Pattern PATTERN_UPPER_SNAKE = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** 数据类型 9 类。 */
    private static final List<String> DATA_TYPES = List.of(
        "int", "long", "decimal", "string", "bool", "enum", "date_time", "json_object", "array");

    private final ObjectMapper objectMapper;
    private final IotProductMapper iotProductMapper;
    private final IotPropertyMapper iotPropertyMapper;
    private final IotCommandMapper iotCommandMapper;
    private final IotEventMapper iotEventMapper;

    public IotThingModelServiceImpl(ObjectMapper objectMapper,
                                    IotProductMapper iotProductMapper,
                                    IotPropertyMapper iotPropertyMapper,
                                    IotCommandMapper iotCommandMapper,
                                    IotEventMapper iotEventMapper) {
        this.objectMapper = objectMapper;
        this.iotProductMapper = iotProductMapper;
        this.iotPropertyMapper = iotPropertyMapper;
        this.iotCommandMapper = iotCommandMapper;
        this.iotEventMapper = iotEventMapper;
    }

    // ---------- 服务 ----------

    @Override
    public List<IotServiceResp> listServices(Long productId) {
        requireProductDraft(productId);
        LambdaQueryWrapper<IotService> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotService::getProductId, productId)
            .orderByAsc(IotService::getSort)
            .orderByAsc(IotService::getId);
        return baseMapper.selectList(wrapper).stream().map(this::toServiceResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createService(IotServiceReq req) {
        requireProductDraft(req.getProductId());
        IotService service = new IotService();
        service.setProductId(req.getProductId());
        service.setServiceId(req.getServiceId());
        service.setServiceName(req.getServiceName());
        service.setOption(req.getOption());
        service.setSort(req.getSort() == null ? 0 : req.getSort());
        service.setDescription(req.getDescription());
        save(service);
        return service.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateService(Long id, IotServiceReq req) {
        IotService service = requireService(id);
        requireProductDraft(service.getProductId());
        service.setServiceId(req.getServiceId());
        service.setServiceName(req.getServiceName());
        service.setOption(req.getOption());
        service.setSort(req.getSort() == null ? 0 : req.getSort());
        service.setDescription(req.getDescription());
        updateById(service);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeService(Long id) {
        IotService service = requireService(id);
        requireProductDraft(service.getProductId());
        // 物理删除并与全量替换同口径；同时级联清掉子表，避免留下指向已删服务的活子行
        iotPropertyMapper.physicalDeleteByServiceIds(List.of(id));
        iotCommandMapper.physicalDeleteByServiceIds(List.of(id));
        iotEventMapper.physicalDeleteByServiceIds(List.of(id));
        baseMapper.physicalDeleteByIds(List.of(id));
    }

    // ---------- 属性 ----------

    @Override
    public List<IotPropertyResp> listProperties(Long serviceId) {
        requireDraftByServiceId(serviceId);
        LambdaQueryWrapper<IotProperty> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotProperty::getServiceId, serviceId)
            .orderByAsc(IotProperty::getSort)
            .orderByAsc(IotProperty::getId);
        return iotPropertyMapper.selectList(wrapper).stream().map(this::toPropertyResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProperty(IotPropertyReq req) {
        requireDraftByServiceId(req.getServiceId());
        IotProperty property = new IotProperty();
        applyProperty(property, req);
        iotPropertyMapper.insert(property);
        return property.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProperty(Long id, IotPropertyReq req) {
        IotProperty property = requireProperty(id);
        requireDraftByServiceId(property.getServiceId());
        applyProperty(property, req);
        iotPropertyMapper.updateById(property);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeProperty(Long id) {
        IotProperty property = requireProperty(id);
        requireDraftByServiceId(property.getServiceId());
        iotPropertyMapper.physicalDeleteByIds(List.of(id));
    }

    // ---------- 命令 ----------

    @Override
    public List<IotCommandResp> listCommands(Long serviceId) {
        requireDraftByServiceId(serviceId);
        LambdaQueryWrapper<IotCommand> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotCommand::getServiceId, serviceId)
            .orderByAsc(IotCommand::getSort)
            .orderByAsc(IotCommand::getId);
        return iotCommandMapper.selectList(wrapper).stream().map(this::toCommandResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCommand(IotCommandReq req) {
        requireDraftByServiceId(req.getServiceId());
        IotCommand command = new IotCommand();
        applyCommand(command, req);
        iotCommandMapper.insert(command);
        return command.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCommand(Long id, IotCommandReq req) {
        IotCommand command = requireCommand(id);
        requireDraftByServiceId(command.getServiceId());
        applyCommand(command, req);
        iotCommandMapper.updateById(command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCommand(Long id) {
        IotCommand command = requireCommand(id);
        requireDraftByServiceId(command.getServiceId());
        iotCommandMapper.physicalDeleteByIds(List.of(id));
    }

    // ---------- 事件 ----------

    @Override
    public List<IotEventResp> listEvents(Long serviceId) {
        requireDraftByServiceId(serviceId);
        LambdaQueryWrapper<IotEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotEvent::getServiceId, serviceId)
            .orderByAsc(IotEvent::getSort)
            .orderByAsc(IotEvent::getId);
        return iotEventMapper.selectList(wrapper).stream().map(this::toEventResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createEvent(IotEventReq req) {
        requireDraftByServiceId(req.getServiceId());
        IotEvent event = new IotEvent();
        applyEvent(event, req);
        iotEventMapper.insert(event);
        return event.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEvent(Long id, IotEventReq req) {
        IotEvent event = requireEvent(id);
        requireDraftByServiceId(event.getServiceId());
        applyEvent(event, req);
        iotEventMapper.updateById(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeEvent(Long id) {
        IotEvent event = requireEvent(id);
        requireDraftByServiceId(event.getServiceId());
        iotEventMapper.physicalDeleteByIds(List.of(id));
    }

    // ---------- TSL 导出 ----------

    @Override
    public TslDocument exportTsl(Long productId) {
        IotProduct product = requireProduct(productId);
        TslDocument doc = new TslDocument();

        List<IotService> services = listServiceEntities(productId);
        List<TslService> tslServices = toTslServices(services);
        doc.setServices(tslServices);

        TslDocument.TslDevice device = new TslDocument.TslDevice();
        device.setManufacturerId(product.getManufacturerId());
        device.setManufacturerName(product.getManufacturerName());
        device.setProtocolType(product.getProtocol());
        device.setDeviceType(product.getDeviceType());
        device.setServiceTypeCapabilities(services.stream().map(s -> {
            TslDocument.TslServiceRef ref = new TslDocument.TslServiceRef();
            ref.setServiceId(s.getServiceId());
            ref.setServiceType(s.getServiceId());
            ref.setOption(s.getOption());
            return ref;
        }).toList());
        doc.setDevices(List.of(device));
        return doc;
    }

    // ---------- TSL 导入 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TslImportResult importTsl(Long productId, TslDocument doc) {
        requireProductDraft(productId);
        List<String> errors = new ArrayList<>();
        validateTsl(doc, errors);
        if (!errors.isEmpty()) {
            TslImportResult result = new TslImportResult();
            result.setSuccessCount(0);
            result.setErrors(errors);
            return result;
        }
        replaceTsl(productId, doc);
        TslImportResult result = new TslImportResult();
        result.setSuccessCount(countTslElements(doc));
        result.setErrors(List.of());
        return result;
    }

    // ---------- 校验与落库 ----------

    /**
     * TSL 全字段校验（§3.7：命名规范/类型枚举/引用完整性），逐项收集错误。
     *
     * @param doc    TSL 文档
     * @param errors 错误收集器
     */
    void validateTsl(TslDocument doc, List<String> errors) {
        List<TslDocument.TslDevice> devices = doc.getDevices() == null ? List.of() : doc.getDevices();
        List<TslService> services = doc.getServices() == null ? List.<TslService>of() : doc.getServices();
        if (devices.size() != 1) {
            errors.add("devices 数组必须恰好包含 1 个产品定义，实际 " + devices.size());
            return;
        }
        TslDocument.TslDevice device = devices.getFirst();
        // 空值安全收集：serviceType 为空的项留给下方命名校验逐项报错；
        // 不能直接 toMap —— HashMap 不收 null 键，会抛 NPE 而绕过「逐项报错」
        Map<String, TslService> byType = new LinkedHashMap<>();
        for (TslService service : services) {
            if (StringUtils.hasText(service.getServiceType())) {
                byType.put(service.getServiceType(), service);
            }
        }
        long masterCount = services.stream()
            .filter(s -> ServiceOption.MASTER.getCode().equals(s.getOption())).count();
        if (masterCount != 1) {
            errors.add("master 服务必须有且仅有一个，实际 " + masterCount);
        }
        for (TslDocument.TslServiceRef ref : device.getServiceTypeCapabilities() == null
            ? List.<TslDocument.TslServiceRef>of() : device.getServiceTypeCapabilities()) {
            if (!byType.containsKey(ref.getServiceType())) {
                errors.add("服务引用未在 services 中定义：" + ref.getServiceType());
            }
        }
        for (TslService service : services) {
            if (!StringUtils.hasText(service.getServiceType())
                || !PATTERN_PASCAL.matcher(service.getServiceType()).matches()) {
                errors.add("服务标识格式非法（应 PascalCase）：" + service.getServiceType());
            }
            if (!StringUtils.hasText(service.getOption())
                || !ServiceOption.isValid(service.getOption())) {
                errors.add("服务选项非法（master|mandatory|optional）：" + service.getServiceType());
            }
            for (TslProperty property : service.getProperties() == null ? List.<TslProperty>of() : service.getProperties()) {
                if (!StringUtils.hasText(property.getPropertyName())
                    || !PATTERN_CAMEL.matcher(property.getPropertyName()).matches()) {
                    errors.add("属性标识格式非法（应 camelCase）：" + property.getPropertyName());
                }
                if (!DATA_TYPES.contains(property.getDataType())) {
                    errors.add("属性数据类型非法：" + property.getPropertyName() + " -> " + property.getDataType());
                }
                if (!AccessMode.isValid(property.getMethod())) {
                    errors.add("属性读写权限非法（R|W|RW）：" + property.getPropertyName());
                }
                validateMaxLength(property.getMaxLength(), "属性 " + property.getPropertyName(), errors);
                validateDecimal(property.getMin(), "属性 " + property.getPropertyName() + " min", errors);
                validateDecimal(property.getMax(), "属性 " + property.getPropertyName() + " max", errors);
                validateDecimal(property.getStep(), "属性 " + property.getPropertyName() + " step", errors);
            }
            for (TslCommand command : service.getCommands() == null ? List.<TslCommand>of() : service.getCommands()) {
                if (!StringUtils.hasText(command.getCommandName())
                    || !PATTERN_UPPER_SNAKE.matcher(command.getCommandName()).matches()) {
                    errors.add("命令标识格式非法（应 UPPER_SNAKE）：" + command.getCommandName());
                }
                validateParas(command.getParas(), "命令 " + command.getCommandName() + " 入参", errors);
                validateParas(command.getResponses(), "命令 " + command.getCommandName() + " 出参", errors);
            }
            for (TslEvent event : service.getEvents() == null ? List.<TslEvent>of() : service.getEvents()) {
                if (!StringUtils.hasText(event.getEventName())
                    || !PATTERN_CAMEL.matcher(event.getEventName()).matches()) {
                    errors.add("事件标识格式非法（应 camelCase）：" + event.getEventName());
                }
                if (!DATA_TYPES.contains(event.getDataType())) {
                    errors.add("事件数据类型非法：" + event.getEventName() + " -> " + event.getDataType());
                }
                validateMaxLength(event.getMaxLength(), "事件 " + event.getEventName(), errors);
            }
        }
    }

    /**
     * 校验命令参数（入参/出参共用）。
     *
     * @param paras  参数列表
     * @param where  出错定位描述
     * @param errors 错误收集器
     */
    private void validateParas(List<TslPara> paras, String where, List<String> errors) {
        for (TslPara para : paras == null ? List.<TslPara>of() : paras) {
            if (!StringUtils.hasText(para.getParaName())
                || !PATTERN_CAMEL.matcher(para.getParaName()).matches()) {
                errors.add(where + " 参数名格式非法（应 camelCase）：" + para.getParaName());
            }
            if (!DATA_TYPES.contains(para.getDataType())) {
                errors.add(where + " 参数数据类型非法：" + para.getParaName() + " -> " + para.getDataType());
            }
            validateMaxLength(para.getMaxLength(), where + " 参数 " + para.getParaName(), errors);
        }
    }

    /**
     * 校验 TSL 的 maxLength：非空时必须是正整数。
     *
     * <p>TSL 里 maxLength 是字符串而落库列是 INT，若不校验，非法值会在写入阶段抛
     * {@code NumberFormatException} 变成 500，绕过「逐项报错、失败不落库」的约定。</p>
     *
     * @param maxLength TSL maxLength 字符串（可空）
     * @param where     出错定位描述
     * @param errors    错误收集器
     */
    private void validateMaxLength(String maxLength, String where, List<String> errors) {
        if (!StringUtils.hasText(maxLength)) {
            return;
        }
        try {
            if (Integer.parseInt(maxLength.trim()) <= 0) {
                errors.add(where + " maxLength 必须为正整数：" + maxLength);
            }
        } catch (NumberFormatException ex) {
            errors.add(where + " maxLength 非整数：" + maxLength);
        }
    }

    /**
     * 全量替换产品下的草稿 TSL 结构（先逻辑删除旧结构，再批量插入新结构）。
     *
     * <p>写入全部走批量：删除按 service 主键一次 IN，插入按「服务 / 属性 / 命令 / 事件」各一次
     * 批量写，语句数恒定（不随 TSL 规模退化为 N+1）。服务主键是雪花 {@code ASSIGN_ID}，
     * 批量插入时即回填到实体，故子表能直接引用父服务 ID。</p>
     *
     * @param productId 产品主键
     * @param doc       TSL 文档（已通过校验）
     */
    private void replaceTsl(Long productId, TslDocument doc) {
        deleteTslStructure(productId);
        insertTslStructure(productId, doc);
    }

    /**
     * 批量逻辑删除产品下全部旧物模型结构（4 条语句）。
     *
     * @param productId 产品主键
     */
    private void deleteTslStructure(Long productId) {
        List<Long> serviceIds = listServiceEntities(productId).stream()
            .map(IotService::getId)
            .toList();
        if (serviceIds.isEmpty()) {
            return;
        }
        // 必须物理删除：iot_service 的业务唯一键不含 is_deleted，逻辑删除行会继续占用
        // (tenant_id, product_id, service_id)，使同一产品第二次导入 TSL 主键冲突
        iotPropertyMapper.physicalDeleteByServiceIds(serviceIds);
        iotCommandMapper.physicalDeleteByServiceIds(serviceIds);
        iotEventMapper.physicalDeleteByServiceIds(serviceIds);
        baseMapper.physicalDeleteByIds(serviceIds);
    }

    /**
     * 批量写入产品的新物模型结构（4 条批量写语句）。
     *
     * @param productId 产品主键
     * @param doc       TSL 文档（已通过校验）
     */
    private void insertTslStructure(Long productId, TslDocument doc) {
        List<TslService> tslServices = doc.getServices() == null
            ? List.<TslService>of() : doc.getServices();
        List<IotService> services = new ArrayList<>(tslServices.size());
        int serviceSort = 0;
        for (TslService tsl : tslServices) {
            IotService service = new IotService();
            service.setProductId(productId);
            service.setServiceId(tsl.getServiceType());
            service.setServiceName(StringUtils.hasText(tsl.getDescription())
                ? tsl.getDescription() : tsl.getServiceType());
            service.setOption(tsl.getOption());
            service.setSort(serviceSort++);
            services.add(service);
        }
        if (services.isEmpty()) {
            return;
        }
        // 先批量落服务：此后雪花主键已回填到实体，子表可引用
        baseMapper.insert(services);

        List<IotProperty> properties = new ArrayList<>();
        List<IotCommand> commands = new ArrayList<>();
        List<IotEvent> events = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            Long serviceId = services.get(i).getId();
            TslService tsl = tslServices.get(i);
            collectProperties(serviceId, tsl.getProperties(), properties);
            collectCommands(serviceId, tsl.getCommands(), commands);
            collectEvents(serviceId, tsl.getEvents(), events);
        }
        if (!properties.isEmpty()) {
            iotPropertyMapper.insert(properties);
        }
        if (!commands.isEmpty()) {
            iotCommandMapper.insert(commands);
        }
        if (!events.isEmpty()) {
            iotEventMapper.insert(events);
        }
    }

    /**
     * 校验 TSL 的数值型字段（min/max/step）：非空时必须是合法十进制数。
     *
     * <p>与 maxLength 同理：TSL 里是字符串而落库列是 DECIMAL，不校验会在写入阶段抛异常。</p>
     *
     * @param value 待校验字符串（可空）
     * @param where 出错定位描述
     * @param errors 错误收集器
     */
    private void validateDecimal(String value, String where, List<String> errors) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            errors.add(where + " 非数值：" + value);
        }
    }

    /**
     * 组装 TSL 服务列表（子结构按 service 主键<b>一次 IN 批量取回</b>再分组，避免逐服务查询）。
     *
     * @param services 服务实体列表（按产品查出）
     * @return TSL 服务列表
     */
    private List<TslService> toTslServices(List<IotService> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        List<Long> serviceIds = services.stream().map(IotService::getId).distinct().toList();
        Map<Long, List<IotProperty>> propertiesByService = iotPropertyMapper.selectList(
                new LambdaQueryWrapper<IotProperty>()
                    .in(IotProperty::getServiceId, serviceIds)
                    .orderByAsc(IotProperty::getSort)).stream()
            .collect(Collectors.groupingBy(IotProperty::getServiceId));
        Map<Long, List<IotCommand>> commandsByService = iotCommandMapper.selectList(
                new LambdaQueryWrapper<IotCommand>()
                    .in(IotCommand::getServiceId, serviceIds)
                    .orderByAsc(IotCommand::getSort)).stream()
            .collect(Collectors.groupingBy(IotCommand::getServiceId));
        Map<Long, List<IotEvent>> eventsByService = iotEventMapper.selectList(
                new LambdaQueryWrapper<IotEvent>()
                    .in(IotEvent::getServiceId, serviceIds)
                    .orderByAsc(IotEvent::getSort)).stream()
            .collect(Collectors.groupingBy(IotEvent::getServiceId));
        List<TslService> result = new ArrayList<>(services.size());
        for (IotService service : services) {
            TslService tsl = new TslService();
            tsl.setServiceType(service.getServiceId());
            tsl.setDescription(service.getServiceName());
            tsl.setOption(service.getOption());
            tsl.setSort(service.getSort());
            tsl.setProperties(propertiesByService.getOrDefault(service.getId(), List.of()).stream()
                .map(this::toTslProperty).toList());
            tsl.setCommands(commandsByService.getOrDefault(service.getId(), List.of()).stream()
                .map(this::toTslCommand).toList());
            tsl.setEvents(eventsByService.getOrDefault(service.getId(), List.of()).stream()
                .map(this::toTslEvent).toList());
            result.add(tsl);
        }
        return result;
    }

    /**
     * 组装属性实体（不落库，交由调用方批量写入）。
     *
     * @param serviceId 所属服务主键
     * @param tslList   TSL 属性列表（可空）
     * @param out       收集容器
     */
    private void collectProperties(Long serviceId, List<TslProperty> tslList, List<IotProperty> out) {
        int sort = 0;
        for (TslProperty tsl : tslList == null ? List.<TslProperty>of() : tslList) {
            IotProperty property = new IotProperty();
            property.setServiceId(serviceId);
            property.setIdentifier(tsl.getPropertyName());
            property.setPropertyName(tsl.getPropertyName());
            property.setDataType(tsl.getDataType());
            property.setAccessMode(tsl.getMethod());
            property.setRequired(tsl.getRequired() == null ? Boolean.FALSE : tsl.getRequired());
            property.setMinValue(toDecimal(tsl.getMin()));
            property.setMaxValue(toDecimal(tsl.getMax()));
            property.setStep(toDecimal(tsl.getStep()));
            property.setMaxLength(parseMaxLength(tsl.getMaxLength()));
            property.setUnit(tsl.getUnit());
            property.setEnumList(tsl.getEnumList() == null ? null : toJson(tsl.getEnumList()));
            property.setDefaultValue(tsl.getDefaultValue());
            property.setExpand(tsl.getExpand() == null ? null : toJson(tsl.getExpand()));
            property.setSort(sort++);
            out.add(property);
        }
    }

    /**
     * 组装命令实体（不落库，交由调用方批量写入）。
     *
     * @param serviceId 所属服务主键
     * @param tslList   TSL 命令列表（可空）
     * @param out       收集容器
     */
    private void collectCommands(Long serviceId, List<TslCommand> tslList, List<IotCommand> out) {
        int sort = 0;
        for (TslCommand tsl : tslList == null ? List.<TslCommand>of() : tslList) {
            IotCommand command = new IotCommand();
            command.setServiceId(serviceId);
            // TSL 命令只有一个名称字段（§3.7：commandName，UPPER_SNAKE），无独立展示名，
            // 故 identifier 与 commandName 同值——这不是字段映射，而是契约本身如此
            command.setIdentifier(tsl.getCommandName());
            command.setCommandName(tsl.getCommandName());
            command.setInputParams(tsl.getParas() == null ? null : toJson(tsl.getParas()));
            command.setOutputParams(tsl.getResponses() == null ? null : toJson(tsl.getResponses()));
            command.setTimeoutMs(tsl.getTimeoutMs());
            command.setSort(sort++);
            out.add(command);
        }
    }

    /**
     * 组装事件实体（不落库，交由调用方批量写入）。
     *
     * @param serviceId 所属服务主键
     * @param tslList   TSL 事件列表（可空）
     * @param out       收集容器
     */
    private void collectEvents(Long serviceId, List<TslEvent> tslList, List<IotEvent> out) {
        int sort = 0;
        for (TslEvent tsl : tslList == null ? List.<TslEvent>of() : tslList) {
            IotEvent event = new IotEvent();
            event.setServiceId(serviceId);
            // 同命令：§3.7 的 TSL 事件只有 eventName 一个名称字段，identifier 与 eventName 同值
            event.setIdentifier(tsl.getEventName());
            event.setEventName(tsl.getEventName());
            event.setDataType(tsl.getDataType());
            event.setMaxLength(parseMaxLength(tsl.getMaxLength()));
            event.setUnit(tsl.getUnit());
            event.setEnumList(tsl.getEnumList() == null ? null : toJson(tsl.getEnumList()));
            event.setSort(sort++);
            out.add(event);
        }
    }

    /**
     * 解析 TSL 的 maxLength（字符串）为整数。
     *
     * <p>调用前 {@code validateTsl} 已保证可解析为正整数，此处仅是转换。</p>
     *
     * @param maxLength TSL maxLength 字符串（可空）
     * @return 整数值；入参为空时 {@code null}
     */
    private Integer parseMaxLength(String maxLength) {
        return StringUtils.hasText(maxLength) ? Integer.valueOf(maxLength.trim()) : null;
    }

    /**
     * 统计 TSL 元素数（服务+属性+命令+事件）。
     *
     * @param doc TSL 文档
     * @return 元素总数
     */
    private int countTslElements(TslDocument doc) {
        int count = doc.getServices() == null ? 0 : doc.getServices().size();
        for (TslService service : doc.getServices() == null ? List.<TslService>of() : doc.getServices()) {
            count += service.getProperties() == null ? 0 : service.getProperties().size();
            count += service.getCommands() == null ? 0 : service.getCommands().size();
            count += service.getEvents() == null ? 0 : service.getEvents().size();
        }
        return count;
    }

    // ---------- 实体 ↔ TSL ----------

    private TslProperty toTslProperty(IotProperty entity) {
        TslProperty tsl = new TslProperty();
        tsl.setPropertyName(entity.getIdentifier());
        tsl.setDataType(entity.getDataType());
        tsl.setMethod(entity.getAccessMode());
        tsl.setMin(entity.getMinValue() == null ? null : entity.getMinValue().toPlainString());
        tsl.setMax(entity.getMaxValue() == null ? null : entity.getMaxValue().toPlainString());
        tsl.setStep(entity.getStep() == null ? null : entity.getStep().toPlainString());
        tsl.setMaxLength(entity.getMaxLength() == null ? null : String.valueOf(entity.getMaxLength()));
        tsl.setUnit(entity.getUnit());
        tsl.setRequired(entity.getRequired());
        tsl.setEnumList(entity.getEnumList() == null ? null : fromJson(entity.getEnumList(),
            new TypeReference<List<String>>() { }));
        tsl.setDefaultValue(entity.getDefaultValue());
        tsl.setSort(entity.getSort());
        return tsl;
    }

    private TslCommand toTslCommand(IotCommand entity) {
        TslCommand tsl = new TslCommand();
        tsl.setCommandName(entity.getIdentifier());
        tsl.setDescription(entity.getCommandName());
        tsl.setParas(entity.getInputParams() == null ? null : fromJson(entity.getInputParams(),
            new TypeReference<List<TslPara>>() { }));
        tsl.setResponses(entity.getOutputParams() == null ? null : fromJson(entity.getOutputParams(),
            new TypeReference<List<TslPara>>() { }));
        tsl.setTimeoutMs(entity.getTimeoutMs());
        tsl.setSort(entity.getSort());
        return tsl;
    }

    private TslEvent toTslEvent(IotEvent entity) {
        TslEvent tsl = new TslEvent();
        tsl.setEventName(entity.getIdentifier());
        tsl.setDescription(entity.getEventName());
        tsl.setDataType(entity.getDataType());
        tsl.setMaxLength(entity.getMaxLength() == null ? null : String.valueOf(entity.getMaxLength()));
        tsl.setUnit(entity.getUnit());
        tsl.setEnumList(entity.getEnumList() == null ? null : fromJson(entity.getEnumList(),
            new TypeReference<List<String>>() { }));
        tsl.setSort(entity.getSort());
        return tsl;
    }

    // ---------- Req 应用 ----------

    private void applyProperty(IotProperty property, IotPropertyReq req) {
        property.setServiceId(req.getServiceId());
        property.setIdentifier(req.getIdentifier());
        property.setPropertyName(req.getPropertyName());
        property.setDataType(req.getDataType());
        property.setAccessMode(req.getAccessMode());
        property.setRequired(req.getRequired() == null ? Boolean.FALSE : req.getRequired());
        property.setMinValue(req.getMinValue());
        property.setMaxValue(req.getMaxValue());
        property.setStep(req.getStep());
        property.setMaxLength(req.getMaxLength());
        property.setUnit(req.getUnit());
        property.setEnumList(req.getEnumList());
        property.setDefaultValue(req.getDefaultValue());
        property.setExpand(req.getExpand());
        property.setSort(req.getSort() == null ? 0 : req.getSort());
    }

    private void applyCommand(IotCommand command, IotCommandReq req) {
        command.setServiceId(req.getServiceId());
        command.setIdentifier(req.getIdentifier());
        command.setCommandName(req.getCommandName());
        command.setInputParams(req.getInputParams());
        command.setOutputParams(req.getOutputParams());
        command.setTimeoutMs(req.getTimeoutMs());
        command.setSort(req.getSort() == null ? 0 : req.getSort());
    }

    private void applyEvent(IotEvent event, IotEventReq req) {
        event.setServiceId(req.getServiceId());
        event.setIdentifier(req.getIdentifier());
        event.setEventName(req.getEventName());
        event.setDataType(req.getDataType());
        event.setMaxLength(req.getMaxLength());
        event.setUnit(req.getUnit());
        event.setEnumList(req.getEnumList());
        event.setSort(req.getSort() == null ? 0 : req.getSort());
    }

    // ---------- 响应转换 ----------

    private IotServiceResp toServiceResp(IotService entity) {
        IotServiceResp resp = new IotServiceResp();
        resp.setId(entity.getId());
        resp.setProductId(entity.getProductId());
        resp.setServiceId(entity.getServiceId());
        resp.setServiceName(entity.getServiceName());
        resp.setOption(entity.getOption());
        resp.setSort(entity.getSort());
        resp.setDescription(entity.getDescription());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    private IotPropertyResp toPropertyResp(IotProperty entity) {
        IotPropertyResp resp = new IotPropertyResp();
        resp.setId(entity.getId());
        resp.setServiceId(entity.getServiceId());
        resp.setIdentifier(entity.getIdentifier());
        resp.setPropertyName(entity.getPropertyName());
        resp.setDataType(entity.getDataType());
        resp.setAccessMode(entity.getAccessMode());
        resp.setRequired(entity.getRequired());
        resp.setMinValue(entity.getMinValue());
        resp.setMaxValue(entity.getMaxValue());
        resp.setStep(entity.getStep());
        resp.setMaxLength(entity.getMaxLength());
        resp.setUnit(entity.getUnit());
        resp.setEnumList(entity.getEnumList());
        resp.setDefaultValue(entity.getDefaultValue());
        resp.setExpand(entity.getExpand());
        resp.setSort(entity.getSort());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    private IotCommandResp toCommandResp(IotCommand entity) {
        IotCommandResp resp = new IotCommandResp();
        resp.setId(entity.getId());
        resp.setServiceId(entity.getServiceId());
        resp.setIdentifier(entity.getIdentifier());
        resp.setCommandName(entity.getCommandName());
        resp.setInputParams(entity.getInputParams());
        resp.setOutputParams(entity.getOutputParams());
        resp.setTimeoutMs(entity.getTimeoutMs());
        resp.setSort(entity.getSort());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    private IotEventResp toEventResp(IotEvent entity) {
        IotEventResp resp = new IotEventResp();
        resp.setId(entity.getId());
        resp.setServiceId(entity.getServiceId());
        resp.setIdentifier(entity.getIdentifier());
        resp.setEventName(entity.getEventName());
        resp.setDataType(entity.getDataType());
        resp.setMaxLength(entity.getMaxLength());
        resp.setUnit(entity.getUnit());
        resp.setEnumList(entity.getEnumList());
        resp.setSort(entity.getSort());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    // ---------- 取实体 ----------

    private IotService requireService(Long id) {
        IotService service = getById(id);
        if (service == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "服务不存在：" + id);
        }
        return service;
    }

    private IotProperty requireProperty(Long id) {
        IotProperty property = iotPropertyMapper.selectById(id);
        if (property == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "属性不存在：" + id);
        }
        return property;
    }

    private IotCommand requireCommand(Long id) {
        IotCommand command = iotCommandMapper.selectById(id);
        if (command == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "命令不存在：" + id);
        }
        return command;
    }

    private IotEvent requireEvent(Long id) {
        IotEvent event = iotEventMapper.selectById(id);
        if (event == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "事件不存在：" + id);
        }
        return event;
    }

    private IotProduct requireProduct(Long productId) {
        IotProduct product = iotProductMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "产品不存在：" + productId);
        }
        return product;
    }

    /**
     * 服务列表实体（包内可见以便单测）。
     *
     * @param productId 产品主键
     * @return 服务实体列表
     */
    List<IotService> listServiceEntities(Long productId) {
        LambdaQueryWrapper<IotService> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotService::getProductId, productId)
            .orderByAsc(IotService::getSort)
            .orderByAsc(IotService::getId);
        return baseMapper.selectList(wrapper);
    }

    private void requireProductDraft(Long productId) {
        IotProduct product = requireProduct(productId);
        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "仅草稿状态可编辑物模型，请先新建草稿（productId=" + productId + "）");
        }
    }

    private void requireDraftByServiceId(Long serviceId) {
        IotService service = requireService(serviceId);
        requireProductDraft(service.getProductId());
    }

    // ---------- JSON / 数值工具 ----------

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.error("[iot] TSL 扩展字段 JSON 序列化失败", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR.getCode(), "JSON 序列化失败");
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            log.error("[iot] TSL 扩展字段 JSON 反序列化失败", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR.getCode(), "JSON 反序列化失败");
        }
    }

    private BigDecimal toDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "数值格式非法：" + value);
        }
    }
}
