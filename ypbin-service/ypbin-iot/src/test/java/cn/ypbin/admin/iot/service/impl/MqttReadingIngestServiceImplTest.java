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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.ReadingIngestReport;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.enums.MqttReceiptStatus;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectReason;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestReq;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.admin.iot.values.RedisLatestValueWriter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MQTT 入站适配服务的行为门禁：**动库前整批拒绝**、租户按设备行解析、{@code requestId} 幂等。
 *
 * <p><b>为什么逐条钉「拒绝发生在动库之前」</b>：本仓铁律是「非法输入不得进入任何落库路径」，
 * 但这条约束在代码上很容易被后来的改动破坏（例如把校验挪到 ingest 之后）。因此校验用例都断言
 * 与 mapper / {@code AvailabilityService} **零交互**（{@code verifyNoInteractions}），而不只是
 * 「抛了异常」——异常类型对但已经查过库的实现在前者下依然全绿。</p>
 *
 * <p><b>变异验证（本测试真的会咬人）</b>：① 去掉 {@code apply()} 里的幂等预查 ⇒
 * {@link #duplicateRequestMustNotReingest()} 转红；② 把设备租户改成报文里的 tenantId 或常量 ⇒
 * {@link #tenantMustComeFromDeviceRowNotPayload()} 转红；③ 把体积/质量码校验挪到 ingest 之后 ⇒
 * 对应的零交互断言转红。三条都实测过（见 PR 回执的变异记录）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
class MqttReadingIngestServiceImplTest {

    private static final Long DEVICE_ID = 9300012L;

    private static final Long TENANT_ID = 1L;

    private AvailabilityService availabilityService;

    private IotDeviceMapper deviceMapper;

    private IotMqttIngestReceiptMapper receiptMapper;

    private SimpleMeterRegistry meterRegistry;

    private MqttReadingIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        availabilityService = mock(AvailabilityService.class);
        deviceMapper = mock(IotDeviceMapper.class);
        receiptMapper = mock(IotMqttIngestReceiptMapper.class);
        // 刻意**打开** FAIL_ON_UNKNOWN_PROPERTIES 再交给被测服务：这样「伪造 tenantId 被忽略」的断言
        // 证明的是服务自身钉死的解析口径（而非 Jackson/Boot 的某个默认值），默认值被改动时用例仍会咬人
        ObjectMapper mapper = new ObjectMapper().rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        meterRegistry = new SimpleMeterRegistry();
        service = new MqttReadingIngestServiceImpl(availabilityService, deviceMapper, receiptMapper,
            mapper, meterRegistry);
    }

    @Test
    @DisplayName("合法报文选设备真实租户并复用既有 ingest，落一条幂等回执")
    void acceptedRequestMustReuseExistingIngest() {
        stubDevice();
        when(availabilityService.ingestWithReport(any(ReadingIngestReq.class)))
            .thenReturn(new ReadingIngestReport(1, List.of()));

        MqttReadingIngestResult result = service.ingest(validBody("r-1"));

        assertThat(result.isDuplicated()).isFalse();
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getDropped()).isZero();
        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(availabilityService).ingestWithReport(captor.capture());
        assertThat(captor.getValue().getItems()).hasSize(1);
        assertThat(captor.getValue().getItems().get(0).getPropertyId()).isEqualTo("temperature");
        ArgumentCaptor<IotMqttIngestReceipt> receipt =
            ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(receipt.capture());
        assertThat(receipt.getValue().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(receipt.getValue().getRequestId()).isEqualTo("r-1");
        // 租户来自设备行（TENANT_ID），不是报文里的任何字段
        assertThat(receipt.getValue().getTenantId()).isEqualTo(TENANT_ID);
        // 二批 6 列：accepted 行取值正确（全落库 ⇒ 无丢弃原因/明细）
        assertThat(receipt.getValue().getReceiptStatus())
            .isEqualTo(MqttReceiptStatus.ACCEPTED.getCode());
        assertThat(receipt.getValue().getReceivedCount()).isEqualTo(1);
        assertThat(receipt.getValue().getPersistedRows()).isEqualTo(1);
        assertThat(receipt.getValue().getPersistedAt()).isNotNull();
        assertThat(receipt.getValue().getDiscardReason()).isNull();
        assertThat(receipt.getValue().getDiscardDetail()).isNull();
    }

    @Test
    @DisplayName("报文里伪造 tenantId 不生效：回执仍写设备真实租户（不信任报文里的租户）")
    void tenantMustComeFromDeviceRowNotPayload() {
        stubDevice();
        when(availabilityService.ingestWithReport(any(ReadingIngestReq.class)))
            .thenReturn(new ReadingIngestReport(1, List.of()));
        String forged = validBody("r-tenant")
            .replace("\"items\"", "\"tenantId\":9,\"items\"");

        service.ingest(forged);

        ArgumentCaptor<IotMqttIngestReceipt> receipt =
            ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(receipt.capture());
        assertThat(receipt.getValue().getTenantId())
            .as("伪造的 tenantId=9 必须被忽略：租户只按设备行解析")
            .isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("同一 requestId 重投：命中回执即整批跳过，**不再调用**落库链路")
    void duplicateRequestMustNotReingest() {
        stubDevice();
        IotMqttIngestReceipt existing = new IotMqttIngestReceipt();
        existing.setItemCount(1);
        existing.setReceiptStatus(MqttReceiptStatus.ACCEPTED.getCode());
        when(receiptMapper.selectByRequestId(DEVICE_ID, "r-dup")).thenReturn(existing);

        MqttReadingIngestResult result = service.ingest(validBody("r-dup"));

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.getAccepted()).isEqualTo(1);
        verify(availabilityService, never()).ingestWithReport(any(ReadingIngestReq.class));
        verify(receiptMapper, never()).insertReceipt(any(IotMqttIngestReceipt.class));
    }

    @Test
    @DisplayName("设备不存在：整批 400，且不调用落库链路（重投不会变好 ⇒ 4xx 不重试）")
    void unknownDeviceMustBeRejectedBeforeIngest() {
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.ingest(validBody("r-unknown")))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.DEVICE_NOT_FOUND);
        verifyNoInteractions(availabilityService);
        verifyNoInteractions(receiptMapper);
    }

    @Test
    @DisplayName("整批都被点位映射丢弃：400 + 落 rejected 回执（persisted_rows=0、原因码、明细截断）")
    void fullyDroppedBatchMustBeRejectedAndRecorded() {
        stubDevice();
        List<String> dropped = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            dropped.add("p" + i);
        }
        when(availabilityService.ingestWithReport(any(ReadingIngestReq.class)))
            .thenReturn(new ReadingIngestReport(0, dropped));

        assertThatThrownBy(() -> service.ingest(bodyWithDistinctItems("r-dropped", 10)))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.NO_ACCEPTED_ITEM);

        ArgumentCaptor<IotMqttIngestReceipt> captor = ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(captor.capture());
        IotMqttIngestReceipt receipt = captor.getValue();
        assertThat(receipt.getReceiptStatus()).isEqualTo(MqttReceiptStatus.REJECTED.getCode());
        assertThat(receipt.getReceivedCount()).isEqualTo(10);
        assertThat(receipt.getPersistedRows()).isZero();
        assertThat(receipt.getPersistedAt()).as("整批被拒没有落库时刻，不得填时间戳").isNull();
        assertThat(receipt.getDiscardReason())
            .isEqualTo(MqttIngestRejectReason.NO_ACCEPTED_ITEM.getCode());
        assertThat(receipt.getDiscardDetail())
            .as("前 8 个 pointId 逗号连接 + ,...(+N) 后缀")
            .isEqualTo("p1,p2,p3,p4,p5,p6,p7,p8,...(+2)");
    }

    @Test
    @DisplayName("🔴 命中 rejected 回执：物理删除旧行后按新批次继续（不得把可恢复变成永久拒绝）")
    void rejectedReceiptMustBeDeletedAndRetried() {
        stubDevice();
        IotMqttIngestReceipt existing = new IotMqttIngestReceipt();
        existing.setId(5L);
        existing.setReceiptStatus(MqttReceiptStatus.REJECTED.getCode());
        when(receiptMapper.selectByRequestId(DEVICE_ID, "r-retry")).thenReturn(existing);
        when(availabilityService.ingestWithReport(any(ReadingIngestReq.class)))
            .thenReturn(new ReadingIngestReport(1, List.of()));

        MqttReadingIngestResult result = service.ingest(validBody("r-retry"));

        assertThat(result.isDuplicated()).as("被拒行不得让重试被当成幂等重复").isFalse();
        assertThat(result.getAccepted()).isEqualTo(1);
        verify(receiptMapper).deleteRejectedByRequestId(DEVICE_ID, "r-retry");
        verify(availabilityService).ingestWithReport(any(ReadingIngestReq.class));
        ArgumentCaptor<IotMqttIngestReceipt> captor = ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(captor.capture());
        assertThat(captor.getValue().getReceiptStatus()).isEqualTo(MqttReceiptStatus.ACCEPTED.getCode());
    }

    @Test
    @DisplayName("部分丢弃：accepted 行附带触发码 + 丢弃明细，persisted_rows < received_count")
    void partialDropMustBeRecorded() {
        stubDevice();
        when(availabilityService.ingestWithReport(any(ReadingIngestReq.class)))
            .thenReturn(new ReadingIngestReport(1, List.of("p2")));

        MqttReadingIngestResult result = service.ingest(bodyWithDistinctItems("r-partial", 2));

        assertThat(result.isDuplicated()).isFalse();
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getDropped()).isEqualTo(1);
        ArgumentCaptor<IotMqttIngestReceipt> captor = ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(captor.capture());
        IotMqttIngestReceipt receipt = captor.getValue();
        assertThat(receipt.getReceiptStatus()).isEqualTo(MqttReceiptStatus.ACCEPTED.getCode());
        assertThat(receipt.getReceivedCount()).isEqualTo(2);
        assertThat(receipt.getPersistedRows()).isEqualTo(1);
        assertThat(receipt.getDiscardReason())
            .isEqualTo(MqttIngestRejectReason.NO_ACCEPTED_ITEM.getCode());
        assertThat(receipt.getDiscardDetail()).isEqualTo("p2");
    }

    @Test
    @DisplayName("丢弃明细：前 8 个逗号连接，超出记 ,...(+N)；超长时减少列出个数但保留计数后缀")
    void discardDetailMustTruncate() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add("p" + i);
        }
        assertThat(MqttReadingIngestServiceImpl.discardDetail(ids)).isEqualTo("p1,p2,p3");
        for (int i = 4; i <= 10; i++) {
            ids.add("p" + i);
        }
        assertThat(MqttReadingIngestServiceImpl.discardDetail(ids))
            .isEqualTo("p1,p2,p3,p4,p5,p6,p7,p8,...(+2)");
        assertThat(MqttReadingIngestServiceImpl.discardDetail(List.of())).isNull();
        assertThat(MqttReadingIngestServiceImpl.discardDetail(null)).isNull();

        List<String> longIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            longIds.add("x".repeat(128));
        }
        String longDetail = MqttReadingIngestServiceImpl.discardDetail(longIds);
        assertThat(longDetail).hasSizeLessThanOrEqualTo(MqttReadingIngestServiceImpl.MAX_DISCARD_DETAIL_LENGTH)
            .endsWith(")").contains("...(+");
    }

    @Test
    @DisplayName("质量码不在白名单：动库之前整批 400（大小写不符也拒）")
    void invalidQualityMustBeRejectedWithoutDbAccess() {
        String body = validBody("r-quality").replace("\"GOOD\"", "\"good\"");

        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.QUALITY_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("坏 requestId（含斜杠等非法字符）整批 400，且不动库")
    void invalidRequestIdMustBeRejectedWithoutDbAccess() {
        assertThatThrownBy(() -> service.ingest(validBody("bad/request/id")))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.REQUEST_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("点位标识形态非法（空格/超长字符集外）整批 400，且不动库")
    void invalidPropertyIdMustBeRejectedWithoutDbAccess() {
        String body = validBody("r-property").replace("\"temperature\"", "\"temp erature!\"");
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.PROPERTY_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("超前的 ts（设备时钟错）整批 400：与最新值写入器用同一超前上限")
    void futureTsMustBeRejected() {
        long future = System.currentTimeMillis() + RedisLatestValueWriter.MAX_FUTURE_SKEW_MS + 60_000L;
        String body = validBody("r-future").replace("1758768000000", Long.toString(future));
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.TS_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("一条消息混了两台设备整批 400（幂等键与「整批」语义都依赖单设备）")
    void mixedDevicesMustBeRejected() {
        String body = "{\"requestId\":\"r-mixed\",\"items\":["
            + item(DEVICE_ID, "temperature") + "," + item(DEVICE_ID + 1, "temperature") + "]}";
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.DEVICE_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("超过单消息条数上限整批 400，且不动库")
    void tooManyItemsMustBeRejected() {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"r-many\",\"items\":[");
        for (int i = 0; i <= MqttReadingIngestReq.MAX_ITEMS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(item(DEVICE_ID, "temperature"));
        }
        sb.append("]}");
        assertThatThrownBy(() -> service.ingest(sb.toString()))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.ITEMS_TOO_MANY);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("超长报文整批 400，**且 iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE} 必须 +1**")
    void oversizedBodyMustBeRejectedAndCounted() {
        // 这条用例是独立复核判出的真缺陷的回归：体积护栏一度写在控制器里 ⇒ 绕过了 reject() 的计数，
        // 指标恒为 0（监控规则"看起来配好了、却永不触发"）。断言指标而不是只断言异常，
        // 正是为了让"护栏放在哪一层"这件事被门禁咬住。
        String huge = "{\"requestId\":\"r-huge\",\"pad\":\""
            + "x".repeat(MqttReadingIngestReq.MAX_BODY_LENGTH + 10) + "\"}";
        assertThatThrownBy(() -> service.ingest(huge))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.BODY_TOO_LARGE);
        assertThat(meterRegistry.get(MqttReadingIngestServiceImpl.METRIC_REJECTED)
            .tag("reason", "BODY_TOO_LARGE").counter().count())
            .as("超长报文必须计入拒绝指标（写 400 但不计数 = 可观测性假阴性）")
            .isEqualTo(1.0d);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("★ 体积护栏按 UTF-8 字节数：字符数未超限但字节数超限（中文）必须整批 400")
    void oversizedUtf8BodyMustBeRejectedByByteLength() {
        // MAX-10 个 ASCII + 5 个中文：字符数 = MAX-5（< 上限），UTF-8 字节数 = MAX-10 + 15 = MAX+5（> 上限）。
        // 旧实现按 String.length() 会放行 ⇒ 实际内存/传输体积可达上限的 3 倍。
        String body = "x".repeat(MqttReadingIngestReq.MAX_BODY_LENGTH - 10) + "中".repeat(5);
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.BODY_TOO_LARGE);
        assertThat(meterRegistry.get(MqttReadingIngestServiceImpl.METRIC_REJECTED)
            .tag("reason", "BODY_TOO_LARGE").counter().count())
            .as("按字节超限也必须计入拒绝指标").isEqualTo(1.0d);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("★ utf8ByteLength 与 getBytes(UTF_8).length 逐字节一致（含代理对与落单代理）")
    void utf8ByteLengthMustMatchJdkEncoding() {
        String corpus = "abc" + "中" + "\uD83D\uDE00" + "a\uD800b" + "\uDC00";
        assertThat(MqttReadingIngestServiceImpl.utf8ByteLength(corpus))
            .as("含代理对/落单代理时也必须与 JDK 编码结果一致")
            .isEqualTo(corpus.getBytes(StandardCharsets.UTF_8).length);
        assertThat(MqttReadingIngestServiceImpl.utf8ByteLength(null)).isZero();
        assertThat(MqttReadingIngestServiceImpl.utf8ByteLength("")).isZero();
        assertThat(MqttReadingIngestServiceImpl.utf8ByteLength("中")).isEqualTo(3);
        assertThat(MqttReadingIngestServiceImpl.utf8ByteLength("\uD83D\uDE00")).isEqualTo(4);
    }

    @Test
    @DisplayName("非 JSON 报文整批 400（不静默当成功，也不动库）")
    void malformedJsonMustBeRejectedWithoutDbAccess() {
        assertThatThrownBy(() -> service.ingest("not-json"))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.INVALID_JSON);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("缺 quality 整批 400（设计 §6.5 的经典缺口：绝不能返回 200 让 EMQX 判成功）")
    void missingQualityMustBeRejected() {
        String body = validBody("r-no-quality").replace(",\"quality\":\"GOOD\"", "");
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.QUALITY_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    /**
     * 桩：设备行存在并属于 {@link #TENANT_ID}。
     */
    private void stubDevice() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of(device));
    }

    /**
     * 构造一条合法的入站报文。
     *
     * @param requestId 请求 ID
     * @return JSON 文本
     */
    private static String validBody(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"items\":["
            + item(DEVICE_ID, "temperature") + "]}";
    }

    /**
     * 构造一条含多个不同点位读数的合法入站报文（用于丢弃明细/部分丢弃用例）。
     *
     * @param requestId 请求 ID
     * @param count     读数条数（点位标识为 p1..pN）
     * @return JSON 文本
     */
    private static String bodyWithDistinctItems(String requestId, int count) {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(requestId).append("\",\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(item(DEVICE_ID, "p" + (i + 1)));
        }
        return sb.append("]}").toString();
    }

    /**
     * 构造一条合法读数条目。
     *
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @return JSON 片段
     */
    private static String item(Long deviceId, String propertyId) {
        return "{\"deviceId\":" + deviceId + ",\"propertyId\":\"" + propertyId
            + "\",\"value\":\"23.4\",\"quality\":\"GOOD\",\"ts\":1758768000000,"
            + "\"pollIntervalMs\":30000}";
    }
}
