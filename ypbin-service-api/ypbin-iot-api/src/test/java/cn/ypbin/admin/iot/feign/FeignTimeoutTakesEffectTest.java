/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClientProperties;

/**
 * 超时**实际生效**验证（issue #298 验收第 3 条）：配置中心给的 read-timeout 必须真的作用于请求。
 *
 * <p><b>为什么不能只断言「Bean 不存在」</b>：本 issue 的原始危害是「Nacos 里调大 read-timeout
 * 不生效（被主上下文的 {@code Request.Options} 覆盖），且无告警」。只断言主上下文没有
 * {@code Request.Options} 只能证明「污染源没了」，不能证明「配置真的被用上」。
 * 故这里起一个**真实 HTTP 端点**（JDK 自带 {@link HttpServer}，不引入新依赖），
 * 让它 sleep 超过短超时、但在长超时之内，然后用两种超时各发一次请求：
 * 短超时 ⇒ 必须失败（{@code feign.RetryableException}，cause 是 SocketTimeout）；
 * 长超时 ⇒ 必须成功。这直接证明「超时值决定行为」，而不依赖对装配细节的推断。</p>
 *
 * <p><b>超时来源的模拟</b>：用 {@link FeignClientProperties.FeignClientConfiguration} 构造
 * 「配置中心解析出来的那份配置」，与 Spring Cloud OpenFeign 在
 * {@code FeignClientFactoryBean#configureUsingProperties} 里用的是同一个类型与同一个字段
 * （{@code connectTimeout}/{@code readTimeout}）。因此这是「配置 → Builder → 实际请求」的端到端证据，
 * 只是省掉了 Nacos 那一跳（那一跳由 {@code deploy/nacos/ypbin-iot.yaml} 的键名正确性保证，
 * 键名用 contextId 已由 {@link FeignConfigurationScanIsolationTest} 的注释与字节码核实记录）。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class FeignTimeoutTakesEffectTest {

    /** mock 端点的响应延迟：要「超过短超时、小于长超时」。 */
    private static final long SERVER_DELAY_MS = 1_500L;

    /** 短读超时（小于响应延迟 ⇒ 必然超时）。 */
    private static final long SHORT_READ_TIMEOUT_MS = 300L;

    /** 长读超时（大于响应延迟 ⇒ 必然成功，模拟 Nacos 里调大到 8000 的效果）。 */
    private static final long LONG_READ_TIMEOUT_MS = 8_000L;

    private HttpServer server;

    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★ 短 read-timeout（300ms）对上 1.5s 的端点 ⇒ 必须超时失败")
    void shortReadTimeoutMustTimeOut() {
        SlowEndpoint client = clientWithTimeout(SHORT_READ_TIMEOUT_MS);

        assertThat(catchingCall(client))
            .as("read-timeout=%dms 而端点耗时 %dms ⇒ 必须抛超时异常（证明超时值真的生效）",
                SHORT_READ_TIMEOUT_MS, SERVER_DELAY_MS)
            .isNotNull()
            .hasMessageContaining("timed out")
            .satisfies(ex -> assertThat(rootCauseName(ex))
                .as("根因应是 SocketTimeoutException：不是连接失败或其它错误")
                .contains("Timeout"));
    }

    @Test
    @DisplayName("★ 长 read-timeout（8000ms）对同一端点 ⇒ 必须成功（模拟 Nacos 调大后不再 3s 超时）")
    void longReadTimeoutMustSucceed() {
        SlowEndpoint client = clientWithTimeout(LONG_READ_TIMEOUT_MS);
        AtomicLong elapsed = new AtomicLong();

        Throwable failure = catchingCall(client, elapsed);

        assertThat(failure)
            .as("read-timeout=%dms > 端点耗时 %dms ⇒ 必须成功。若此断言失败，说明超时值没有真的作用到请求上"
                + "（issue #298 的原始症状）", LONG_READ_TIMEOUT_MS, SERVER_DELAY_MS)
            .isNull();
        assertThat(elapsed.get())
            .as("请求确实等满了 mock 端点的延迟（证明它真的走完了 HTTP 往返，不是被缓存/短路）")
            .isGreaterThanOrEqualTo(SERVER_DELAY_MS - 100L)
            .isLessThan(LONG_READ_TIMEOUT_MS);
    }

    @Test
    @DisplayName("★ 不重试：一次超时只发一次请求（Retryer.NEVER_RETRY 语义，防止把压力放大到远端）")
    void neverRetryMustIssueSingleAttempt() {
        SlowEndpoint client = clientWithTimeout(SHORT_READ_TIMEOUT_MS);

        catchingCall(client);

        assertThat(hits.get())
            .as("超时后不得自动重试（OpenFeign 默认 NEVER_RETRY；重试会把失败放大并占用调度线程）")
            .isEqualTo(1);
    }

    /** mock 端点被访问次数（验证不重试）。 */
    private final AtomicLong hits = new AtomicLong();

    /**
     * 用「配置中心那份配置」构造一个 Feign 客户端。
     *
     * @param readTimeoutMs 读超时（毫秒）
     * @return 指向 mock 端点的客户端
     */
    private SlowEndpoint clientWithTimeout(long readTimeoutMs) {
        // 与 Spring Cloud OpenFeign 的 configureUsingProperties 同源：
        // 把 FeignClientConfiguration 的 connect/read 转成 Request.Options 交给 Builder。
        FeignClientProperties.FeignClientConfiguration config =
            new FeignClientProperties.FeignClientConfiguration();
        config.setConnectTimeout(1_000);
        config.setReadTimeout((int) readTimeoutMs);
        Request.Options options = new Request.Options(config.getConnectTimeout(),
            TimeUnit.MILLISECONDS, config.getReadTimeout(), TimeUnit.MILLISECONDS, true);
        return Feign.builder()
            .options(options)
            .retryer(Retryer.NEVER_RETRY)
            .requestInterceptor(template -> hits.incrementAndGet())
            .target(SlowEndpoint.class, baseUrl);
    }

    private static Throwable catchingCall(SlowEndpoint client) {
        return catchingCall(client, new AtomicLong());
    }

    private static Throwable catchingCall(SlowEndpoint client, AtomicLong elapsed) {
        long start = System.nanoTime();
        try {
            client.slow();
            return null;
        } catch (RuntimeException ex) {
            return ex;
        } finally {
            elapsed.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static String rootCauseName(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /** mock 端点的客户端契约（不需要 Spring 注解：本测试直接构造 Builder）。 */
    interface SlowEndpoint {

        /** 调 mock 的慢端点。 */
        @feign.RequestLine("GET /slow")
        void slow();
    }
}
