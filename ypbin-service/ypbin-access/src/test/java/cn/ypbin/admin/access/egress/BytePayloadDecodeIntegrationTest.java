/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.ypbin.admin.access.config.AccessEgressProperties;
import cn.ypbin.admin.access.decode.ByteDecodeMetrics;
import cn.ypbin.admin.access.link.PointMappingDataListener;
import cn.ypbin.admin.iot.availability.IReadingClient;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeFailure;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 宿主可插解码 SPI 的端到端（listener → 出口）单测，逐条覆盖验收四事：
 *
 * <ol>
 *   <li><b>未注册</b> ⇒ {@code byte[]} 仍被出口闸门丢弃并计 {@code iot.access.egress.invalid}；</li>
 *   <li><b>注册并解码成功</b> ⇒ 解出值、走既有规范化/缩放、到达出口；</li>
 *   <li><b>解码失败</b> ⇒ BAD 质量 + {@code ypbin.iot.errors{type=iot.common.payload.decode-failed.<detail>}}；</li>
 *   <li><b>解码器抛异常</b> ⇒ 不吞，BAD + reason=decoder-error + ERROR 全堆栈。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class BytePayloadDecodeIntegrationTest {

    private static final String DEVICE = "100";
    private static final String TOPIC = "sensors/temp";
    private static final Instant TS = Instant.parse("2026-10-13T08:00:00Z");

    @Test
    @DisplayName("★① 未注册解码器 ⇒ byte[] 仍被出口闸门丢弃并计 iot.access.egress.invalid（行为不变）")
    void unregisteredPayloadIsStillGated() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpAccessReadingSink sink = new HttpAccessReadingSink(mock(IReadingClient.class),
            egressProperties(), meters);
        PointMappingDataListener listener = listener("mqtt",
            List.of(point(TOPIC, "temperature", "decimal")), sink, meters,
            BytePayloadDecoderRegistry.empty());

        listener.onData(PointValue.good(PointAddress.of(TOPIC), new byte[] {0x12, 0x34}, TS));

        assertThat(sink.pendingCount()).as("未解码字节不得入库").isZero();
        assertThat(meters.get("iot.access.egress.invalid").counter().count()).isEqualTo(1.0d);
        assertThat(listener.decodeFailureCount()).as("未认领不算解码失败").isZero();
        assertThat(meters.find(ByteDecodeMetrics.METRIC_NAME).counters()).isEmpty();
    }

    @Test
    @DisplayName("★①b 解码器存在但不认领该协议 ⇒ 同样落回出口闸门（不静默降级）")
    void notClaimedPayloadFallsBackToGate() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpAccessReadingSink sink = new HttpAccessReadingSink(mock(IReadingClient.class),
            egressProperties(), meters);
        PointMappingDataListener listener = listener("opcua",
            List.of(point("ns=2;s=Temp", "temperature", "decimal")), sink, meters, registry());

        listener.onData(PointValue.good(PointAddress.of("ns=2;s=Temp"), new byte[] {0x01}, TS));

        assertThat(sink.pendingCount()).isZero();
        assertThat(meters.get("iot.access.egress.invalid").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★② 注册后解出值：UINT16 big 0x1234 ⇒ 4660，走既有缩放链路")
    void registeredPayloadIsDecodedAndScaled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        AccessPointMappingDto mapping = point(TOPIC, "temperature", "decimal");
        mapping.setScaleFactor(new BigDecimal("2"));
        mapping.setOffsetValue(BigDecimal.ONE);
        PointMappingDataListener listener = listener("mqtt", List.of(mapping), readings::add, meters,
            registry());

        listener.onData(PointValue.good(PointAddress.of(TOPIC), new byte[] {0x12, 0x34}, TS));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).as("4660 * 2 + 1").isEqualTo(new BigDecimal("9321.000000"));
            assertThat(reading.timestamp()).isEqualTo(TS);
        });
        assertThat(listener.decodeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★②b 解码出的字符串仍走既有物模型类型规范化：\"abc\" 送到 int 点 ⇒ 规范化失败丢弃 + 计数")
    void decodedStringStillGoesThroughThinModelNormalization() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        BytePayloadDecoder textDecoder = new BytePayloadDecoder() {
            @Override
            public String id() {
                return "text";
            }

            @Override
            public boolean supports(DecodeContext context) {
                return true;
            }

            @Override
            public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                return DecodeOutcome.ok("abc");
            }
        };
        PointMappingDataListener listener = listener("mqtt",
            List.of(point(TOPIC, "counter", "int")), readings::add, meters,
            new DefaultBytePayloadDecoderRegistry(List.of(textDecoder)));

        listener.onData(PointValue.good(PointAddress.of(TOPIC), new byte[] {0x01}, TS));

        assertThat(readings).isEmpty();
        assertThat(listener.normalizeFailureCount()).isEqualTo(1);
        assertThat(meters.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "not-numeric").counter().count())
            .isEqualTo(1.0d);
        assertThat(listener.decodeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★③ 解码失败 ⇒ BAD 质量 + reason=length-mismatch 计数（不落库）")
    void decodeFailureBecomesBadAndCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        // 声明 4 字节 uint，实际只给 2 字节
        PointMappingDataListener listener = listener("mqtt",
            List.of(point(TOPIC, "temperature", "decimal")), readings::add, meters,
            registry(4));

        listener.onData(PointValue.good(PointAddress.of(TOPIC), new byte[] {0x12, 0x34}, TS));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.isGood()).isFalse();
            assertThat(reading.value()).isNull();
        });
        assertThat(listener.decodeFailureCount()).isEqualTo(1);
        assertThat(meters.get(ByteDecodeMetrics.METRIC_NAME)
            .tag(ByteDecodeMetrics.TAG_TYPE, "iot.common.payload.decode-failed.length-mismatch").counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★④ 解码器抛异常 ⇒ 不吞：BAD + reason=decoder-error + ERROR 全堆栈")
    void decoderExceptionIsNotSwallowed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        BytePayloadDecoder broken = new BytePayloadDecoder() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public boolean supports(DecodeContext context) {
                return true;
            }

            @Override
            public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                throw new IllegalStateException("boom");
            }
        };
        PointMappingDataListener listener = listener("mqtt",
            List.of(point(TOPIC, "temperature", "decimal")), readings::add, meters,
            new DefaultBytePayloadDecoderRegistry(List.of(broken)));

        Logger logger = (Logger) LoggerFactory.getLogger(PointMappingDataListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.onData(PointValue.good(PointAddress.of(TOPIC), new byte[] {0x01}, TS));

            assertThat(readings).singleElement()
                .satisfies(reading -> assertThat(reading.quality()).isEqualTo("BAD"));
            assertThat(listener.decodeFailureCount()).isEqualTo(1);
            assertThat(meters.get(ByteDecodeMetrics.METRIC_NAME)
                .tag(ByteDecodeMetrics.TAG_TYPE, "iot.common.payload.decode-failed.decoder-error").counter().count())
                .isEqualTo(1.0d);

            List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).as("解码器缺陷必须以 ERROR 暴露").hasSize(1);
            assertThat(errors.getFirst().getThrowableProxy()).as("ERROR 必须带完整堆栈").isNotNull();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static PointMappingDataListener listener(String protocol,
                                                     List<AccessPointMappingDto> points,
                                                     AccessReadingSink sink,
                                                     SimpleMeterRegistry meters,
                                                     BytePayloadDecoderRegistry registry) {
        return new PointMappingDataListener(DEVICE, protocol, 5_000, points, sink, meters, registry);
    }

    /** 测试用解码器：UINT big，默认 2 字节（等价于已删除的参考实现在本测试里的行为）。 */
    private static BytePayloadDecoderRegistry registry() {
        return registry(2);
    }

    private static BytePayloadDecoderRegistry registry(int length) {
        return new DefaultBytePayloadDecoderRegistry(List.of(new UintDecoder(length)));
    }

    /**
     * 极小测试解码器：定长大端无符号整数 → {@link Long}。
     *
     * <p>本测试类验的是「宿主可插解码 SPI 的链路」（注册表 → listener → 缩放 → 落列 / 失败语义），
     * 与具体解码器无关。原参考实现（MQTT 二进制定宽数值解码）已按 iot-starter #39 a 删除
     * （它在本仓双重不可能生效：enabled 默认 false + access 不依赖 mqtt 协议栈），
     * 故这里用一个内联替身保住链路验证强度——<b>不是</b>把参考实现换个名字留在生产代码里。</p>
     */
    private record UintDecoder(int length) implements BytePayloadDecoder {

        @Override
        public String id() {
            return "test-uint";
        }

        @Override
        public boolean supports(DecodeContext context) {
            // 与原参考实现同口径：只认领 mqtt 协议。这条判据是「★①b 解码器存在但不认领该协议 ⇒
            // 落回出口闸门」用例的前提（协议是 opcua 时本解码器必须**不**认领）。
            return "mqtt".equals(context.protocol().value());
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            if (payload.length != length) {
                return DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH);
            }
            long value = 0L;
            for (byte b : payload) {
                value = (value << 8) | (b & 0xFFL);
            }
            return DecodeOutcome.ok(value);
        }
    }

    private static AccessEgressProperties egressProperties() {
        AccessEgressProperties properties = new AccessEgressProperties();
        properties.setQueueCapacity(10);
        properties.setBatchSize(3);
        return properties;
    }

    private static AccessPointMappingDto point(String address, String identifier, String dataType) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(address);
        point.setIdentifier(identifier);
        point.setDataType(dataType);
        point.setPropertyId("pk-" + identifier);
        return point;
    }
}
