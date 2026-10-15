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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.emqx.EmqxAdminClient;
import cn.ypbin.admin.iot.emqx.EmqxProperties;
import cn.ypbin.admin.iot.emqx.EmqxPublishOutcome;
import cn.ypbin.admin.iot.emqx.EmqxPublishResult;
import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.CommandBatchSendReq;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandBatchItemResult;
import cn.ypbin.admin.iot.model.resp.CommandBatchResp;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.admin.iot.service.CommandBatchProperties;
import cn.ypbin.admin.iot.service.IotShadowService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量下发的行为门禁：全成功 / 部分失败逐条归因 / 空列表 / 超上限 / 设备与物模型不重复查询 /
 * 幂等键逐设备派生（A 命中不短路 B） / 单台异常不影响其它 / 预算耗尽。
 *
 * <p><b>为什么每条"部分失败"用例都断言"其余设备确实收到 publish"</b>：本能力的核心承诺是
 * 「单台失败不影响其它」。只断言失败条目存在，会放过"整批因为一台失败而停止"的实现——
 * 那正是要消灭的形态。</p>
 *
 * <p><b>为什么断言批量级查询次数</b>：{@code deviceMapper.selectBatchIds} 必须**恰好一次**、
 * {@code service/property/commandMapper.selectList} 各必须**恰好一次**（按 productId 去重后
 * 固定 3 条 IN 装载物模型）。逐台 send 会把它们放大成 N 次（N+1），门禁看不到（接收者不是 Mapper），
 * 只能靠用例钉住；其中 20 台同产品的用例专门证明"查询次数与设备数无关"。</p>
 *
 * <p><b>独立验收用例已并入</b>（task-13 验收方 verify-backend 的临时探针，重写为正式用例后删除临时文件）：
 * 其中三条是原作者用例没有的断言力——① {@link #singleSendIdempotentHitMustNotLoadThingModel}
 * 证明单台 {@code send()} 的幂等命中**不查物模型**（惰性加载未被本次改造破坏）；②
 * {@link #thingModelEmptyMustBeDistinguishableFromLoadFailure} 把「物模型为空」与「装载失败」
 * 钉成两个不同原因码；③ {@link #budgetExhaustionMustNotRollbackAlreadySent}
 * 断言「已发出不回滚」的强形式（受理数 == 实际 publish 次数）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class CommandInstanceBatchTest {

    private static final long TENANT = 1L;

    private static final long DEVICE_A = 9300101L;

    private static final long DEVICE_B = 9300102L;

    private static final long DEVICE_C = 9300103L;

    private static final long DEVICE_DISABLED = 9300104L;

    private static final long DEVICE_MISSING = 9300199L;

    private static final long PRODUCT = 9100101L;

    private IotDeviceMapper deviceMapper;

    private IotServiceMapper serviceMapper;

    private IotPropertyMapper propertyMapper;

    private IotCommandMapper commandMapper;

    private IotCommandInstanceMapper instanceMapper;

    private IotPointMappingMapper pointMappingMapper;

    private EmqxAdminClient emqxAdminClient;

    private IotShadowService shadowService;

    private SimpleMeterRegistry meterRegistry;

    private CommandBatchProperties batchProperties;

    private CommandInstanceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, IotCommandInstance.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
        TableInfoHelper.initTableInfo(assistant, IotService.class);
        TableInfoHelper.initTableInfo(assistant, IotCommand.class);
        TableInfoHelper.initTableInfo(assistant, IotPointMapping.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
    }

    @BeforeEach
    void setUp() {
        deviceMapper = mock(IotDeviceMapper.class);
        serviceMapper = mock(IotServiceMapper.class);
        propertyMapper = mock(IotPropertyMapper.class);
        commandMapper = mock(IotCommandMapper.class);
        instanceMapper = mock(IotCommandInstanceMapper.class);
        pointMappingMapper = mock(IotPointMappingMapper.class);
        emqxAdminClient = mock(EmqxAdminClient.class);
        shadowService = mock(IotShadowService.class);
        meterRegistry = new SimpleMeterRegistry();
        batchProperties = new CommandBatchProperties();
        service = new CommandInstanceServiceImpl(deviceMapper, serviceMapper, propertyMapper,
            commandMapper, instanceMapper, pointMappingMapper, emqxAdminClient, new EmqxProperties(),
            new ObjectMapper(), shadowService, batchProperties, meterRegistry);
    }

    @Test
    @DisplayName("全成功：逐台 sent，total/accepted/delivered/rejected 计数正确，物模型只批量查一次")
    void allSucceedMustReportEveryDevice() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A, DEVICE_B), CommandSource.CONSOLE, 7L);

        assertThat(resp.getTotal()).isEqualTo(2);
        assertThat(resp.getAccepted()).isEqualTo(2);
        assertThat(resp.getRejected()).isZero();
        assertThat(resp.getDelivered()).isEqualTo(2);
        assertThat(resp.getResults()).extracting(CommandBatchItemResult::getDeviceId)
            .containsExactly(DEVICE_A, DEVICE_B);
        for (CommandBatchItemResult item : resp.getResults()) {
            assertThat(item.getAccepted()).isTrue();
            assertThat(item.getOk()).isTrue();
            assertThat(item.getStatusCode()).isEqualTo(CommandInstanceStatus.SENT.getCode());
            assertThat(item.getRequestId()).startsWith("cmd-");
            assertThat(item.getCode()).isNull();
        }
        // 每台各发一条独立报文（topic 含各自 deviceId）
        verify(emqxAdminClient, times(2)).publish(any(), any(), anyInt(), anyBoolean());
        // 循环外批量：设备一次 IN、物模型一次（同产品去重，不是每台 2 次）
        verify(deviceMapper, times(1)).selectBatchIds(anyList());
        verify(serviceMapper, times(1)).selectList(any());
        verify(propertyMapper, times(1)).selectList(any());
        verify(commandMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("部分失败：不存在/停用/缺产品逐条归因，其余设备照常下发（单台失败不影响其它）")
    void partialFailureMustBeReportedPerDeviceWithoutBlockingOthers() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_DISABLED, PRODUCT, EntityStatus.DISABLED.getCode()),
            device(DEVICE_B, null, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));

        CommandBatchResp resp = service.sendBatch(
            batch(DEVICE_A, DEVICE_DISABLED, DEVICE_B, DEVICE_MISSING), CommandSource.CONSOLE, 1L);

        assertThat(resp.getTotal()).isEqualTo(4);
        assertThat(resp.getAccepted()).isEqualTo(1);
        assertThat(resp.getRejected()).isEqualTo(3);
        assertThat(resp.getDelivered()).isEqualTo(1);
        Map<Long, CommandBatchItemResult> byDevice = indexByDevice(resp.getResults());
        assertThat(byDevice.get(DEVICE_A).getAccepted()).isTrue();
        assertThat(byDevice.get(DEVICE_MISSING).getCode()).isEqualTo("DEVICE_NOT_FOUND");
        assertThat(byDevice.get(DEVICE_MISSING).getMessage()).contains("不存在");
        assertThat(byDevice.get(DEVICE_DISABLED).getCode()).isEqualTo("DEVICE_DISABLED");
        assertThat(byDevice.get(DEVICE_DISABLED).getMessage()).contains("停用");
        assertThat(byDevice.get(DEVICE_B).getCode()).isEqualTo("DEVICE_WITHOUT_PRODUCT");
        assertThat(byDevice.get(DEVICE_B).getMessage()).contains("未绑定产品");
        // 被拒设备不得产生任何实例/投递；好设备必须真的发出去
        verify(emqxAdminClient, times(1)).publish(any(), any(), anyInt(), anyBoolean());
        verify(instanceMapper, times(1)).insert(any(IotCommandInstance.class));
    }

    @Test
    @DisplayName("物模型为空 ⇒ THING_MODEL_EMPTY；标识不在物模型 ⇒ COMMAND_REJECTED 且逐条给人话")
    void thingModelProblemsMustBeAttributedPerDevice() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        CommandBatchResp resp = service.sendBatch(
            batchWithIdentifier("no-such-property", DEVICE_A, DEVICE_B), CommandSource.CONSOLE, 1L);

        assertThat(resp.getAccepted()).isZero();
        assertThat(resp.getRejected()).isEqualTo(2);
        assertThat(resp.getResults()).allSatisfy(item -> {
            assertThat(item.getCode()).isEqualTo("COMMAND_REJECTED");
            assertThat(item.getMessage()).contains("物模型中没有属性");
        });
        // 预解析已定论 ⇒ 循环里不再查库、更不发布
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
        verify(instanceMapper, never()).insert(any(IotCommandInstance.class));
    }

    @Test
    @DisplayName("物模型装载失败 ⇒ 逐台 THING_MODEL_LOAD_FAILED（「加载失败」与「物模型为空」必须区分）")
    void thingModelLoadFailureMustNotBeReportedAsEmptyModel() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        when(serviceMapper.selectList(any())).thenThrow(new IllegalStateException("库连接断了"));

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A), CommandSource.CONSOLE, 1L);

        assertThat(resp.getRejected()).isEqualTo(1);
        CommandBatchItemResult item = resp.getResults().get(0);
        assertThat(item.getAccepted()).isFalse();
        assertThat(item.getCode())
            .as("必须是加载失败，不能谎报成「物模型为空」这种确定结论")
            .isEqualTo("THING_MODEL_LOAD_FAILED");
        assertThat(item.getMessage()).contains("物模型加载失败");
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("空列表 / 含 null 元素 ⇒ 业务错误且整批拒绝（不发布任何命令）")
    void emptyOrNullElementsMustBeRejectedAsWholeBatch() {
        assertThatThrownBy(() -> service.sendBatch(new CommandBatchSendReq(), CommandSource.CONSOLE, 1L))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");

        CommandBatchSendReq nullElement = batch(DEVICE_A);
        List<Long> withNull = new ArrayList<>();
        withNull.add(DEVICE_A);
        withNull.add(null);
        nullElement.setDeviceIds(withNull);
        assertThatThrownBy(() -> service.sendBatch(nullElement, CommandSource.CONSOLE, 1L))
            .isInstanceOf(BusinessException.class).hasMessageContaining("设备 ID 不能为空");
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("超上限 ⇒ 业务错误并点明配置项；去重后判定（重复项不占配额）")
    void exceedingMaxDevicesMustBeRejected() {
        batchProperties.setMaxDevices(2);
        assertThatThrownBy(() -> service.sendBatch(batch(DEVICE_A, DEVICE_B, DEVICE_C),
            CommandSource.CONSOLE, 1L))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 2")
            .hasMessageContaining("max-devices");

        // 去重后 2 台（=上限）应放行：重复项不占配额
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));
        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A, DEVICE_A, DEVICE_B),
            CommandSource.CONSOLE, 1L);
        assertThat(resp.getTotal()).as("去重后按 2 台计").isEqualTo(2);
        assertThat(resp.getAccepted()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 幂等键逐设备派生：A 命中已有实例不短路 B；整批重放两台都不二次下发")
    void clientKeyMustDerivePerDeviceSoHitOnOneDoesNotShortCircuitAnother() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));
        // A 已有该派生键的实例（模拟上一批只成功了 A）；B 没有
        IotCommandInstance existingA = instance("cmd-existing-a", DEVICE_A);
        when(instanceMapper.selectByClientKey(eq(DEVICE_A), eq("batch-key-1:" + DEVICE_A)))
            .thenReturn(existingA);
        when(instanceMapper.selectByClientKey(eq(DEVICE_B), eq("batch-key-1:" + DEVICE_B)))
            .thenReturn(null);

        CommandBatchSendReq req = batchWithKey("batch-key-1", DEVICE_A, DEVICE_B);
        CommandBatchResp first = service.sendBatch(req, CommandSource.CONSOLE, 1L);

        Map<Long, CommandBatchItemResult> byDevice = indexByDevice(first.getResults());
        assertThat(byDevice.get(DEVICE_A).getRequestId()).isEqualTo("cmd-existing-a");
        assertThat(byDevice.get(DEVICE_B).getRequestId()).startsWith("cmd-");
        assertThat(first.getAccepted()).isEqualTo(2);
        // 只有 B 真的插入/发布（A 幂等命中，不二次下发）
        verify(instanceMapper, times(1)).insert(any(IotCommandInstance.class));
        verify(emqxAdminClient, times(1)).publish(any(), any(), anyInt(), anyBoolean());

        // 整批重放：两台都命中 ⇒ 都不二次下发、都不插入
        IotCommandInstance existingB = instance("cmd-existing-b", DEVICE_B);
        when(instanceMapper.selectByClientKey(eq(DEVICE_B), eq("batch-key-1:" + DEVICE_B)))
            .thenReturn(existingB);
        CommandBatchResp replay = service.sendBatch(req, CommandSource.CONSOLE, 1L);

        assertThat(indexByDevice(replay.getResults()).get(DEVICE_A).getRequestId())
            .isEqualTo("cmd-existing-a");
        assertThat(indexByDevice(replay.getResults()).get(DEVICE_B).getRequestId())
            .isEqualTo("cmd-existing-b");
        assertThat(replay.getAccepted()).isEqualTo(2);
        verify(instanceMapper, times(1)).insert(any(IotCommandInstance.class));
        verify(emqxAdminClient, times(1)).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("单台异常不影响其它：sendWithModel 抛 RuntimeException 只让该台 INTERNAL_ERROR")
    void singleDeviceRuntimeExceptionMustNotAbortTheBatch() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        AtomicInteger calls = new AtomicInteger();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟单台意外");
            }
            return new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg");
        });

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A, DEVICE_B), CommandSource.CONSOLE, 1L);

        // 第一台：非预期运行时异常 ⇒ 该台 INTERNAL_ERROR（整批不抛、不 500）
        // 第二台：**必须照常下发**（这才是"不影响其它"——只断言失败条目存在会放过"一台失败即整批停止"）
        assertThat(resp.getTotal()).isEqualTo(2);
        Map<Long, CommandBatchItemResult> byDevice = indexByDevice(resp.getResults());
        assertThat(byDevice.get(DEVICE_A).getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(byDevice.get(DEVICE_A).getMessage())
            .as("措辞必须诚实：实例可能已落库，不能简单说没下发")
            .contains("可能已生成命令记录");
        assertThat(byDevice.get(DEVICE_B).getAccepted()).isTrue();
        assertThat(byDevice.get(DEVICE_B).getOk()).isTrue();
        assertThat(resp.getAccepted()).isEqualTo(1);
        assertThat(resp.getRejected()).isEqualTo(1);
        assertThat(resp.getDelivered()).isEqualTo(1);
        verify(emqxAdminClient, times(2)).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("总耗时预算为 0 时后续设备逐条 BATCH_BUDGET_EXCEEDED，前面已发出的不回滚")
    void exhaustedBudgetMustBeReportedPerRemainingDevice() {
        // 预算取得足够大，保证"第一台的循环开始检查"必然通过（否则用例会随机器负载抖动，
        // 变成"看起来在测预算、其实在测调度延迟"）；再用 sleep 把预算确定性耗光。
        batchProperties.setTotalBudgetMs(50);
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            // 第一台投递完就把预算耗光（模拟慢 EMQX）
            Thread.sleep(200L);
            return new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg");
        });

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A, DEVICE_B), CommandSource.CONSOLE, 1L);

        assertThat(resp.getTotal()).isEqualTo(2);
        assertThat(indexByDevice(resp.getResults()).get(DEVICE_B).getCode())
            .isEqualTo("BATCH_BUDGET_EXCEEDED");
        assertThat(indexByDevice(resp.getResults()).get(DEVICE_B).getMessage())
            .contains("预算").contains("total-budget-ms");
        assertThat(resp.getRejected()).isEqualTo(1);
        assertThat(resp.getAccepted()).isEqualTo(1);
    }

    @Test
    @DisplayName("设备离线（EMQX 202）：受理但未送达 —— accepted=true / ok=false / NO_SUBSCRIBER")
    void offlineDeviceMustBeAcceptedButNotDelivered() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.NO_SUBSCRIBER, null));

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A), CommandSource.CONSOLE, 1L);

        CommandBatchItemResult item = resp.getResults().get(0);
        assertThat(item.getAccepted()).as("平台受理了（实例已落库可重发）").isTrue();
        assertThat(item.getOk()).as("但设备没收到").isFalse();
        assertThat(item.getErrorCode()).isEqualTo("NO_SUBSCRIBER");
        assertThat(item.getMessage()).contains("设备未连接").contains("重发");
        assertThat(resp.getAccepted()).isEqualTo(1);
        assertThat(resp.getDelivered()).isZero();
        assertThat(resp.getRejected()).isZero();
    }

    @Test
    @DisplayName("派生幂等键超长 ⇒ 整批拒绝并说清派生规则（不截断，避免串单）")
    void overlongDerivedClientKeyMustBeRejected() {
        // 64 字符请求级键 + ':' + 设备 ID 必然超 64
        String tooLong = "x".repeat(64);
        CommandBatchSendReq req = batchWithKey(tooLong, DEVICE_A);
        assertThatThrownBy(() -> service.sendBatch(req, CommandSource.CONSOLE, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("超过上限");
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("service_call 未配命令点位 ⇒ payload 不含 point（契约与单台一致）")
    void serviceCallWithoutMappingKeepsContract() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        when(pointMappingMapper.selectList(any())).thenReturn(List.of());
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));

        CommandSendReq template = new CommandSendReq();
        template.setKind("service_call");
        template.setIdentifier("setTemp");
        template.setParams(Map.of("target", 26));
        CommandBatchSendReq req = new CommandBatchSendReq();
        req.setDeviceIds(List.of(DEVICE_A));
        req.setCommand(template);
        service.sendBatch(req, CommandSource.CONSOLE, 1L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(emqxAdminClient).publish(any(), payload.capture(), anyInt(), anyBoolean());
        assertThat(payload.getValue()).doesNotContain("point").contains("\"params\":{\"target\":26}");
    }

    @Test
    @DisplayName("单台 send() 幂等命中 ⇒ 不查物模型、不发布（本次批量改造不得破坏惰性加载）")
    void singleSendIdempotentHitMustNotLoadThingModel() {
        // ⚠️ 这条是**回归护栏**：send() 被拆出共用的 sendWithModel 后，物模型加载若被提前到
        //    幂等检查之前，幂等命中也会白查 3 条 SQL——语义没变但把"幂等命中不查库"的历史保证打破了。
        //    只按"返回了已有实例"断言抓不到它，必须断言**零查询**。
        when(deviceMapper.selectById(eq(DEVICE_A))).thenReturn(
            device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        when(instanceMapper.selectByClientKey(eq(DEVICE_A), eq("key-1")))
            .thenReturn(instance("cmd-hit", DEVICE_A));

        CommandSendReq req = sendReq();
        req.setClientRequestId("key-1");
        CommandInstanceResp resp = service.send(DEVICE_A, req, CommandSource.CONSOLE, 7L);

        assertThat(resp.getRequestId()).isEqualTo("cmd-hit");
        verify(serviceMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectList(any());
        verify(commandMapper, never()).selectList(any());
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
        verify(instanceMapper, never()).insert(any(IotCommandInstance.class));
    }

    @Test
    @DisplayName("「物模型为空」与「物模型装载失败」必须是两个可区分的原因码（同一批内不互相顶替）")
    void thingModelEmptyMustBeDistinguishableFromLoadFailure() {
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        when(serviceMapper.selectList(any())).thenThrow(new IllegalStateException("连接断了"));
        CommandBatchResp loadFailed = service.sendBatch(batch(DEVICE_A), CommandSource.CONSOLE, 1L);
        assertThat(loadFailed.getResults().get(0).getCode()).isEqualTo("THING_MODEL_LOAD_FAILED");

        // 换成"查询成功但没有服务定义" ⇒ EMPTY（两者要修的东西完全不同：修连接 vs 修产品配置）
        setUp();
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()));
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        CommandBatchResp empty = service.sendBatch(batch(DEVICE_A), CommandSource.CONSOLE, 1L);
        assertThat(empty.getResults().get(0).getCode()).isEqualTo("THING_MODEL_EMPTY");
        assertThat(loadFailed.getResults().get(0).getCode())
            .as("两个原因码必须可区分（前端据此提示用户去修不同的东西）")
            .isNotEqualTo(empty.getResults().get(0).getCode());
    }

    @Test
    @DisplayName("预算耗尽的强形式：已发出的**不回滚**（受理数 == 实际 publish 次数）")
    void budgetExhaustionMustNotRollbackAlreadySent() throws Exception {
        batchProperties.setTotalBudgetMs(50);
        stubDevices(device(DEVICE_A, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_B, PRODUCT, EntityStatus.ENABLED.getCode()),
            device(DEVICE_C, PRODUCT, EntityStatus.ENABLED.getCode()));
        stubThingModel();
        AtomicInteger published = new AtomicInteger();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            published.incrementAndGet();
            // 单台耗时远大于阈值：第一台放行后阈值即被顶穿
            Thread.sleep(120L);
            return new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg");
        });

        CommandBatchResp resp = service.sendBatch(batch(DEVICE_A, DEVICE_B, DEVICE_C),
            CommandSource.CONSOLE, 1L);

        Map<Long, CommandBatchItemResult> byDevice = indexByDevice(resp.getResults());
        assertThat(byDevice.get(DEVICE_A).getAccepted()).isTrue();
        assertThat(byDevice.get(DEVICE_B).getCode()).isEqualTo("BATCH_BUDGET_EXCEEDED");
        assertThat(byDevice.get(DEVICE_C).getCode()).isEqualTo("BATCH_BUDGET_EXCEEDED");
        // 「已发出的不回滚」的强断言：受理台数必须**恰好等于**真正发布出去的次数
        assertThat(resp.getAccepted())
            .as("已受理但未送达也计入 accepted；受理数必须等于实际 publish 次数（无回滚）")
            .isEqualTo(published.get());
        assertThat(resp.getAccepted() + resp.getRejected()).isEqualTo(resp.getTotal());
        // 阈值是循环入口检查点：第一台必然放行，因此至少发出 1 条、且不会把 3 台全发完
        assertThat(published.get()).isBetween(1, 2);
    }

    @Test
    @DisplayName("N+1 反例：20 台同产品的查询次数与设备数无关（设备 1 次 + 物模型 3 条 IN），publish 仍 20 次")
    void queryCountMustBeIndependentOfDeviceCount() {
        List<IotDevice> devices = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long id = 9500100L + i;
            devices.add(device(id, PRODUCT, EntityStatus.ENABLED.getCode()));
            ids.add(id);
        }
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(devices);
        stubThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg"));

        CommandBatchResp resp = service.sendBatch(batch(ids.toArray(new Long[0])),
            CommandSource.CONSOLE, 1L);

        assertThat(resp.getTotal()).isEqualTo(20);
        assertThat(resp.getAccepted()).isEqualTo(20);
        // 查询与设备数**无关**：设备 1 次 IN、物模型固定 3 条 IN
        verify(deviceMapper, times(1)).selectBatchIds(anyList());
        verify(serviceMapper, times(1)).selectList(any());
        verify(propertyMapper, times(1)).selectList(any());
        verify(commandMapper, times(1)).selectList(any());
        // 而 MQTT 发布必然是逐台的 20 次（N 个不可合并的外部动作）
        verify(emqxAdminClient, times(20)).publish(any(), any(), anyInt(), anyBoolean());
    }

    /**
     * 按 deviceId 建索引（结果顺序断言之外的便捷查法）。
     *
     * @param results 逐台结果
     * @return deviceId → 结果
     */
    private static Map<Long, CommandBatchItemResult> indexByDevice(List<CommandBatchItemResult> results) {
        Map<Long, CommandBatchItemResult> byDevice = new java.util.LinkedHashMap<>();
        for (CommandBatchItemResult item : results) {
            byDevice.put(item.getDeviceId(), item);
        }
        return byDevice;
    }

    /**
     * 桩：设备清单（{@code selectBatchIds} 返回给定设备）。
     *
     * @param devices 设备
     */
    private void stubDevices(IotDevice... devices) {
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of(devices));
    }

    /**
     * 桩：物模型（1 个服务；属性 temperature=RW；命令 setTemp）。
     */
    private void stubThingModel() {
        IotService modelService = new IotService();
        modelService.setId(100L);
        modelService.setProductId(PRODUCT);
        when(serviceMapper.selectList(any())).thenReturn(List.of(modelService));

        IotProperty writable = new IotProperty();
        writable.setIdentifier("temperature");
        writable.setAccessMode("RW");
        writable.setServiceId(100L);
        when(propertyMapper.selectList(any())).thenReturn(List.of(writable));

        IotCommand command = new IotCommand();
        command.setId(200L);
        command.setIdentifier("setTemp");
        command.setServiceId(100L);
        when(commandMapper.selectList(any())).thenReturn(List.of(command));
    }

    /**
     * 构建设备。
     *
     * @param id        主键
     * @param productId 产品 ID（可空）
     * @param status    启停位
     * @return 设备
     */
    private static IotDevice device(long id, Long productId, Integer status) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(TENANT);
        device.setProductId(productId);
        device.setStatus(status);
        return device;
    }

    /**
     * 构造批量请求（默认 property_set / temperature）。
     *
     * @param deviceIds 设备
     * @return 请求
     */
    private static CommandBatchSendReq batch(Long... deviceIds) {
        return batchWithKey(null, deviceIds);
    }

    /**
     * 构造带幂等键的批量请求。
     *
     * @param clientRequestId 请求级幂等键（可空）
     * @param deviceIds       设备
     * @return 请求
     */
    private static CommandBatchSendReq batchWithKey(String clientRequestId, Long... deviceIds) {
        return batchWithIdentifierAndKey("temperature", clientRequestId, deviceIds);
    }

    /**
     * 构造单台下发请求（默认 property_set / temperature；用于单台路径的回归护栏）。
     *
     * @return 请求
     */
    private static CommandSendReq sendReq() {
        CommandSendReq req = new CommandSendReq();
        req.setKind("property_set");
        req.setIdentifier("temperature");
        req.setParams(Map.of("value", 25.0));
        return req;
    }

    /**
     * 构造指定标识的批量请求。
     *
     * @param identifier 标识
     * @param deviceIds  设备
     * @return 请求
     */
    private static CommandBatchSendReq batchWithIdentifier(String identifier, Long... deviceIds) {
        return batchWithIdentifierAndKey(identifier, null, deviceIds);
    }

    /**
     * 构造批量请求（完整参数）。
     *
     * @param identifier      标识
     * @param clientRequestId 请求级幂等键（可空）
     * @param deviceIds       设备
     * @return 请求
     */
    private static CommandBatchSendReq batchWithIdentifierAndKey(String identifier, String clientRequestId,
                                                                 Long... deviceIds) {
        CommandSendReq template = new CommandSendReq();
        template.setKind("property_set");
        template.setIdentifier(identifier);
        template.setParams(Map.of("value", 25.0));
        CommandBatchSendReq req = new CommandBatchSendReq();
        req.setDeviceIds(List.of(deviceIds));
        req.setCommand(template);
        req.setClientRequestId(clientRequestId);
        return req;
    }

    /**
     * 构造命令实例行。
     *
     * @param requestId 请求 ID
     * @param deviceId  设备
     * @return 实例
     */
    private static IotCommandInstance instance(String requestId, long deviceId) {
        IotCommandInstance row = new IotCommandInstance();
        row.setId(1L);
        row.setTenantId(TENANT);
        row.setDeviceId(deviceId);
        row.setIdentifier("temperature");
        row.setKind("property_set");
        row.setRequestId(requestId);
        row.setStatusCode(CommandInstanceStatus.SENT.getCode());
        row.setRetryCount(0);
        row.setTimeoutMs(30_000);
        row.setSource("console");
        return row;
    }
}
