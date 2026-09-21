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

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotEvent;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotEventMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.resp.TslImportResult;
import cn.ypbin.admin.iot.model.tsl.TslCommand;
import cn.ypbin.admin.iot.model.tsl.TslDocument;
import cn.ypbin.admin.iot.model.tsl.TslEvent;
import cn.ypbin.admin.iot.model.tsl.TslPara;
import cn.ypbin.admin.iot.model.tsl.TslProperty;
import cn.ypbin.admin.iot.model.tsl.TslService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TSL 导入校验纯逻辑单测（不起 Spring、不连库）。
 *
 * <p>validateTsl 是校验规则本体（§3.7：命名规范/类型枚举/master 唯一/引用完整性，逐项报错），
 * 是 TSL 导入安全的关键逻辑，必须钉死。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotThingModelServiceImplTest {

    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final IotCommandMapper commandMapper = mock(IotCommandMapper.class);
    private final IotEventMapper eventMapper = mock(IotEventMapper.class);
    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);
    private final IotThingModelServiceImpl service = new IotThingModelServiceImpl(
        new ObjectMapper(), productMapper, propertyMapper, commandMapper, eventMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotService.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotProperty.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotCommand.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotEvent.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", serviceMapper);
    }

    @Test
    @DisplayName("合法 TSL：master 唯一 + 引用完整 + 命名/类型全合规 → 零错误")
    void validTslShouldPass() {
        TslDocument doc = validDoc();

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("devices 数组必须恰好 1 个：0 个或多于 1 个都报错")
    void devicesCountMustBeOne() {
        TslDocument doc = validDoc();
        doc.setDevices(List.of());

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("恰好包含 1 个产品定义"));

        // 多于 1 个同样拒绝
        TslDocument twoDevices = validDoc();
        twoDevices.setDevices(List.of(twoDevices.getDevices().getFirst(),
            twoDevices.getDevices().getFirst()));

        List<String> errors2 = new ArrayList<>();
        service.validateTsl(twoDevices, errors2);
        assertThat(errors2).anyMatch(e -> e.contains("恰好包含 1 个产品定义"));
    }

    @Test
    @DisplayName("master 服务有且仅有一个：缺少或多于一个都报错")
    void masterServiceMustBeExactlyOne() {
        // 缺 master：两个服务都改成非 master
        TslDocument noMaster = validDoc();
        noMaster.getServices().get(0).setOption("mandatory");
        noMaster.getServices().get(1).setOption("optional");

        List<String> errors1 = new ArrayList<>();
        service.validateTsl(noMaster, errors1);
        assertThat(errors1).anyMatch(e -> e.contains("master 服务必须有且仅有一个"));

        // 多于一个 master
        TslDocument twoMasters = validDoc();
        twoMasters.getServices().get(1).setOption("master");

        List<String> errors2 = new ArrayList<>();
        service.validateTsl(twoMasters, errors2);
        assertThat(errors2).anyMatch(e -> e.contains("master 服务必须有且仅有一个"));
    }

    @Test
    @DisplayName("引用完整性：serviceTypeCapabilities 引用的服务必须在 services 中定义")
    void serviceRefMustResolve() {
        TslDocument doc = validDoc();
        doc.getDevices().getFirst().getServiceTypeCapabilities().getFirst()
            .setServiceType("GhostService");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("服务引用未在 services 中定义"));
    }

    @Test
    @DisplayName("命名规范：属性 camelCase / 命令 UPPER_SNAKE / 事件 camelCase / 服务 PascalCase")
    void namingRulesShouldEnforce() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setPropertyName("Bad-Name");
        doc.getServices().getFirst().getCommands().getFirst().setCommandName("bad_name");
        doc.getServices().getFirst().getEvents().getFirst().setEventName("1Event");
        doc.getServices().getFirst().setServiceType("bad_service");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("服务标识格式非法"));
        assertThat(errors).anyMatch(e -> e.contains("属性标识格式非法"));
        assertThat(errors).anyMatch(e -> e.contains("命令标识格式非法"));
        assertThat(errors).anyMatch(e -> e.contains("事件标识格式非法"));
    }

    @Test
    @DisplayName("类型枚举：dataType 必须是 9 类之一（属性/命令参数/事件）")
    void dataTypeMustBeWhitelisted() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setDataType("blob");
        doc.getServices().getFirst().getCommands().getFirst().getParas().getFirst().setDataType("blob");
        doc.getServices().getFirst().getEvents().getFirst().setDataType("blob");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("属性数据类型非法"));
        assertThat(errors).anyMatch(e -> e.contains("参数数据类型非法"));
        assertThat(errors).anyMatch(e -> e.contains("事件数据类型非法"));
    }

    @Test
    @DisplayName("逐项报错：同一文档多处违规时每条都列出，不静默丢弃")
    void errorsShouldBePerItem() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setDataType("blob");
        doc.getServices().getFirst().getProperties().getFirst().setPropertyName("X-Bad");
        doc.getServices().getFirst().getCommands().getFirst().setCommandName("lower_snake");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).hasSize(3);
    }

    @Test
    @DisplayName("属性读写权限 method 必须是 R|W|RW")
    void methodMustBeRW() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setMethod("X");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("属性读写权限非法"));
    }

    @Test
    @DisplayName("导入走批量写：每个表只插一次（不随 TSL 规模退化为 N+1），且子表引用父服务 ID")
    void importShouldWriteInBatch() {
        when(productMapper.selectById(9L)).thenReturn(draftProduct(9L));
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        // 模拟雪花主键在批量插入时回填到实体
        doAnswer(invocation -> {
            List<IotService> inserted = invocation.getArgument(0);
            long nextId = 1000L;
            for (IotService item : inserted) {
                item.setId(nextId++);
            }
            return List.of();
        }).when(serviceMapper).insert(anyList());

        TslImportResult result = service.importTsl(9L, validDoc());

        // 2 个服务 → 恰好一次批量插入，且绝不出现逐行 insert(T)
        verify(serviceMapper).insert(anyList());
        verify(serviceMapper, never()).insert(any(IotService.class));
        verify(propertyMapper).insert(anyList());
        verify(commandMapper).insert(anyList());
        verify(eventMapper).insert(anyList());
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSuccessCount()).isPositive();
    }

    @Test
    @DisplayName("导入前先批量清空旧结构：按 service 主键一次 IN，不逐服务删除")
    void importShouldDeleteOldStructureInBatch() {
        when(productMapper.selectById(9L)).thenReturn(draftProduct(9L));
        IotService old1 = new IotService();
        old1.setId(501L);
        IotService old2 = new IotService();
        old2.setId(502L);
        when(serviceMapper.selectList(any())).thenReturn(List.of(old1, old2));
        doAnswer(invocation -> {
            List<IotService> inserted = invocation.getArgument(0);
            long nextId = 1000L;
            for (IotService item : inserted) {
                item.setId(nextId++);
            }
            return List.of();
        }).when(serviceMapper).insert(anyList());

        service.importTsl(9L, validDoc());

        verify(propertyMapper).delete(any());
        verify(commandMapper).delete(any());
        verify(eventMapper).delete(any());
        // 一次 IN 删除，而非 deleteById 两次
        verify(serviceMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("无服务列表时短路：不产生任何批量写")
    void importWithNoServicesShouldShortCircuit() {
        when(productMapper.selectById(9L)).thenReturn(draftProduct(9L));
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        TslDocument doc = validDoc();
        doc.setServices(List.of());

        service.importTsl(9L, doc);

        verify(serviceMapper, never()).insert(anyList());
        verify(propertyMapper, never()).insert(anyList());
    }

    @Test
    @DisplayName("serviceType 为空：逐项报错而不是抛 NPE（哈希表不收 null 键）")
    void nullServiceTypeShouldBeReportedNotThrow() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().setServiceType(null);

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("服务标识格式非法"));
    }

    @Test
    @DisplayName("maxLength 非整数：在校验阶段逐项报错，而不是写入时抛 NumberFormatException")
    void nonNumericMaxLengthShouldBeReported() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setMaxLength("abc");
        doc.getServices().getFirst().getEvents().getFirst().setMaxLength("-5");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("maxLength 非整数"));
        assertThat(errors).anyMatch(e -> e.contains("maxLength 必须为正整数"));
    }

    @Test
    @DisplayName("min/max/step 非数值：在校验阶段逐项报错")
    void nonNumericDecimalShouldBeReported() {
        TslDocument doc = validDoc();
        doc.getServices().getFirst().getProperties().getFirst().setMin("abc");

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

        assertThat(errors).anyMatch(e -> e.contains("非数值"));
    }

    /** 构造草稿态产品（importTsl 前置校验用）。 */
    private static IotProduct draftProduct(Long id) {
        IotProduct product = new IotProduct();
        product.setId(id);
        product.setModelStatus("draft");
        return product;
    }

    /** 构造合法 TSL 文档：1 产品 + 2 服务（1 master + 1 optional），每服务 1 属性/1 命令/1 事件。 */
    private static TslDocument validDoc() {
        TslDocument doc = new TslDocument();

        TslProperty property = new TslProperty();
        property.setPropertyName("temperature");
        property.setDataType("int");
        property.setMethod("R");

        TslPara para = new TslPara();
        para.setParaName("value");
        para.setDataType("int");

        TslCommand command = new TslCommand();
        command.setCommandName("SET_VALUE");
        command.setParas(List.of(para));
        command.setResponses(List.of());

        TslEvent event = new TslEvent();
        event.setEventName("alarm");
        event.setDataType("string");

        TslService master = new TslService();
        master.setServiceType("DeviceBasic");
        master.setDescription("基础服务");
        master.setOption("master");
        master.setProperties(List.of(property));
        master.setCommands(List.of(command));
        master.setEvents(List.of(event));

        TslService optional = new TslService();
        optional.setServiceType("Telemetry");
        optional.setDescription("遥测");
        optional.setOption("optional");
        optional.setProperties(List.of());
        optional.setCommands(List.of());
        optional.setEvents(List.of());

        TslDocument.TslDevice device = new TslDocument.TslDevice();
        device.setProtocolType("mqtt");
        device.setDeviceType("WaterMeter");
        TslDocument.TslServiceRef ref1 = new TslDocument.TslServiceRef();
        ref1.setServiceId("DeviceBasic");
        ref1.setServiceType("DeviceBasic");
        ref1.setOption("master");
        TslDocument.TslServiceRef ref2 = new TslDocument.TslServiceRef();
        ref2.setServiceId("Telemetry");
        ref2.setServiceType("Telemetry");
        ref2.setOption("optional");
        device.setServiceTypeCapabilities(List.of(ref1, ref2));

        doc.setServices(List.of(master, optional));
        doc.setDevices(List.of(device));
        return doc;
    }
}
