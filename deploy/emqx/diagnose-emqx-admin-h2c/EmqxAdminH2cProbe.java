/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * 诊断用探针（**只读 + 只发一条无人订阅的诊断主题**，不改任何配置、不碰设备主题）。
 *
 * 目的：证明平台 `EmqxRestAdminClient` 的 `POST /api/v5/publish` 在 **JDK HttpClient 默认 HTTP/2**
 * 下会因 h2c upgrade 握手失败而抛 `java.io.IOException: EOF reached while reading`
 * （平台侧表现为 `failed / EMQX_ERROR / EMQX 管理面不可达`），而改为 **HTTP/1.1** 或
 * **先用无体请求预热连接** 后即成功。
 *
 * 用法（在**平台容器**内运行；凭据从容器自身 env 读，不进 argv、不打印）：
 *   java EmqxAdminH2cProbe h2     # 默认 HTTP/2：期望 3/3 EXC EOF
 *   java EmqxAdminH2cProbe h1     # 强制 HTTP/1.1：期望 3/3 HTTP 202
 *   java EmqxAdminH2cProbe warm   # 先 GET /status 建连，再 POST：期望 3/3 HTTP 202
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class EmqxAdminH2cProbe {

    /** 无人订阅的诊断主题：EMQX 会回 202（no_matching_subscribers），不会打扰任何设备。 */
    private static final String DIAG_TOPIC = "ypbin/diagnostic/h2c-probe";
    private static final int ATTEMPTS = 3;

    private EmqxAdminH2cProbe() {
    }

    public static void main(String[] args) throws Exception {
        String base = System.getenv().getOrDefault("EMQX_PROBE_BASE_URL", "http://172.20.0.1:18093");
        String key = System.getenv("EMQX_API_KEY");
        String secret = System.getenv("EMQX_API_SECRET");
        if (key == null || secret == null) {
            System.out.println("NO_ENV：容器里没有 EMQX_API_KEY/EMQX_API_SECRET");
            return;
        }
        String auth = "Basic " + Base64.getEncoder()
            .encodeToString((key + ':' + secret).getBytes(StandardCharsets.UTF_8));
        String mode = args.length > 0 ? args[0] : "h2";

        HttpClient.Version version = "h1".equals(mode)
            ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2;
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(2000)).version(version).build();

        String body = "{\"topic\":\"" + DIAG_TOPIC + "\",\"qos\":1,\"retain\":false,"
            + "\"payload\":\"{\\\"probe\\\":true}\"}";

        System.out.println("mode=" + mode + " version=" + version);
        if ("warm".equals(mode)) {
            // 无体请求：h2c upgrade 不带 body ⇒ 连接能正常建立；随后同一连接上的 POST 就成功
            HttpRequest get = HttpRequest.newBuilder().uri(URI.create(base + "/status"))
                .timeout(Duration.ofMillis(5000)).header("Authorization", auth).GET().build();
            HttpResponse<String> g = client.send(get, HttpResponse.BodyHandlers.ofString());
            System.out.println("  warmup GET /status -> HTTP " + g.statusCode() + " ver=" + g.version());
        }
        for (int i = 0; i < ATTEMPTS; i++) {
            HttpRequest post = HttpRequest.newBuilder().uri(URI.create(base + "/api/v5/publish"))
                .timeout(Duration.ofMillis(5000))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            try {
                HttpResponse<String> r = client.send(post, HttpResponse.BodyHandlers.ofString());
                System.out.println("  #" + i + " POST /api/v5/publish -> HTTP " + r.statusCode()
                    + " ver=" + r.version() + " body=" + r.body());
            } catch (Exception ex) {
                System.out.println("  #" + i + " POST /api/v5/publish -> EXC " + ex);
            }
        }
    }
}
