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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.command.CommandReplyReq;
import cn.ypbin.admin.iot.command.CommandReplyResult;
import cn.ypbin.admin.iot.emqx.EmqxAdminClient;
import cn.ypbin.admin.iot.emqx.EmqxClientException;
import cn.ypbin.admin.iot.emqx.EmqxErrorCode;
import cn.ypbin.admin.iot.emqx.EmqxProperties;
import cn.ypbin.admin.iot.emqx.EmqxPublishOutcome;
import cn.ypbin.admin.iot.emqx.EmqxPublishResult;
import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 命令实例服务的行为门禁（下发语义 / 物模型校验 / 重发 / 回执幂等 / 超时扫描）。
 *
 * <p><b>变异验证（本测试会咬人，实测过）</b>：① 把 {@code 202} 当成功 ⇒
 * {@link #noSubscriberMustFailImmediately()} 转红；② 去掉物模型校验 ⇒
 * {@link #unknownIdentifierMustBeRejectedBeforePublish()} 等转红；③ 回执不校验设备一致 ⇒
 * {@link #replyFromAnotherDeviceMustBeDiscarded()} 转红；④ 超时扫描不判空短路 ⇒
 * {@link #timeoutScanMustShortCircuitOnEmpty()} 转红。</p>
 *
 * <p><b>为什么每次都断言"没有 publish"</b>：下发链路的失败语义是"**校验失败绝不发布**"——
 * 只断言抛异常会漏掉"先发了再报错"这种实现（那才是真事故：设备动了、平台说没下发）。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
class CommandInstanceServiceImplTest {

    private static final long TENANT = 1L;

    private static final long DEVICE = 9300012L;

    private static final long OTHER_DEVICE = 9300013L;

    private static final long PRODUCT = 9100001L;

    private IotDeviceMapper deviceMapper;

    private IotServiceMapper serviceMapper;

    private IotPropertyMapper propertyMapper;

    private IotCommandMapper commandMapper;

    private IotCommandInstanceMapper instanceMapper;

    private EmqxAdminClient emqxAdminClient;

    private SimpleMeterRegistry meterRegistry;

    private CommandInstanceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 需要实体的 lambda 缓存（否则构造 wrapper 时抛 can not find lambda cache）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        // Lambda 包装器需要实体元数据：命令实例 + 物模型三张表（loadThingModel 的批量查询也走 lambda）
        TableInfoHelper.initTableInfo(assistant, IotCommandInstance.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
        TableInfoHelper.initTableInfo(assistant, IotService.class);
        TableInfoHelper.initTableInfo(assistant, IotCommand.class);
    }

    @BeforeEach
    void setUp() {
        deviceMapper = mock(IotDeviceMapper.class);
        serviceMapper = mock(IotServiceMapper.class);
        propertyMapper = mock(IotPropertyMapper.class);
        commandMapper = mock(IotCommandMapper.class);
        instanceMapper = mock(IotCommandInstanceMapper.class);
        emqxAdminClient = mock(EmqxAdminClient.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new CommandInstanceServiceImpl(deviceMapper, serviceMapper, propertyMapper,
            commandMapper, instanceMapper, emqxAdminClient, new EmqxProperties(), new ObjectMapper(),
            meterRegistry);
    }

    @Test
    @DisplayName("下发属性设置：投递成功 → sent（qos1/retain=false），报文体含 properties 映射")
    void sendPropertySetMustPersistSent() {
        stubDeviceAndThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg-1"));

        CommandInstanceResp resp = service.send(DEVICE, req("property_set", "temperature", "{\"value\":25.0}"),
            CommandSource.CONSOLE, 7L);

        assertThat(resp.getStatusCode()).isEqualTo(CommandInstanceStatus.SENT.getCode());
        assertThat(resp.getSentAt()).isNotNull();
        assertThat(resp.getFinishedAt()).isNull();
        assertThat(resp.getEmqxMessageId()).isEqualTo("msg-1");
        assertThat(resp.getRequestId()).startsWith("cmd-");
        assertThat(resp.getTopic()).isEqualTo("ypbin/v1/1/9300012/down/property/set");
        assertThat(resp.getPayload()).contains("\"properties\":{\"temperature\":25.0}");
        verify(emqxAdminClient).publish(resp.getTopic(), resp.getPayload(), 1, false);
        ArgumentCaptor<IotCommandInstance> row = ArgumentCaptor.forClass(IotCommandInstance.class);
        verify(instanceMapper).insert(row.capture());
        assertThat(row.getValue().getSource()).isEqualTo(CommandSource.CONSOLE.getCode());
        assertThat(row.getValue().getOperatorUserId()).isEqualTo(7L);
        assertThat(row.getValue().getTimeoutMs()).isEqualTo(EmqxProperties.DEFAULT_COMMAND_TIMEOUT_MS);
    }

    @Test
    @DisplayName("202 = 无匹配订阅者 ⇒ **立刻** failed/NO_SUBSCRIBER（不等超时）")
    void noSubscriberMustFailImmediately() {
        stubDeviceAndThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.NO_SUBSCRIBER, null));

        CommandInstanceResp resp = service.send(DEVICE, req("property_set", "temperature", "{\"value\":1}"),
            CommandSource.CONSOLE, 1L);

        assertThat(resp.getStatusCode()).isEqualTo(CommandInstanceStatus.FAILED.getCode());
        assertThat(resp.getErrorCode()).isEqualTo("NO_SUBSCRIBER");
        assertThat(resp.getErrorMsg()).contains("设备未连接");
        assertThat(resp.getFinishedAt()).isNotNull();
        assertThat(meterRegistry.get(CommandInstanceServiceImpl.METRIC_FAILED).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("EMQX 不可达 ⇒ failed/EMQX_ERROR（可人工重发），异常带完整堆栈进日志")
    void emqxErrorMustFailWithDistinguishableCode() {
        stubDeviceAndThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenThrow(new EmqxClientException(EmqxErrorCode.UNREACHABLE, "down"));

        CommandInstanceResp resp = service.send(DEVICE, req("property_set", "temperature", "{\"value\":1}"),
            CommandSource.CONSOLE, 1L);

        assertThat(resp.getStatusCode()).isEqualTo(CommandInstanceStatus.FAILED.getCode());
        assertThat(resp.getErrorCode()).isEqualTo("EMQX_ERROR");
    }

    @Test
    @DisplayName("物模型校验：未知标识 / 属性不可写 / 未知命令 ⇒ 业务错误且**绝不发布**")
    void unknownIdentifierMustBeRejectedBeforePublish() {
        stubDeviceAndThingModel();
        assertThatThrownBy(() -> service.send(DEVICE, req("property_set", "nope", "{\"value\":1}"),
            CommandSource.CONSOLE, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(DEVICE, req("property_set", "readonly", "{\"value\":1}"),
            CommandSource.CONSOLE, 1L))
            .as("只读属性不可写").isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(DEVICE, req("service_call", "nope", "{}"),
            CommandSource.CONSOLE, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(DEVICE, req("property_get", "nope", null),
            CommandSource.CONSOLE, 1L))
            .as("读属性也要在物模型内").isInstanceOf(BusinessException.class);
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
        verify(instanceMapper, never()).insert(any(IotCommandInstance.class));
    }

    @Test
    @DisplayName("writeDesired=true 显式拒绝（不静默忽略），且不发布")
    void writeDesiredMustBeRejectedExplicitly() {
        stubDeviceAndThingModel();
        CommandSendReq req = req("property_set", "temperature", "{\"value\":1}");
        req.setWriteDesired(Boolean.TRUE);
        assertThatThrownBy(() -> service.send(DEVICE, req, CommandSource.CONSOLE, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("shadow");
        verify(emqxAdminClient, never()).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("非法类型码 ⇒ 业务错误且不发布")
    void unknownKindMustBeRejected() {
        stubDeviceAndThingModel();
        assertThatThrownBy(() -> service.send(DEVICE, req("reboot", "temperature", "{}"),
            CommandSource.CONSOLE, 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(emqxAdminClient);
    }

    @Test
    @DisplayName("读属性不带标识：实例 identifier 记审计标签、payload 的 properties 是空数组")
    void propertyGetWithoutIdentifierMeansAllReadable() {
        stubDeviceAndThingModel();
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, null));

        CommandInstanceResp resp = service.send(DEVICE, req("property_get", null, null),
            CommandSource.CONSOLE, 1L);

        assertThat(resp.getIdentifier()).isEqualTo("all-properties");
        assertThat(resp.getPayload()).contains("\"properties\":[]");
        assertThat(resp.getTopic()).isEqualTo("ypbin/v1/1/9300012/down/property/get");
    }

    @Test
    @DisplayName("人工重发：同 requestId、retry_count+1、重新打开为 sent（失败原因被清空）")
    void resendMustReopenWithSameRequestId() {
        IotCommandInstance row = instance(CommandInstanceStatus.FAILED, 0);
        stubInstanceLookup(row);
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, "msg-2"));
        when(instanceMapper.update(isNull(), any())).thenReturn(1);

        CommandInstanceResp resp = service.resend(DEVICE, "cmd-1");

        assertThat(resp.getRequestId()).isEqualTo("cmd-1");
        assertThat(resp.getRetryCount()).isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(CommandInstanceStatus.SENT.getCode());
        assertThat(resp.getErrorCode()).isNull();
        assertThat(resp.getFinishedAt()).isNull();
        LambdaUpdateWrapper<IotCommandInstance> wrapper = captureUpdate();
        assertThat(wrapper.getSqlSet())
            .as("重发必须显式 set（含把失败原因与终态时刻置 NULL）")
            .contains("error_code=", "error_msg=", "finished_at=", "status_code=", "retry_count=");
    }

    @Test
    @DisplayName("重发又失败：不得抛'非法状态转换'，retry_count+1、error_code 更新为本次失败原因（复核 M2）")
    void resendThatFailsAgainMustRecordTheNewFailure() {
        IotCommandInstance row = instance(CommandInstanceStatus.FAILED, 0);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(instanceMapper.selectByRequestId("cmd-1")).thenReturn(row);
        when(emqxAdminClient.publish(any(), any(), anyInt(), anyBoolean()))
            .thenReturn(new EmqxPublishOutcome(EmqxPublishResult.NO_SUBSCRIBER, null));
        when(instanceMapper.update(isNull(), any())).thenReturn(1);

        CommandInstanceResp resp = service.resend(DEVICE, "cmd-1");

        assertThat(resp.getRetryCount()).as("重发计数必须 +1（回滚会让运维看不到重发过）").isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(CommandInstanceStatus.FAILED.getCode());
        assertThat(resp.getErrorCode()).isEqualTo("NO_SUBSCRIBER");
        assertThat(resp.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("回执 CAS：更新影响 0 行（并发/已终态）⇒ duplicated，且 WHERE 必须限定 pending/sent")
    void replyCasMustBeGuardedByStatusCode() {
        stubInstanceLookup(instance(CommandInstanceStatus.SENT, 0));
        when(instanceMapper.update(isNull(), any())).thenReturn(0);

        CommandReplyResult result = replyFromAuthenticatedTopic(DEVICE, "cmd-1", 0, "ok", null, null);

        assertThat(result.isDuplicated()).as("CAS 落空必须判重复，不能当受理").isTrue();
        LambdaUpdateWrapper<IotCommandInstance> wrapper = captureUpdate();
        assertThat(wrapper.getSqlSegment())
            .as("CAS 的 WHERE 必须限定 status_code IN (pending, sent)（否则终态会被覆盖）")
            .contains("status_code");
    }

    @Test
    @DisplayName("只有失败/超时可重发：已成功/待投递/已取消一律业务错误")
    void resendOnlyAllowedFromFailedOrTimeout() {
        for (CommandInstanceStatus status : List.of(CommandInstanceStatus.SUCCEEDED,
            CommandInstanceStatus.PENDING, CommandInstanceStatus.SENT,
            CommandInstanceStatus.CANCELLED)) {
            stubInstanceLookup(instance(status, 0));
            assertThatThrownBy(() -> service.resend(DEVICE, "cmd-1"))
                .as("状态 %s 不可重发", status.getCode()).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(emqxAdminClient);
    }

    @Test
    @DisplayName("回执 code=0 ⇒ succeeded，reply_payload 原样保留 data 与设备时间")
    void replySuccessMustPersistVerbatimJson() {
        stubInstanceLookup(instance(CommandInstanceStatus.SENT, 0));
        when(instanceMapper.update(isNull(), any())).thenReturn(1);

        CommandReplyResult result = replyFromAuthenticatedTopic(DEVICE, "cmd-1", 0, "ok",
            "{\"temp\":26}", 1758768000000L);

        assertThat(result.isAccepted()).isTrue();
        LambdaUpdateWrapper<IotCommandInstance> wrapper = captureUpdate();
        String params = wrapper.getParamNameValuePairs().toString();
        assertThat(params).as("回执原文（含设备 data 与设备时间）必须进 reply_payload 的绑定值")
            .contains("1758768000000").contains("temp");
        assertThat(wrapper.getSqlSet()).contains("status_code=", "finished_at=",
            "reply_payload=", "error_code=", "error_msg=");
        assertThat(meterRegistry.get(CommandInstanceServiceImpl.METRIC_REPLY_ACCEPTED).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("回执非 0 ⇒ failed，且**原样保留设备给的 code 与 message**")
    void replyFailureMustPreserveDeviceCodeAndMessage() {
        stubInstanceLookup(instance(CommandInstanceStatus.SENT, 0));
        when(instanceMapper.update(isNull(), any())).thenReturn(1);

        replyFromAuthenticatedTopic(DEVICE, "cmd-1", 7, "阀位超限", null, null);

        LambdaUpdateWrapper<IotCommandInstance> wrapper = captureUpdate();
        String params = wrapper.getParamNameValuePairs().toString();
        assertThat(params).as("失败原因码与设备给的 message/code 必须原样保留")
            .contains("DEVICE_REJECTED").contains("阀位超限").contains("7");
    }

    @Test
    @DisplayName("重复回执（已终态）⇒ duplicated，且**不更新**（幂等）")
    void duplicateReplyMustNotUpdate() {
        stubInstanceLookup(instance(CommandInstanceStatus.SUCCEEDED, 0));

        CommandReplyResult result = replyFromAuthenticatedTopic(DEVICE, "cmd-1", 0, "ok", null, null);

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.isAccepted()).isFalse();
        verify(instanceMapper, never()).update(isNull(), any());
        assertThat(meterRegistry.get(CommandInstanceServiceImpl.METRIC_REPLY_DUPLICATED).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("回执不信任报文：设备与实例不一致 / 未知 requestId / 设备不存在 ⇒ 丢弃")
    void replyMustBeValidatedAgainstInstance() {
        stubInstanceLookup(instance(CommandInstanceStatus.SENT, 0));
        assertThat(replyFromAuthenticatedTopic(OTHER_DEVICE, "cmd-1", 0, "ok", null, null).isDiscarded())
            .as("同租户内 A 设备替 B 设备回执必须丢弃").isTrue();

        stubInstanceLookup(null);
        assertThat(replyFromAuthenticatedTopic(DEVICE, "cmd-unknown", 0, "ok", null, null).isDiscarded())
            .isTrue();

        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of());
        assertThat(replyFromAuthenticatedTopic(DEVICE, "cmd-1", 0, "ok", null, null).isDiscarded())
            .isTrue();

        assertThat(replyFromAuthenticatedTopic(DEVICE, "bad/id", 0, "ok", null, null).isDiscarded())
            .as("requestId 形态非法必须丢弃").isTrue();
        verify(instanceMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("回执的认证设备（EMQX 模板头）必须与载荷一致：不一致即丢弃（防替别人回执）")
    void replyMustMatchAuthenticatedDeviceFromTopic() {
        stubInstanceLookup(instance(CommandInstanceStatus.SENT, 0));

        // 认证设备来自主题（不可被载荷影响），载荷却声称另一台设备 ⇒ 丢弃
        CommandReplyResult mismatch = service.applyReply(reply(OTHER_DEVICE, "cmd-1", 0, "ok", null, null),
            OTHER_DEVICE + 1);
        assertThat(mismatch.isDiscarded()).isTrue();
        assertThat(mismatch.getReason()).contains("认证主题");

        // 一致时正常受理
        when(instanceMapper.update(isNull(), any())).thenReturn(1);
        assertThat(service.applyReply(reply(DEVICE, "cmd-1", 0, "ok", null, null), DEVICE).isAccepted())
            .isTrue();
        // 头缺失（直接 HTTP 自测）时退化为用载荷设备，仍按实例校验 ⇒ 正常受理
        assertThat(service.applyReply(reply(DEVICE, "cmd-1", 0, "ok", null, null), null).isAccepted())
            .as("头缺失时退化为用载荷设备（只有 EMQX 动作会带头；直接 HTTP 自测无头）").isTrue();
        verify(instanceMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("超时扫描：批量置超时（一条 UPDATE）；候选为空时**短路**不打库")
    void timeoutScanMustBeBatchAndShortCircuit() {
        IotCommandInstance one = instance(CommandInstanceStatus.SENT, 0);
        one.setId(11L);
        IotCommandInstance two = instance(CommandInstanceStatus.PENDING, 0);
        two.setId(12L);
        when(instanceMapper.selectTimeoutCandidates(any(), anyInt())).thenReturn(List.of(one, two));
        when(instanceMapper.markTimeout(anyList(), any())).thenReturn(2);

        assertThat(service.scanTimeouts()).isEqualTo(2);
        verify(instanceMapper).markTimeout(eq(List.of(11L, 12L)), any());
        assertThat(meterRegistry.get(CommandInstanceServiceImpl.METRIC_TIMEOUT).counter().count())
            .isEqualTo(2.0d);
    }

    @Test
    @DisplayName("超时扫描：无候选时返回 0 且不调用批量更新（空集合短路）")
    void timeoutScanMustShortCircuitOnEmpty() {
        when(instanceMapper.selectTimeoutCandidates(any(), anyInt())).thenReturn(List.of());
        assertThat(service.scanTimeouts()).isZero();
        verify(instanceMapper, never()).markTimeout(anyList(), any());
    }

    /**
     * 捕获一次 {@code update(null, wrapper)} 的 wrapper（断言显式 set 与绑定参数用）。
     *
     * @return 捕获到的更新包装器
     */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<IotCommandInstance> captureUpdate() {
        ArgumentCaptor<Wrapper<IotCommandInstance>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(instanceMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(LambdaUpdateWrapper.class);
        return (LambdaUpdateWrapper<IotCommandInstance>) captor.getValue();
    }

    /**
     * 桩：设备 + 物模型（1 个服务；属性 temperature=RW、readonly=R；命令 setTemp）。
     */
    private void stubDeviceAndThingModel() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(TENANT);
        device.setProductId(PRODUCT);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device);
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of(device));

        IotService service = new IotService();
        service.setId(100L);
        service.setProductId(PRODUCT);
        when(serviceMapper.selectList(any())).thenReturn(List.of(service));

        IotProperty writable = new IotProperty();
        writable.setIdentifier("temperature");
        writable.setAccessMode("RW");
        IotProperty readonly = new IotProperty();
        readonly.setIdentifier("readonly");
        readonly.setAccessMode("R");
        when(propertyMapper.selectList(any())).thenReturn(List.of(writable, readonly));

        IotCommand command = new IotCommand();
        command.setId(200L);
        command.setIdentifier("setTemp");
        when(commandMapper.selectList(any())).thenReturn(List.of(command));
    }

    /**
     * 构造设备（租户上下文用）。
     *
     * @return 设备
     */
    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(TENANT);
        device.setProductId(PRODUCT);
        return device;
    }

    /**
     * 桩：实例按 requestId 查得到（{@code null} = 查不到）。
     *
     * @param row 实例
     */
    private void stubInstanceLookup(IotCommandInstance row) {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(TENANT);
        when(deviceMapper.selectById(DEVICE)).thenReturn(device);
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of(device));
        when(instanceMapper.selectByRequestId("cmd-1")).thenReturn(row);
    }

    /**
     * 构造下发请求。
     *
     * @param kind       类型码
     * @param identifier 标识
     * @param params     参数
     * @return 请求
     */
    private static CommandSendReq req(String kind, String identifier, String params) {
        CommandSendReq req = new CommandSendReq();
        req.setKind(kind);
        req.setIdentifier(identifier);
        req.setParams(params);
        return req;
    }

    /**
     * 构造回执。
     *
     * @param deviceId  设备
     * @param requestId 请求 ID
     * @param code      结果码
     * @param message   说明
     * @param data      数据
     * @param ts        设备时间
     * @return 回执
     */
    /**
     * 以「主题派生的认证设备 = 载荷设备」应用回执（EMQX 模板头与载荷一致时的正常形态）。
     *
     * @param deviceId  设备 ID
     * @param requestId 请求 ID
     * @param code      结果码
     * @param message   说明
     * @param data      数据
     * @param ts        设备时间
     * @return 受理结果
     */
    private CommandReplyResult replyFromAuthenticatedTopic(Long deviceId, String requestId, int code,
                                                          String message, String data, Long ts) {
        return service.applyReply(reply(deviceId, requestId, code, message, data, ts), deviceId);
    }

    /**
     * 便捷重载：直接按字段构造回执（认证设备默认 = 载荷设备，除专门的不一致用例外）。
     *
     * @param deviceId  载荷设备
     * @param requestId 请求 ID
     * @param code      结果码
     * @param message   说明
     * @param data      数据
     * @param ts        设备时间
     * @return 回执
     */
    private static CommandReplyReq reply(Long deviceId, String requestId, int code, String message,
                                        String data, Long ts) {
        CommandReplyReq req = new CommandReplyReq();
        req.setDeviceId(deviceId);
        req.setRequestId(requestId);
        req.setCode(code);
        req.setMessage(message);
        req.setData(data);
        req.setTs(ts);
        return req;
    }

    /**
     * 构造实例行。
     *
     * @param status 状态
     * @param retry  重发次数
     * @return 实例
     */
    private static IotCommandInstance instance(CommandInstanceStatus status, int retry) {
        IotCommandInstance row = new IotCommandInstance();
        row.setId(1L);
        row.setTenantId(TENANT);
        row.setDeviceId(DEVICE);
        row.setIdentifier("temperature");
        row.setKind("property_set");
        row.setRequestId("cmd-1");
        row.setTopic("ypbin/v1/1/9300012/down/property/set");
        row.setPayload("{\"requestId\":\"cmd-1\",\"properties\":{\"temperature\":25.0}}");
        row.setStatusCode(status.getCode());
        row.setRetryCount(retry);
        row.setTimeoutMs(30_000);
        row.setSource("console");
        return row;
    }
}
