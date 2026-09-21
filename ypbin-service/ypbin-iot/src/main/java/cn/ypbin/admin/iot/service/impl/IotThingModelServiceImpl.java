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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
        removeById(id);
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
        iotPropertyMapper.deleteById(id);
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
        iotCommandMapper.deleteById(id);
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
        iotEventMapper.deleteById(id);
    }

    // ---------- TSL 导出 ----------

    @Override
    public TslDocument exportTsl(Long productId) {
        IotProduct product = requireProduct(productId);
        TslDocument doc = new TslDocument();

        List<IotService> services = listServiceEntities(productId);
        List<TslService> tslServices = services.stream().map(s -> {
            TslService tsl = new TslService();
            tsl.setServiceType(s.getServiceId());
            tsl.setDescription(s.getServiceName());
            tsl.setOption(s.getOption());
            tsl.setSort(s.getSort());
            tsl.setProperties(iotPropertyMapper.selectList(
                    new LambdaQueryWrapper<IotProperty>()
                        .eq(IotProperty::getServiceId, s.getId())
                        .orderByAsc(IotProperty::getSort)).stream()
                .map(this::toTslProperty).toList());
            tsl.setCommands(iotCommandMapper.selectList(
                    new LambdaQueryWrapper<IotCommand>()
                        .eq(IotCommand::getServiceId, s.getId())
                        .orderByAsc(IotCommand::getSort)).stream()
                .map(this::toTslCommand).toList());
            tsl.setEvents(iotEventMapper.selectList(
                    new LambdaQueryWrapper<IotEvent>()
                        .eq(IotEvent::getServiceId, s.getId())
                        .orderByAsc(IotEvent::getSort)).stream()
                .map(this::toTslEvent).toList());
            return tsl;
        }).toList();
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
        Map<String, TslService> byType = services.stream()
            .collect(Collectors.toMap(TslService::getServiceType, s -> s, (a, b) -> b));
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
            if (!PATTERN_PASCAL.matcher(service.getServiceType()).matches()) {
                errors.add("服务标识格式非法（应 PascalCase）：" + service.getServiceType());
            }
            if (!StringUtils.hasText(service.getOption())
                || !ServiceOption.isValid(service.getOption())) {
                errors.add("服务选项非法（master|mandatory|optional）：" + service.getServiceType());
            }
            for (TslProperty property : service.getProperties() == null ? List.<TslProperty>of() : service.getProperties()) {
                if (!PATTERN_CAMEL.matcher(property.getPropertyName()).matches()) {
                    errors.add("属性标识格式非法（应 camelCase）：" + property.getPropertyName());
                }
                if (!DATA_TYPES.contains(property.getDataType())) {
                    errors.add("属性数据类型非法：" + property.getPropertyName() + " -> " + property.getDataType());
                }
                if (!AccessMode.isValid(property.getMethod())) {
                    errors.add("属性读写权限非法（R|W|RW）：" + property.getPropertyName());
                }
            }
            for (TslCommand command : service.getCommands() == null ? List.<TslCommand>of() : service.getCommands()) {
                if (!PATTERN_UPPER_SNAKE.matcher(command.getCommandName()).matches()) {
                    errors.add("命令标识格式非法（应 UPPER_SNAKE）：" + command.getCommandName());
                }
                validateParas(command.getParas(), "命令 " + command.getCommandName() + " 入参", errors);
                validateParas(command.getResponses(), "命令 " + command.getCommandName() + " 出参", errors);
            }
            for (TslEvent event : service.getEvents() == null ? List.<TslEvent>of() : service.getEvents()) {
                if (!PATTERN_CAMEL.matcher(event.getEventName()).matches()) {
                    errors.add("事件标识格式非法（应 camelCase）：" + event.getEventName());
                }
                if (!DATA_TYPES.contains(event.getDataType())) {
                    errors.add("事件数据类型非法：" + event.getEventName() + " -> " + event.getDataType());
                }
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
            if (!PATTERN_CAMEL.matcher(para.getParaName()).matches()) {
                errors.add(where + " 参数名格式非法（应 camelCase）：" + para.getParaName());
            }
            if (!DATA_TYPES.contains(para.getDataType())) {
                errors.add(where + " 参数数据类型非法：" + para.getParaName() + " -> " + para.getDataType());
            }
        }
    }

    /**
     * 全量替换产品下的草稿 TSL 结构（先逻辑删除旧结构，再插入新结构）。
     *
     * @param productId 产品主键
     * @param doc       TSL 文档（已通过校验）
     */
    private void replaceTsl(Long productId, TslDocument doc) {
        List<IotService> oldServices = listServiceEntities(productId);
        for (IotService old : oldServices) {
            iotPropertyMapper.delete(new LambdaQueryWrapper<IotProperty>()
                .eq(IotProperty::getServiceId, old.getId()));
            iotCommandMapper.delete(new LambdaQueryWrapper<IotCommand>()
                .eq(IotCommand::getServiceId, old.getId()));
            iotEventMapper.delete(new LambdaQueryWrapper<IotEvent>()
                .eq(IotEvent::getServiceId, old.getId()));
            baseMapper.deleteById(old.getId());
        }
        int sort = 0;
        for (TslService tsl : doc.getServices()) {
            IotService service = new IotService();
            service.setProductId(productId);
            service.setServiceId(tsl.getServiceType());
            service.setServiceName(StringUtils.hasText(tsl.getDescription())
                ? tsl.getDescription() : tsl.getServiceType());
            service.setOption(tsl.getOption());
            service.setSort(sort++);
            baseMapper.insert(service);
            insertProperties(service.getId(), tsl.getProperties());
            insertCommands(service.getId(), tsl.getCommands());
            insertEvents(service.getId(), tsl.getEvents());
        }
    }

    private void insertProperties(Long serviceId, List<TslProperty> properties) {
        int sort = 0;
        for (TslProperty tsl : properties == null ? List.<TslProperty>of() : properties) {
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
            property.setMaxLength(tsl.getMaxLength() == null ? null : Integer.parseInt(tsl.getMaxLength()));
            property.setUnit(tsl.getUnit());
            property.setEnumList(tsl.getEnumList() == null ? null : toJson(tsl.getEnumList()));
            property.setDefaultValue(tsl.getDefaultValue());
            property.setExpand(tsl.getExpand() == null ? null : toJson(tsl.getExpand()));
            property.setSort(sort++);
            iotPropertyMapper.insert(property);
        }
    }

    private void insertCommands(Long serviceId, List<TslCommand> commands) {
        int sort = 0;
        for (TslCommand tsl : commands == null ? List.<TslCommand>of() : commands) {
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
            iotCommandMapper.insert(command);
        }
    }

    private void insertEvents(Long serviceId, List<TslEvent> events) {
        int sort = 0;
        for (TslEvent tsl : events == null ? List.<TslEvent>of() : events) {
            IotEvent event = new IotEvent();
            event.setServiceId(serviceId);
            // 同命令：§3.7 的 TSL 事件只有 eventName 一个名称字段，identifier 与 eventName 同值
            event.setIdentifier(tsl.getEventName());
            event.setEventName(tsl.getEventName());
            event.setDataType(tsl.getDataType());
            event.setMaxLength(tsl.getMaxLength() == null ? null : Integer.parseInt(tsl.getMaxLength()));
            event.setUnit(tsl.getUnit());
            event.setEnumList(tsl.getEnumList() == null ? null : toJson(tsl.getEnumList()));
            event.setSort(sort++);
            iotEventMapper.insert(event);
        }
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
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR.getCode(), "JSON 序列化失败");
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR.getCode(), "JSON 反序列化失败");
        }
    }

    private java.math.BigDecimal toDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "数值格式非法：" + value);
        }
    }
}
