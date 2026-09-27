# 平台 EMQX 管理面 HTTP/2 缺陷 · 判据与复跑

> 一句话：平台的 `EmqxRestAdminClient` 用 JDK `HttpClient` 的**默认 HTTP/2**，在**新建连接上第一个
> 请求就是带体 POST** 时，h2c upgrade 握手会被 EMQX 断开 ⇒ 发布抛
> `java.io.IOException: EOF reached while reading`，平台侧记为 `failed / EMQX_ERROR / EMQX 管理面不可达`
> （**消息其实根本没到 EMQX**）。改 HTTP/1.1 或先用无体请求预热连接即恢复正常。

## 为什么值得单独留档

这是 **2026-10-03 本轮「开放 1883 + 从外部验证全链路」时暴露出来的平台缺陷**，不是暴露面本身的问题：
上行（设备 → 公网 1883 → EMQX → 平台）全程正常；**只有平台自己调 EMQX REST 发下行**这一步会失败。
现象极具误导性——平台报的是「管理面不可达」，而实际管理面健康、同一容器里 `curl` 也完全正常，
**只有 JDK HttpClient 走 h2c 时才复现**。不单独留证据，很容易被误判成网络/隧道/暴露面的问题。

## 复跑

```bash
bash deploy/emqx/diagnose-emqx-admin-h2c/run.sh \
     --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test"
```

只读性质：不改配置、不重启；只向**无人订阅的诊断主题** `ypbin/diagnostic/h2c-probe` 发消息（EMQX 回 202），
不会打扰任何真实设备。凭据从平台容器自身的 env 读取，不进 argv、不打印、不落盘。

## 实测输出（2026-10-03，生产机 `113.142.217.58` → 平台容器 `ypbin-iot`）

```text
=== mode=h2 ===
mode=h2 version=HTTP_2
  #0 POST /api/v5/publish -> EXC java.io.IOException: EOF reached while reading
  #1 POST /api/v5/publish -> EXC java.io.IOException: EOF reached while reading
  #2 POST /api/v5/publish -> EXC java.io.IOException: EOF reached while reading

=== mode=h1 ===
mode=h1 version=HTTP_1_1
  #0 POST /api/v5/publish -> HTTP 202 ver=HTTP_1_1 body={"message":"no_matching_subscribers","reason_code":16}
  #1 POST /api/v5/publish -> HTTP 202 ver=HTTP_1_1 body={"message":"no_matching_subscribers","reason_code":16}
  #2 POST /api/v5/publish -> HTTP 202 ver=HTTP_1_1 body={"message":"no_matching_subscribers","reason_code":16}

=== mode=warm ===
mode=warm version=HTTP_2
  warmup GET /status -> HTTP 200 ver=HTTP_2
  #0 POST /api/v5/publish -> HTTP 202 ver=HTTP_2 body={"message":"no_matching_subscribers","reason_code":16}
  #1 POST /api/v5/publish -> HTTP 202 ver=HTTP_2 body={"message":"no_matching_subscribers","reason_code":16}
  #2 POST /api/v5/publish -> HTTP 202 ver=HTTP_2 body={"message":"no_matching_subscribers","reason_code":16}
```

同一现象在**平台自己的日志**里的样子（生产机 `ypbin-iot` 容器）：

```text
ERROR [ypbin-iot] CommandInstanceServiceImpl : [iot] 命令投递失败（EMQX 调用异常）：
      deviceId=9300012 requestId=cmd-... 原因码=UNREACHABLE
cn.ypbin.admin.iot.emqx.EmqxClientException: EMQX 管理面不可达：/api/v5/publish
Caused by: java.io.IOException: EOF reached while reading
Caused by: java.io.EOFException: EOF reached while reading
	at java.net.http/jdk.internal.net.http.Http2Connection$Http2TubeSubscriber.onComplete(Unknown Source)
```

以及**行为层面的 A/B**（无订阅者下，看 `errorCode` 区分「到没到 EMQX」）：

| 步骤 | 结果 | 含义 |
|---|---|---|
| 直接下发命令 | `failed / EMQX_ERROR` | 发布**没到** EMQX（h2c EOF） |
| 先签发一次设备凭据（内部先做**无体** `DELETE`）再立刻下发 | `failed / NO_SUBSCRIBER` | 发布**到了** EMQX（HTTP 202）；只是当时无订阅者 |

## 机制（已实测，未深究到的部分明确标注）

- **已确认**：JDK HttpClient `HTTP_2` 下，`POST /api/v5/publish` 作为某条**新连接上的第一个请求**时，
  EMQX/Cowboy 会在 h2c upgrade 之后断开连接（读侧 EOF）；同一客户端 `HTTP_1_1` 下 3/3 成功；
  先发**无体** `GET /status` 把 h2c 连接建立起来后，同一连接上的 `POST` 3/3 成功。
- **未定论**：EMQX/Cowboy 侧为何在 upgrade 后对"带体请求"断开（h2c upgrade 与请求体的交互属于
  已知的灰色地带）；为什么同为客户端的 `import_users`（multipart POST）此前能成功——推测与 JDK
  对两类 body publisher 的处理差异有关。**本仓不对此下确定性结论**（R1）。
- **不受影响**：`curl`（默认 HTTP/1.1，或 `--http2-prior-knowledge` 走先验知识路径）3/3 正常；
  这解释了"为什么手工测都是好的、只有平台会失败"。

## 建议的正解（**本仓未实施**，超出本轮改动范围）

在 `ypbin-service/ypbin-iot` 的 `EmqxRestAdminClient` 构造里显式指定协议版本：

```java
this.httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
    .version(HttpClient.Version.HTTP_1_1)   // ← 新增：绕开 h2c upgrade 与带体请求的交互缺陷
    .build();
```

或保留 HTTP/2 但对"新建连接上的 POST"重试一次（`-Djdk.httpclient.enableAllMethodRetry=true`
只影响 JVM 全局，不建议）。推荐前者：改动一行、语义明确、已被本判据证明有效。

⚠️ 在本修复落地前，平台的下行发布在「连接空闲后第一次发布」时会失败；本轮外部验收脚本
（`accept-emqx-external.sh`）因此在第 6 步**显式预热**了一次连接（脚本内有大段注释说明这是**绕过**），
以便把下行链路的其余部分验完。
