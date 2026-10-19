/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.admin.access.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.access.config.AccessEgressProperties;
import cn.ypbin.admin.iot.availability.IReadingClient;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.tools.warn.WarnThrottled;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 队列满告警的<b>节流语义</b>测试（starter #106：手写 {@code compareAndSet} → {@link WarnThrottled}）。
 *
 * <p><b>为什么需要它</b>：节流属于「错了不报错、只是日志量不对」的那类逻辑。迁移前后必须保证三件事：
 * ① 间隔内多次只打 <b>1</b> 条；② 跨间隔<b>再打</b>；③ 被抑制的条数<b>不丢</b>（限流 ≠ 静默）。
 * 前两条直接驱动 {@code WarnThrottled}（用短间隔 + 真实时间，量级 60ms，稳定不 flaky）；第三条用
 * 真实 {@link HttpAccessReadingSink} 端到端确认「队满多次只落一条 WARN，且文案带抑制条数」——
 * 只测工具类不测装配点，会漏掉「忘了接到热路径」这类缺陷。</p>
 *
 * <p><b>为什么不用假时钟</b>：{@code WarnThrottled} 唯一带 {@link java.time.Clock} 的构造器是
 * <b>包内可见</b>的（javap 实测），宿主包 {@code cn.ypbin.admin.*} 无法注入。故本类用「缩短间隔 +
 * 真实等待」验证跨窗口语义；等待上限 {@link #SHORT_INTERVAL_MS} 量级，不引入秒级 sleep。</p>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class AccessDropWarnThrottleTest {

    /**
     * 「同一窗口内必被抑制」用例的间隔：直接用<b>生产值</b>（60s）。
     *
     * <p><b>为什么不用短间隔（实测踩过）</b>：{@code WarnThrottled} 先取 {@code now}、CAS 写入
     * {@code lastWarnAtMillis}、再执行 {@code log.warn}。类首次输出日志时要在冷 JVM 里加载 logback
     * 的格式化链路，实测可耗 300ms 以上 —— 于是「首次调用返回后」的时间戳已经比 {@code lastWarnAt}
     * 大出几百毫秒，短窗口（如 300ms）会让第二条被误判为「已到期」而放行，用例假红。
     * 用 60s 窗口后，「连续 5 次调用都落在同一窗口」是物理上必然的，与冷启动耗时无关。</p>
     */
    private static final long WINDOW_MS = HttpAccessReadingSink.DROP_WARN_INTERVAL_MS;

    /**
     * 「跨窗口必再打」用例的短间隔（毫秒）：只在日志链路已充分预热后使用。
     */
    private static final long SHORT_INTERVAL_MS = 300L;

    /**
     * 跨窗口等待（毫秒）：{@code 短间隔 × 3}。
     *
     * <p>{@code Thread.sleep} 只会比请求值更久 ⇒ 必然到期；给 3 倍余量是为了容忍调度抖动。</p>
     */
    private static final long WAIT_PAST_INTERVAL_MS = SHORT_INTERVAL_MS * 3L;

    /** 端到端用例要求触发的丢弃次数（&gt; 1 条才谈得上「重复丢弃只打一条」）。 */
    private static final int DROPS_TO_TRIGGER = 4;

    private static final String SINK_LOGGER_NAME = HttpAccessReadingSink.class.getName();

    private Logger sinkLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        sinkLogger = (Logger) LoggerFactory.getLogger(SINK_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        sinkLogger.addAppender(appender);
        // 预热：让 logback 的格式化链路在计时用例开始前加载完（否则首次 log.warn 的冷启动耗时会
        // 挤进节流窗口，把「同窗口内应被抑制」误判成「已到期」——实测踩过，见 WINDOW_MS 注释）
        new WarnThrottled(0L, sinkLogger).tryWarn("预热日志链路（不计入任何断言）");
        appender.list.clear();
    }

    @AfterEach
    void detachAppender() {
        sinkLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("★ 同一窗口内多次只打 1 条，且被抑制条数逐次累加（限流 ≠ 静默）")
    void throttleShouldEmitOncePerIntervalAndCountSuppressed() {
        WarnThrottled throttle = new WarnThrottled(WINDOW_MS, sinkLogger);

        // 连续调用（微秒级）⇒ 必落在同一窗口内，不存在时序 flaky
        assertThat(throttle.tryWarn("第一条：累计丢弃 {} 条", 1L)).as("窗口内首条必须真打").isTrue();
        assertThat(throttle.tryWarn("第二条：累计丢弃 {} 条", 2L)).as("间隔内必须被抑制").isFalse();
        assertThat(throttle.tryWarn("第三条：累计丢弃 {} 条", 3L)).as("间隔内继续抑制").isFalse();
        assertThat(throttle.tryWarn("第四条：累计丢弃 {} 条", 4L)).as("间隔内继续抑制").isFalse();
        assertThat(throttle.tryWarn("第五条：累计丢弃 {} 条", 5L)).as("间隔内继续抑制").isFalse();

        assertThat(throttle.suppressedCount()).as("抑制条数必须累计（4 条）").isEqualTo(4L);
        assertThat(throttle.minIntervalMillis()).as("间隔可回读（便于诊断/断言）")
            .isEqualTo(WINDOW_MS);

        // 只真打了 1 条 WARN
        assertThat(warnMessages()).hasSize(1);
    }

    @Test
    @DisplayName("★ 跨间隔必须再打一条（sleep 只会比请求值更久 ⇒ 必然到期，不 flaky）")
    void throttleShouldEmitAgainAfterIntervalElapsed() throws InterruptedException {
        WarnThrottled throttle = new WarnThrottled(SHORT_INTERVAL_MS, sinkLogger);

        assertThat(throttle.tryWarn("窗口 1 首条")).isTrue();
        assertThat(throttle.tryWarn("窗口 1 抑制")).isFalse();
        Thread.sleep(WAIT_PAST_INTERVAL_MS);
        assertThat(throttle.tryWarn("窗口 2 首条")).as("跨间隔必须再打").isTrue();
        // 刚打过 ⇒ 立刻再打必被抑制
        assertThat(throttle.tryWarn("窗口 2 抑制")).isFalse();

        assertThat(throttle.suppressedCount())
            .as("两处抑制累加（窗口 1 一条 + 窗口 2 一条）")
            .isEqualTo(2L);
        assertThat(warnMessages()).as("真打了 2 条（每个窗口各一条）").hasSize(2);
    }

    @Test
    @DisplayName("★ 0 间隔 = 不节流（每次调用都真打）——用于确认「节流窗口被误配成 0」时仍然可观测")
    void zeroIntervalShouldNeverSuppress() {
        WarnThrottled throttle = new WarnThrottled(0L, sinkLogger);

        assertThat(throttle.tryWarn("一")).isTrue();
        assertThat(throttle.tryWarn("二")).isTrue();
        assertThat(throttle.tryWarn("三")).isTrue();
        assertThat(throttle.suppressedCount()).isZero();
        assertThat(warnMessages()).hasSize(3);
    }

    @Test
    @DisplayName("★ 端到端：真实 sink 队满多次丢弃只落 1 条「队列已满」WARN，且文案带抑制条数")
    void sinkShouldWarnOnceForRepeatedDrops() throws InterruptedException {
        // 用「消费线程卡在 ingest 上」制造稳定的队满：容量 1 ⇒ 消费线程拿走第 1 条后卡住，
        // 第 2 条占满队列，此后每条 offer 都失败。判据以「丢弃计数达标」为准，
        // 不依赖「几条抢在消费线程启动前入队」的时序（那是 flaky 来源）。
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        IReadingClient blockingClient = mock(IReadingClient.class);
        when(blockingClient.ingest(any())).thenAnswer(invocation -> {
            consumerThread.set(Thread.currentThread());
            if (!releaseConsumer.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试未在 5s 内放行消费线程");
            }
            return R.ok(1);
        });

        AccessEgressProperties properties = new AccessEgressProperties();
        properties.setQueueCapacity(1);
        properties.setBatchSize(1);
        properties.setFlushIntervalMs(20);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        HttpAccessReadingSink sink = new HttpAccessReadingSink(blockingClient, properties, meterRegistry);

        try {
            sink.accept(reading());
            awaitConsumerBlocked(consumerThread);
            // 消费线程已阻塞在 ingest 上：此后队列最多容纳 1 条，连续入队必然产生多次丢弃
            awaitDropped(sink, meterRegistry, DROPS_TO_TRIGGER);

            List<String> dropWarns = dropWarns();
            assertThat(dropWarns)
                .as("60s 窗口内重复丢弃只能落一条 WARN（否则过载时日志先于背压把进程拖垮）")
                .hasSize(1);
            assertThat(dropWarns.getFirst())
                .as("原文案必须保留（既有排障手册与看板按它检索）")
                .contains("读数上报队列已满");
            assertThat(dropWarns.getFirst())
                .as("必须写明被抑制条数——限流不等于静默")
                .contains("此前同类被抑制");
        } finally {
            releaseConsumer.countDown();
            sink.close();
        }
    }

    /**
     * 等到「消费线程已卡在 ingest 上」（由 ingest 替身写入线程引用）。
     *
     * @param consumerThread 消费线程引用
     */
    private static void awaitConsumerBlocked(AtomicReference<Thread> consumerThread)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (consumerThread.get() != null) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("消费线程未在 3s 内进入 ingest（测试前置条件不成立）");
    }

    /**
     * 持续入队直到丢弃计数达到 {@code expected}（消费线程已阻塞，故每轮至少丢一条）。
     *
     * @param sink         被测出口
     * @param meterRegistry 指标注册表（读 {@code iot.access.egress.dropped}）
     * @param expected     期望的丢弃条数
     */
    private static void awaitDropped(HttpAccessReadingSink sink, SimpleMeterRegistry meterRegistry,
            int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (meterRegistry.get("iot.access.egress.dropped").counter().count() >= expected) {
                return;
            }
            sink.accept(reading());
            Thread.sleep(2L);
        }
        throw new IllegalStateException("丢弃计数未在 3s 内达到 " + expected);
    }

    private static AccessReading reading() {
        return new AccessReading("100", "900", 1, "GOOD", Instant.parse("2026-09-22T02:00:00Z"), 5_000);
    }

    private List<String> dropWarns() {
        return warnMessages().stream()
            .filter(message -> message.contains("读数上报队列已满"))
            .collect(Collectors.toList());
    }

    private List<String> warnMessages() {
        return appender.list.stream()
            .filter(event -> "WARN".equals(event.getLevel().toString()))
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }
}
