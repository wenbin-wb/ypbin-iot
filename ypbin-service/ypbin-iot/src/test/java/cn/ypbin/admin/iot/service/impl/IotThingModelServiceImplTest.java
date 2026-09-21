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

import cn.ypbin.admin.iot.model.tsl.TslCommand;
import cn.ypbin.admin.iot.model.tsl.TslDocument;
import cn.ypbin.admin.iot.model.tsl.TslEvent;
import cn.ypbin.admin.iot.model.tsl.TslPara;
import cn.ypbin.admin.iot.model.tsl.TslProperty;
import cn.ypbin.admin.iot.model.tsl.TslService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private final IotThingModelServiceImpl service = new IotThingModelServiceImpl(
        new ObjectMapper(), null, null, null, null);

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

        List<String> errors = new ArrayList<>();
        service.validateTsl(doc, errors);

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
