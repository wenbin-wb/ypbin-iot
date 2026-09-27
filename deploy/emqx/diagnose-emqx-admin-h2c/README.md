# 平台 EMQX 管理面 HTTP/2 缺陷 · 判据与复跑

> 一句话：平台的 `EmqxRestAdminClient` 用 JDK `HttpClient` 的**默认 HTTP/2**，在**新建连接上第一个
> 请求就是带体 POST** 时，h2c upgrade 握手会被 EMQX 断开 ⇒ 发布抛
> `java.io.IOException: EOF reached while reading`，平台侧记为 `failed / EMQX_ERROR / EMQX 管理面不可达`
> （**消息其实根本没到 EMQX**）。改 HTTP/1.1 或先用无体请求预热连接即恢复正常。

## 为什么值得单独留档

这是 **2026-09-27 本轮「开放 1883 + 从外部验证全链路」时暴露出来的平台缺陷**，不是暴露面本身的问题：
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

## 实测输出（2026-09-27，生产机 `113.142.217.58` → 平台容器 `ypbin-iot`）

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

## 机制（区分"我实测的"与"独立复核补测的"）

- **已确认（本仓实测）**：JDK HttpClient `HTTP_2` 下，`POST /api/v5/publish` 作为某条**新连接上的第一个
  请求**时会失败（读侧 EOF）；同一客户端 `HTTP_1_1` 下 3/3 成功；先发**无体** `GET /status` 把连接
  建立起来后，同一连接上的 `POST` 3/3 成功。
  ⚠️ **不要把它读成"HTTP/2 下必失败"**：本判据的 `warm` 模式本身就是 HTTP/2 且 3/3 成功。
  准确的表述是「**新建连接上的第一个带体请求**失败」。
- **独立复核补测的结论**（`R6-H2C-SUBAGENT.md`，**由独立子代理完成、本仓未复跑**）：它补做了"**绕开隧道**"的
  直连对照，结论是根因在 **EMQX/Cowboy 对带体 h2c upgrade 回 `101` 之后直接 RST**，平台只是触发方。
  ⇒ 更准确的归因是「**EMQX 侧 h2c upgrade 缺陷，平台以 HTTP/1.1 规避**」，而不是"平台自己的缺陷"
  （旧措辞不够准，已按复核意见收敛）。
- **`import_users` 为何"看起来正常"——已解释（推翻了本文档早先的猜测）**：平台的
  `upsertPasswordUser` 是**先 `deleteUser`（无体 DELETE）再 `import_users`**；那条无体 DELETE 等于
  **把连接预热了**，所以紧随其后的 multipart POST 才能成功。与 JDK 对两类 body publisher 的差异无关。
- **不受影响**：`curl`（默认 HTTP/1.1，或 `--http2-prior-knowledge` 走先验知识路径）3/3 正常；
  这解释了"为什么手工测都是好的、只有平台会失败"。

## 正解（**已在 PR #90 落地**；部署状态见该 PR）

在 `ypbin-service/ypbin-iot` 的 `EmqxRestAdminClient` 构造里显式指定协议版本：

```java
this.httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
    .version(HttpClient.Version.HTTP_1_1)   // ← 绕开"新建连接首个带体请求"的 h2c upgrade 缺陷
    .build();
```

**为什么不用"加重试"或"预热"**：重试只是掩盖（每次新建连接的第一次带体请求仍失败，且把一次
用户可见的失败变成不可解释的延迟）；预热只是碰巧（依赖连接存活时间，空闲后照样失败，且每次发布
前多一次往返）。固定 HTTP/1.1 与 EMQX REST 自身的语义一致，且已被本判据证明 3/3 成功。

防回归用例（`EmqxRestAdminClientTest`，两条，均经**变异**证明会咬人）：配置级断言 `version()==HTTP_1_1`；
**线上报文级**断言首个带体 POST 不带 `Upgrade: h2c` / `HTTP2-Settings` 头
（⚠️ 只看 `exchange.getProtocol()` 会**恒真假绿**——JDK 内建 `HttpServer` 只用 1.1 应答）。

⚠️ 修复**部署前**，平台的下行发布在「连接空闲后第一次发布」时会失败；本轮外部验收脚本
（`accept-emqx-external.sh`）因此在第 6 步**显式预热**了一次连接（脚本内有大段注释说明这是**绕过**），
以便把下行链路的其余部分验完。
