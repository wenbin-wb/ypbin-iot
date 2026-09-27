# 上游反哺材料（`ypbin-admin` / `ypbin-starter` / `ypbin-iot-starter`）

> **用途**：本文件是 ypbin-iot（`ypbin-admin` 的 fork）在 IoT 平台建设过程中**发现的、属于上游仓的问题**的
> **可照修清单**。每条都经过在**本机对应上游仓**里逐条复核（文件:行号 + 可复现命令/输出），
> 复核范围见文末「证据总表」；**核不到的明确标「未核实」**，不把转述当一手。
>
> 关系与版本：`ypbin-iot` = `ypbin-admin` 的 fork（只跟 `main`）；`ypbin-iot-starter` 是独立协议层；
> `ypbin-starter` 是内核（宿主按版本号跟进）。本仓当前 `ypbin-starter` = **3.5.0**，`ypbin-iot-starter` = **0.1.0**，
> Spring Boot = **4.1.1**。
>
> 命名沿用 `docs/STARTER-FEEDBACK.md`：**SF-*** 为已移交 starter 的条目（本文只做「是否已修」的回执），
> **UP-*** 为框架/部署侧条目，**A/B 组**按归属仓分组。
>
> 最后更新：2026-09-27。所有复核命令均可在本机三仓检出内直接重跑（不含任何凭据）。

---

## 0. 结论先行

| 组 | 条目 | 归属仓 | 性质 | 证据状态 |
|---|---|---|---|---|
| A | **UP-3** TCP 整帧 `byte[]` 当值 + 缺解码 SPI | `ypbin-iot-starter` | 数据正确性（静默语义丢失） | **已核实**（源码一手） |
| A | **UP-4**（新）TCP 只投递订阅地址列表**第 0 个**点位 | `ypbin-iot-starter` | 功能性缺口（数据不完整） | **已核实**（源码一手 + 本仓生产观测记录） |
| A | **UP-2** 建链超时硬编码 10s + `bind` 阻塞调用方线程 | `ypbin-iot-starter` | 可用性 | **已核实**（源码一手；生产超时形态为 2026-09-26 记录，当前已打补丁不可再现） |
| A | **SF-4**（#52）/ **SF-5**（#53）是否已修 | `ypbin-starter` | 可用性 / 安全 | **已核实：两件均仍未修**（源码一手） |
| A | **UP-5**（新）管理端点 `/actuator/metrics` 无权限码 | `ypbin-starter` | 安全（能力缺口） | **已核实**（鉴权层一手；「非管理员实测可读」**未核实**） |
| B | **UP-6**（新）`install.sh` 占位符替换是全局 `sed` | `ypbin-admin` | 凭据卫生 | **已核实**；**上游 admin 当前为「潜在」**（见条目内说明） |
| B | **UP-7**（新）`install.sh` 的 `NACOS_DIR` 写死 `ypbin-admin` | `ypbin-admin` | 部署可靠性（静默不生效） | **已核实**（本机 + 生产路径判定） |
| B | **UP-8**（新）compose 健康检查把口令放进 `exec` 参数 | `ypbin-admin` | 凭据卫生 | **已核实**（源码一手） |
| B | **UP-9**（新）`005-xxl-job.sql` 内置默认口令哈希 | `ypbin-admin` | 凭据卫生 | **已核实**（源码一手 + 哈希复算） |
| B | **UP-10**（新）「业务 404 包在 HTTP 200」易被误判 | **`ypbin-starter`** | DX / 文档陷阱（非 admin） | **已核实**（生产实测 + 源码一手） |

**最该先修的（建议优先级）**：`UP-10`（最容易反复踩、成本最低）＞ `SF-4`（登录结构性不可用）＞
`UP-6`（凭据落到配置存储）＞ `SF-5`（身份头可伪造）＞ `UP-3`/`UP-4`（数据语义/完整性）＞
`UP-2`（可用性）＞ `UP-7`（部署可靠性）＞ `UP-8`（凭据进进程参数）＞ `UP-9`（默认口令）＞ `UP-5`（能力建设）。

### 已开 issue / 评论清单（2026-09-27）

| 上游仓 | 编号 | 标题（简） | 覆盖条目 |
|---|---|---|---|
| `ypbin-admin` | [#74](https://github.com/wenbin-wb/ypbin-admin/issues/74) | `install.sh` 两处缺陷（全局 sed 写凭据进注释 + `NACOS_DIR` 写死 `ypbin-admin`） | UP-6 / UP-7 |
| `ypbin-admin` | [#75](https://github.com/wenbin-wb/ypbin-admin/issues/75) | deploy 凭据卫生（healthcheck 口令进 `exec` + xxl-job 默认口令哈希） | UP-8 / UP-9 |
| `ypbin-starter` | [#54](https://github.com/wenbin-wb/ypbin-starter/issues/54) | 管理端点缺少权限收口：`/actuator/**` 只校验登录、无权限码 | UP-5 |
| `ypbin-starter` | [#55](https://github.com/wenbin-wb/ypbin-starter/issues/55) | 「业务 404 包在 HTTP 200」缺陷阱警示与 `curl` 判据（含文档订正） | UP-10 |
| `ypbin-iot-starter` | [#14](https://github.com/wenbin-wb/ypbin-iot-starter/issues/14) | 建链超时硬编码 + `connect-timeout` 死键 + `bind` 阻塞调用方线程 | UP-2 |
| `ypbin-iot-starter` | [#15](https://github.com/wenbin-wb/ypbin-iot-starter/issues/15) | TCP 只投递订阅地址列表第 0 个点位 | UP-4 |
| `ypbin-iot-starter` | [#13 评论](https://github.com/wenbin-wb/ypbin-iot-starter/issues/13#issuecomment-5852289739) | 「仍未修」复核回执 + 交叉引用 #15 | UP-3 |
| `ypbin-starter` | [#52 评论](https://github.com/wenbin-wb/ypbin-starter/issues/52#issuecomment-5852289501) | 「仍未修」复核回执（identity 登录结构性失败） | SF-4 |
| `ypbin-starter` | [#53 评论](https://github.com/wenbin-wb/ypbin-starter/issues/53#issuecomment-5852289643) | 「仍未修」复核回执 + 默认 fail-open 实证 | SF-5 |

> 合并口径（避免刷屏）：`install.sh` 的两个缺陷合成 admin#74；两条部署凭据卫生合成 admin#75；
> TCP 的「值语义」（#13）与「点位覆盖」（#15）是**两个不同缺陷**故分开，但在两边互相交叉引用并建议共享扩展点。

---

# A 组：`ypbin-starter` / `ypbin-iot-starter`

## UP-3（高｜数据正确性）TCP 模块把整帧 `byte[]` 当值、缺宿主可插的解码 SPI

> 状态：⬜ **仍未修**。上游仓 issue：**#13**（2026-09-27 开）；2026-09-27 已在该 issue 下补「仍未修」复核评论
> （[评论链](https://github.com/wenbin-wb/ypbin-iot-starter/issues/13#issuecomment-5852289739)）。
> 姊妹项 `docs/STARTER-FEEDBACK.md` §UP-3（同一缺陷的完整版，本文件为其「复核版」）。

### 现象
用纯 TCP 透传（协议码 `tcp`）接入的设备，读数被交付为原始帧字节：落库值是字面文本
`[B@<identityHash>`（实测 `[B@2e2bd4eb`），`value_double` 恒 `null`。链路本身正常
（`quality=GOOD`、时间戳连续），属**静默的语义丢失**：采集「成功」、落库「成功」，但值无意义。

### 一手证据（本机 `ypbin-iot-starter` 检出，2026-09-27）
```bash
cd /home/wenbin/projects/ypbin/ypbin-iot-starter
git rev-list -n1 v0.1.0        # 878a49363325bf00dc17cd7f1729f5094ca18e66
grep -n "PointValue.good(address, payload" \
  ypbin-iot-protocol-tcp/src/main/java/cn/ypbin/iot/protocol/tcp/TcpSession.java
```
```
295:        PointValue value = PointValue.good(address, payload, context.clock().instant());
```
- 同行号在 **tag `v0.1.0`（`878a493`）与当前 `master`（`de296e2`）** 上一致（`git show v0.1.0:… | grep -n` ⇒ 同样 `295`）；
  `payload` 来自 **第 221 行**注册的 `Consumer<byte[]>` 帧监听器；
  类级 Javadoc **第 70-71 行**自述「{@code value} 为 {@code byte[]}」。
- **姊妹模块有、TCP 没有**：`MqttPayloadFormat`（`TEXT(0,…)` / `NUMBER(1,…)` / `BINARY(2,…)`，
  `ypbin-iot-protocol-mqtt/.../MqttPayloadFormat.java:39/42/45`，带 `isValidUtf8` 严格解码）；
  配置前缀 `ypbin.iot.protocol.mqtt`（`autoconfigure/MqttProperties.java:72`），
  `payload-format` 是其字段（`:43/:53-54`，缺省 `TEXT`）。
- **TCP 无等价配置**：`TcpProperties.java` 的 10 个字段里没有 `payloadFormat`（只有 framing/idle/workerThreads 等）。
- **core 无解码 SPI**：`ls ypbin-iot-core/src/main/java/cn/ypbin/iot/core/spi/` ⇒
  `ChangeType / ConnectionSpecProvider / DataSink / DeviceChange / DeviceEventListener / DeviceRegistry / ValidationResult`，
  无 `ValueDecoder`/`PayloadCodec` 类；`grep -rn "Decoder\|decode(" ypbin-iot-core/src/main` ⇒ **0 命中**。

### 影响
所有 TCP 透传设备取值无语义（数值/布尔/文本全部退化成 `[B@…`）；时序库只能落文本列 ⇒
曲线/聚合/阈值告警不可用；且**没有任何失败信号**，只能靠看数据的人发现。

### 期望能力（二选一，推荐 A；两条都必须满足「解码失败显式可见」）
- **A（与 MQTT 对齐）** 给 TCP 模块加 `ypbin.iot.protocol.tcp.payload-format`：
  `text`（严格 UTF-8，非法字节 ⇒ BAD + 明确消息键）/ `number`（解析失败 ⇒ BAD）/
  `binary`（原样交付 `byte[]`，为默认值以保持向后兼容）。
- **B** 在 `ypbin-iot-core` 增通用**值解码 SPI**（协议模块交付前回调宿主），可同时惠及 Modbus/OPC UA 的寄存器级解码。
- 两条都要：解码失败产出 **BAD 质量 + 明确消息键**，不得把畸形内容当 GOOD 交付。

### 验收标准
1. `payload-format=text`：合法 UTF-8 帧交付 `String`；含非法字节的帧交付 **BAD**（不得出现 U+FFFD 脏串）；
2. `payload-format=number`：`23.5` 交付数值、非数值交付 **BAD**；不配置时与 `0.1.0` 行为**逐字一致**；
3. `payload-format=binary`：原样交付 `byte[]`；
4. 三条都有单测钉住，且做**变异验证**（把严格 UTF-8 换回 `new String(bytes, UTF_8)` 时用例必须转红）；
5. 宿主（本仓 access）删除过渡解码层后，`iot.reading.value_double` 对新采集行有值。

### 我方临时处置与替换路径
本仓 access 侧过渡解码层 `cn.ypbin.admin.access.decode.ValueDecoder` / `TextFrameValueDecoder`
（按协议码 `tcp` 选解码器，按 `iot_property.data_type` 与点位映射 `raw_address`（帧键）产出规范值；
失败 ⇒ `iot.access.decode.failure{reason=…}` + WARN + 丢弃）。
**替换动作见 `docs/VALUE-DECODE-DESIGN.md` §5**；框架能力就位后该层整体删除，`raw_address` 作为帧键的约定保留。

---

## UP-4（高｜数据正确性）TCP 适配器只投递「订阅地址列表第 0 个点位」，其余点位静默无数据

> 状态：⬜ **仍未修**。上游仓 issue：**#15**（2026-09-27 开，
> [链接](https://github.com/wenbin-wb/ypbin-iot-starter/issues/15)）。
> 本条与 UP-3 是**同一模块的两个不同缺陷**（UP-3 = 值的语义，UP-4 = 点位的覆盖），故分开登记、互相交叉引用。

### 现象
一个 TCP 设备订阅 **N 个点位**（`SubscribeRequest.addresses()` 有 N 项）时，**只有第 0 个点位**会收到数据；
其余 N-1 个点位**永远没有新行**，且**不报错、不告警**。

### 一手证据（本机 `ypbin-iot-starter` 检出，2026-09-27）
```bash
cd /home/wenbin/projects/ypbin/ypbin-iot-starter
sed -n '212,227p' \
  ypbin-iot-protocol-tcp/src/main/java/cn/ypbin/iot/protocol/tcp/TcpSession.java
```
```java
213  public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
...
219      PointAddress streamAddress = request.addresses().get(0);
220      TcpSubscription subscription = new TcpSubscription(subscriptionId, request.addresses(), listener);
221      Consumer<byte[]> frameConsumer = payload -> dispatch(subscription, streamAddress, payload);
222      subscription.attach(frameConsumer);
223      connection.addFrameListener(frameConsumer);
```
- 派发时地址**恒为 `streamAddress`**（=`addresses().get(0)`，第 219 行）⇒ 第 221 行的闭包把**所有帧**都算在
  第 0 个点位上；而 `TcpSubscription.addresses()`（第 361-363 行）却返回**全部**地址 ⇒ 宿主看到的订阅句柄是
  「N 个点位」，实际数据只有 1 个 ⇒ **接口语义与实际行为不一致**。
- 类级 Javadoc 第 70-71 行亦自述地址「即调用方给出的订阅地址**之一**」——即**该行为是已知且刻意的窄口径**，
  但它没有替代入口：宿主没有任何办法在一帧里寻址到第 2..N 个点位（除非自己拆帧，而帧键又不在框架侧约定）。
- **行号在 tag `v0.1.0`（`878a493`）与 `master`（`de296e2`）上一致**（`git show v0.1.0:… | grep -n` ⇒ `219/221/295` 同号）。
- **（自我订正，2026-09-27）** 曾怀疑 `addresses().get(0)` 对空列表会抛 `IndexOutOfBoundsException`；
  复核后**该担心不成立**：`SubscribeRequest` 的紧凑构造器已保证非空
  （`SubscribeRequest.java:48-53`：`Objects.requireNonNull` + `if (addresses.isEmpty()) throw new IllegalArgumentException("addresses must not be empty")`）
  ⇒ 经合法 `SubscribeRequest` 进到 `subscribe()` 的列表**必非空**。此处不需要额外判空，**本条不构成缺陷**。

### 影响
- 同一 TCP 设备的多点位采集**静默少采**：本仓生产演示设备 `9300012` 订阅 **4 个点位**
  （`temperature/humidity/serialNo/demoBoundary`，access 日志 `订阅成功：deviceId=9300012 点位数=4`），
  但只有第 0 个 `temperature` 有新数据；另 3 个点位在 Redis 里**始终停在种子值**（`docs/DEMO-DATA.md:23`，复核 2026-09-27）。
- **没有任何失败信号**：其余点位不走「未映射地址」告警路径（独立复核 grep 该 WARN = 0 条，见 `docs/ACCESS-ENABLE.md` §3.1）
  ⇒ 运维无法从日志发现漏采，只能靠人比对数据。
- 该限制**不是** UP-3 的解码层能修的——解码解决「值是什么」，不解决「这一帧属于哪个点位」。

### 期望能力
二者之一（推荐 A）：
- **A 一帧可寻址多点位**：在 `SubscribeRequest`/`PointValue` 上引入 **`valueSelector`（帧键/字段路径）**语义，
  由宿主在点位声明的 `raw_address` 里写「取帧里的哪个键」，协议模块按此把一帧派发到**对应点位**（0..N-1）；
  这个「帧键」约定恰好也是 UP-3 期望能力 A 里「一帧多字段」所需要的。
- **B 明示单点位契约**：若框架有意保持「TCP = 单点位流」，则至少
  ① 在 `subscribe()` 对 `addresses().size() > 1` **fail-fast**（异常而非静默丢弃），
  ② 文档/`ProtocolDescriptor` 里显式声明该能力边界，让宿主无法在不知情下少采。
- 无论哪条：不得在「订阅了 N 个点位」的同时**静默只交付 1 个**。

### 验收标准
1. 订阅 3 个点位时，模拟器发 3 种不同帧内容，断言**三个点位各自产生新值**（A 方案）；
2. 若采用 B 方案：`addresses().size() > 1` 时 `subscribe()` **异常完成**，且异常消息可读、有单测钉住；
3. （**已移除**）原第 3 条「`addresses()` 为空时给出显式异常」经复核**不适用**：`SubscribeRequest.java:48-53`
   已在构造期拒绝空列表 ⇒ 无此缺口，不应作为验收项；
4. 宿主（本仓 access）在框架支持后，演示设备 4 个点位**全部**有新数据（端到端）。

### 我方临时处置与替换路径
本仓 access **未做多点位 workaround**（不做「猜帧」这类静默兜底）；演示数据只声明 1 个有效点位（第 0 个），
其余 3 个点位保持种子值并在文档里显式登记为**已知限制**：
`docs/ACCESS-ENABLE.md` §3.1、`docs/DEMO-DATA.md:23`、`docs/VALUE-DECODE-DESIGN.md` §3.1。
框架支持后删除这些「已知限制」说明并恢复 4 点位采集。

---

## UP-2（高｜可用性）建链超时硬编码 10s（且配置项是死键），`IotLifecycle.bind` 阻塞调用方线程

> 状态：⬜ **仍未修**。上游仓 issue：**#14**（2026-09-27 开，
> [链接](https://github.com/wenbin-wb/ypbin-iot-starter/issues/14)）。
> 本条目在 `docs/STARTER-FEEDBACK.md` §UP-2 已有草稿；**本文件以 `ypbin-iot-starter` 源码为准重新复核**，
> 并对草稿里「框架既没有可配的默认建链超时（无 `@ConfigurationProperties` 绑定）」一句做了**订正**（见下）。

### 现象
生产启用 `ypbin-access`（单租户 12 台设备，其中多台 endpoint 不可达）后，采集出现
「本地租约已过期（未成功续约）→ 自行停采」的周期性自 fencing；根因是**一趟批量绑定把续约线程占满**。

### 一手证据（本机 `ypbin-iot-starter` 检出，2026-09-27）

**(1) `ConnectionSpec` 的默认建链超时是硬编码常量**
```bash
cd /home/wenbin/projects/ypbin/ypbin-iot-starter
grep -n "DEFAULT_CONNECT_TIMEOUT\|connectTimeout = connectTimeout" \
  ypbin-iot-core/src/main/java/cn/ypbin/iot/core/model/ConnectionSpec.java
```
```
51:    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
63:        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
78:        return new ConnectionSpec(connectionId, protocol, endpoint, DEFAULT_CONNECT_TIMEOUT,
```
⇒ 宿主若用 `ConnectionSpec.of(id, protocol, endpoint)`，建链超时**只能是 10s**（`NettyTransport:161` 用的是 `spec.connectTimeout()`）。

**(2) 确实存在一个协议级配置项，但它是「死键」——没有任何生产代码消费它**
```bash
grep -rn "settings()\.connectTimeout\|settings\.connectTimeout" --include=*.java ypbin-iot-*/src/main
# ⇒ 0 命中（对比：settings().keepAliveInterval / settings().requestTimeout 均有消费者，见
#    MqttAdapter:191、ModbusConnection:90、MqttSession:219、OpcUaSession:122）
grep -rn "\.connectTimeout()" --include=*.java ypbin-iot-*/src/main | grep -v '/target/'
```
```
ypbin-iot-protocol-modbus/.../ModbusAdapter.java:210:            builder.connectTimeout = spec.connectTimeout();
ypbin-iot-protocol-mqtt/.../MqttAdapter.java:195:   .orTimeout(Math.max(1L, spec.connectTimeout().toMillis()), …)
ypbin-iot-protocol-opcua/.../OpcUaAdapter.java:205/240/279:            … spec.connectTimeout() …
ypbin-iot-spring-boot-starter/.../IotAutoConfiguration.java:200:  protocolProperties.connectTimeout(),   ← 仅用于「构造 AdapterSettings」
ypbin-iot-transport/.../NettyTransport.java:161:  long millis = spec.connectTimeout().toMillis();
```
⇒ 配置 `ypbin.iot.protocol.<code>.connect-timeout` 会**绑定进** `AdapterSettings`（`IotProperties.java:319-341`、
`DefaultAdapterSettings.java:55/82`；派生测试 `IotAutoConfigurationTest.java:136-144` 断言 7s 生效），
但**建链路径读的是 `ConnectionSpec.connectTimeout`**（即宿主传入值，缺省 10s）⇒
**改了配置也不会改变实际建链超时**。这是「看起来可配、实际不生效」的假缝（比「完全不可配」更危险）。
> **对 `STARTER-FEEDBACK.md` §UP-2 的订正**：草稿写「无 `@ConfigurationProperties` 绑定」并不准确；
> 准确表述是「有绑定、无消费」——`ypbin.iot.protocol.*.connect-timeout` 是死键，有效默认值仍是硬编码的 10s。

**(3) `bind` 全程 `.join()`，阻塞调用方线程**
```bash
sed -n '210,224p' \
  ypbin-iot-spring-boot-starter/src/main/java/cn/ypbin/iot/spring/autoconfigure/IotLifecycle.java
```
```java
214      ConnectionRegistry.ConnectionHandle handle = connectionRegistry
215              .acquire(adapter, specOptional.get(), context)
216              .toCompletableFuture()
217              .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
218              .join();
...
221      session = adapter.bind(handle.connection(), effective, context)
222              .toCompletableFuture()
223              .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
224              .join();
```
且 `BIND_TIMEOUT` **同样是硬编码 10s**：`grep -n "BIND_TIMEOUT =" IotLifecycle.java` ⇒
`73: private static final Duration BIND_TIMEOUT = Duration.ofSeconds(10);`（第 201/217/223/236/311 行共用）。
⇒ 单台不可达设备最坏占调用方线程 10s；N 台顺序绑定 = N×10s，**宿主无法通过任何配置把一趟绑定压进租约 TTL**。

**(4) 生产侧的一手旁证（2026-09-27 只读复采，本机 SSH 到生产实例）**
```bash
docker logs ypbin-access --since 30m 2>&1 | grep "failed to bind device" \
  | grep -oE "^[0-9-]+T[0-9]{2}:[0-9]{2}" | sort | uniq -c | tail -8
```
```
      9 2026-09-27T11:16
      9 2026-09-27T11:18
      9 2026-09-27T11:20
      9 2026-09-27T11:22
```
```
ERROR 7 --- [ypbin-access] [   scheduling-1] c.y.i.spring.autoconfigure.IotLifecycle : [ypbin-iot] failed to bind device 9300010
```
⇒ 失败日志的线程名是 **`scheduling-1`**（续约调度线程自己）——这正是「**bind 占用调用方线程**」的直接现场
（`ConnectionRegistry`/`LeaseRenewScheduler` 与该线程同源）。30 分钟内 135 条失败、每 2 分钟 9 台一轮。
> **证据强度声明（不夸大）**：`ConnectTimeoutException: connection timed out after 10000 ms` 的**原始超时形态**
> 来自本仓 2026-09-26 的生产记录（`docs/STARTER-FEEDBACK.md` §UP-2 证据段）；
> **当前生产已把不可达 endpoint 改成快速失败，今天的日志是 `Connection refused`（540 条）、无法再现 10s 超时**，
> 故「10s 硬编码」这一条以**源码一手**为准，「生产被拖到租约饿死」以**本仓 2026-09-26 记录 + 今日线程/节奏旁证**为准。

### 影响
- 任何「租户下存在少量不可达/慢设备」的真实部署都会**租约抖动 → 周期性停采**，且现象与「设备离线」混淆；
- 「配置了 `connect-timeout` 却不生效」会让运维在错误的方向上排查（**假缝比缺能力更贵**）。

### 期望能力
1. **打通配置到实际建链**：`ypbin.iot.protocol.<code>.connect-timeout` 必须真正作用于建链
   （例如在 `resolveSpec`/`ConnectionRegistry.acquire` 前，用 `AdapterSettings.connectTimeout` 填补
   `ConnectionSpec.connectTimeout` 为空的场景），并保留 `ConnectionSpec` 的单设备覆盖优先级；
2. 提供**不阻塞调用方线程**的批量绑定 API（返回 future / 框架自己的线程池推进），
   或至少给出「批量绑定带整体超时」的语义；
3. 补一条可观测：租约「客户端自认在租约内 / 服务端已判失效」的偏差计数，避免只能事后扫描发现。

### 验收标准
- `ypbin.iot.protocol.tcp.connect-timeout=1s` 时，对不可达地址的建链在 **≈1s** 失败
  （`ConnectTimeoutException` 的 `after X ms` 随之变化）——**这一条用今天的代码必然失败**，正是死键的判据；
- 租户挂 N 台不可达设备时，`renew` 仍在 TTL 内完成（集成测试：假 endpoint + 断言续约线程不被建链占用）；
- 批量绑定 API 不占用调用方线程（线程名断言：续约线程 != 建链线程）。

### 我方临时处置与替换路径
本仓当前把不可达演示设备的 endpoint 改成 `tcp://127.0.0.1:9`（**快速失败**）绕开（回滚物在 `deploy/`），
并另加 systemd 看门狗；access 侧的接线缺口（`HttpDeviceSpecSource.findConnection` 固定传 `null`）
登记在 `docs/IOT-ROADMAP.md`。**这些是给演示数据打的补丁，不是修根因**；框架修复后应删除补丁、恢复真实 endpoint。

---

## SF-4 / SF-5 回执：`ypbin-starter` **两件均仍未修**（2026-09-27 复核）

> 上游 issue：**#52**（SF-4，2026-09-25 开，**0 评论、open**）、**#53**（SF-5，2026-09-25 开，open，含我们 1 条生产实证评论）。
> **按纪律不重复开 issue**，改在两条下面补「仍未修」的复核评论（2026-09-27 已补：
> [#52 评论](https://github.com/wenbin-wb/ypbin-starter/issues/52#issuecomment-5852289501)、
> [#53 评论](https://github.com/wenbin-wb/ypbin-starter/issues/53#issuecomment-5852289643)）。

### SF-4（高｜可用性）identity 模式下 auth 登录结构性失败

**复核命令与输出**（本机 `ypbin-starter` 检出，`master` = `f6e841e`，`revision` = `3.5.1-SNAPSHOT`）：
```bash
cd /home/wenbin/projects/ypbin/ypbin-starter
sed -n '57,63p;89,96p;104,111p' \
  ypbin-starter-security/src/main/java/cn/ypbin/starter/security/identity/IdentityStpLogic.java
```
```
57  /**
58   * 无身份头时返回的 token 值。
60   * <p>Sa-Token 以「token 为空」判定未登录…因此这里用空串而不是
61   * {@code null} 表达「无身份」…</p>
63  private static final String NO_IDENTITY_TOKEN = "";
...
90  public String getLoginIdNotHandle(String tokenValue) {
94      String token = currentToken();
95      return token.equals(tokenValue) ? token : NO_IDENTITY_TOKEN;
...
109  private static String currentToken() {
110      return IdentityContext.getUserId().map(String::valueOf).orElse(NO_IDENTITY_TOKEN);
```
⇒ 与 `docs/STARTER-FEEDBACK.md` §SF-4 环 1-4 的机制链**逐字一致**：
sa-token 的「候选 token 可用」判据要求 `getLoginIdNotHandle(...) == null`，
而本实现**任何输入都不可能返回 `null`**（只可能是空串或身份值）⇒ 12 次重试必然全败 ⇒ 建 token 恒抛异常。
**结论：仍未修。** 影响：`ypbin.security.identity.enabled=true` 的服务登录（含短信/社交）**结构性不可用**。
**我方临时处置**：本仓 `ypbin-auth` 保持 `identity.enabled=false`（见 `docs/NACOS-AUTH.md`），等待 starter 修复。

### SF-5（高｜安全）下游 `IdentityHeaderFilter` 不校验网关签名

**复核命令与输出**（同上检出）：
```bash
grep -n "Signed\|signature\|trustedSource" \
  ypbin-starter-security/src/main/java/cn/ypbin/starter/security/identity/IdentityHeaderFilter.java
# ⇒ 0 命中（该文件 118 行全文只在 :60-91 读取 X-User-Id/X-User-Name/X-Tenant-Id/X-Dept-Id/X-Roles 并直接建身份）
grep -n "USER_ID\|USER_NAME\|TENANT_ID\|DEPT_ID\|ROLES" \
  ypbin-starter-security/src/main/java/cn/ypbin/starter/security/identity/IdentityHeaders.java
# ⇒ 30-34 行：五个身份头常量，无签名头常量
```
**签名机制确实存在，但只覆盖 Feign 出站透传，不覆盖「建立身份」这一步**：
```bash
grep -n "isIdentitySourceTrusted" -A 6 \
  ypbin-starter-cloud-core/src/main/java/cn/ypbin/starter/cloud/feign/FeignHeaderInterceptor.java
```
```
128  private boolean isIdentitySourceTrusted(HttpServletRequest request) {
129      if (!hasText(trustedSourceToken) || !hasText(trustedSourceHeader)) {
130          return true;          ← 未配置即 fail-open
131      }
132      String actual = request.getHeader(trustedSourceHeader);
133      return hasText(actual) && trustedSourceToken.equals(actual.trim());
```
- 网关侧只在**同时配置了** `trusted-source-token`（`GatewayProperties.java:223`，默认 **空串**）与头名
  （`:226`，默认 `X-Gateway-Signed`）时才写标记（`GatewayAuthGlobalFilter.java:131-133`）；
- `IdentityHeaderFilter`（servlet 侧）**完全不看**该标记 ⇒ 直连下游端口 + 构造 `X-User-Id`/`X-Roles` 即可建立身份；
- 且 Feign 侧校验**默认 fail-open**（`trustedSourceToken` 空 ⇒ `isIdentitySourceTrusted` 恒 `true`）——
  与 #53 里我们提出的「不允许『配了 token 才校验』的 fail-open」一致。

**结论：仍未修。** 生产实证已在 #53 的评论里（2026-09-25，只读 GET，直连 `127.0.0.1:18084` 构造身份头成功）。
**我方临时处置/边界**：`18084` 已收窄为**仅回环**（`deploy/docker-compose.yml:409-415` 用独立的
`IOT_BIND_ADDR`），把现网可利用面限制为「同宿主进程 / 同 compose 网络 / 能打 `127.0.0.1` 的 SSRF」。

---

## UP-5（中｜安全）网关与下游对 `/actuator/**` **只做登录校验、无权限码**，任何已登录用户可读平台级指标

> 状态：⬜ **未修**。上游 issue：**#54**（2026-09-27 开，
> [链接](https://github.com/wenbin-wb/ypbin-starter/issues/54)）。归属：**`ypbin-starter`**（能力缺口）；
> 现象在本仓（`ypbin-iot`）部署形态下实测。本仓的配置已把「直接暴露」这一面收窄，但「已登录即可读」这一面**没有能力去收**。

### 现象
平台的运行指标（`iot.ingest.*`、`iot.access.egress.*`、`iot.timeseries.*` 等计数）通过
`/iot/actuator/metrics/**` 暴露；**网关只校验「有没有登录」，没有任何权限码** ⇒
任何**已登录**账号（不限角色）都能读到平台级指标。下游服务侧，`/actuator/**` 还被排除在 Sa-Token 拦截之外。

### 一手证据（2026-09-27）
```bash
# ① 网关的鉴权过滤器只有「认证」，没有「授权/权限码」
cd /home/wenbin/projects/ypbin/ypbin-starter
sed -n '76,84p' \
  ypbin-starter-cloud-gateway/src/main/java/cn/ypbin/starter/gateway/filter/GatewayAuthGlobalFilter.java
```
```java
77  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
78      String path = exchange.getRequest().getPath().pathWithinApplication().value();
79      if (excludePaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path))) {
80          return filterExcludedPath(exchange, chain);
81      }
82      return authProvider.authenticate(exchange)          ← 只有 authenticate
83          .flatMap(result -> handleAuthResult(exchange, chain, result));
```
```bash
# ② 网关白名单没有 /actuator/**（⇒ 未登录会被拒）
cd /home/wenbin/projects/ypbin/ypbin-iot
sed -n '57,82p' deploy/nacos/ypbin-gateway.yaml | grep -n "actuator"     # ⇒ 0 命中
# ③ 网关把 /iot/** 剥前缀转发给 ypbin-iot（⇒ /iot/actuator/metrics 可达该服务的 actuator）
grep -n "Path=/iot/\*\*" -A 4 deploy/nacos/ypbin-gateway.yaml
#   49  - id: iot / 50 uri: lb://ypbin-iot / 52 Path=/iot/** / 54 StripPrefix=1
# ④ 下游服务把 /actuator/** 排除在 Sa-Token 拦截之外（服务侧无认证）
grep -n "excludes" -A 6 deploy/nacos/ypbin-system.yaml
#   90      excludes: / 91 /open/license/** / 92 /open-api/** / 93 /internal/** / 94 /actuator/**
# ⑤ 生产实测（只读）：未登录经网关 → 业务 401；即「登录是唯一门槛」
curl -s -m 8 -w "\nhttp=%{http_code}\n" http://127.0.0.1:18080/iot/actuator/metrics
#   {"code":401,"data":null,"message":"未提供登录凭证","success":false,"timestamp":"2026-09-27 11:22:22"}
#   http=200
# ⑥ 生产实测（只读）：**直连服务端口、完全不带任何认证头**也能拿到指标 ⇒ 服务侧对该端点无任何认证
curl -s -m 8 -o /tmp/m.json -w "http=%{http_code}\n" http://127.0.0.1:18084/actuator/metrics; rm -f /tmp/m.json
#   http=200
#   {"names":["application.ready.time","application.started.time","disk.free","disk.total",
#             "executor.active","executor.completed","executor.pool.core",…]}
```
**证据强度声明（不夸大）**：以上证明的是**鉴权层事实**（网关仅认证、无权限码；服务侧 `18084` 直连**零认证**、路由可达）。
「用一个**非管理员**的真实账号**经网关**实测能读到指标」这一步**未核实**——本次未使用任何账号口令，
按凭据纪律不应为验证而索要口令。**结论强度**：能力缺口成立；单次越权访问的实证留待有账号的会话补做。
> 注意 ⑥ 的另一层含义：`18084` 的**唯一**现有边界是「只绑回环」（见下方「我方临时处置」）——
> 这条边界一旦被改宽（例如为图方便设 `IOT_BIND_ADDR=0.0.0.0`），平台指标即**完全无认证**地暴露。
> 这正是「starter 应提供权限收口能力」的直接理由。

### 影响
平台级运行指标（摄入量、丢弃计数、出口错误、时序写入行数）对**任何已登录用户**可见，
可被用来推断平台规模、故障时点与多租户负载分布；也会让「管理端点」在权限模型里成为一个**无主面**。

### 期望能力
starter 提供**管理端点权限收口**能力，建议形态：
- 网关侧支持「路径 → 权限码」声明（例如 `ypbin.gateway.auth.endpoint-permissions` 映射到 `@SaCheckPermission`
  同款权限码），对 `/actuator/**`（除 `health`/`info`）默认要求**管理权限**；
- 或下游侧提供 `ManagementEndpointGuard`（复用 `PermissionProvider`），在 servlet 层对
  `management.endpoints.web.base-path` 下的端点做权限校验；
- 无论哪种，**默认 fail-closed**：无法判定权限时拒绝，而不是放行。

### 验收标准
1. 仅登录、**无管理权限**的账号访问 `/actuator/metrics/**` ⇒ 被拒（业务码 403），有测试；
2. 有管理权限的账号 ⇒ 放行（正向用例）；
3. `health`/`info` 仍放行（可用性不能被误伤）——正向 + 负向各一例；
4. 本仓把 `/iot/actuator/**` 纳入该机制后，端到端验证「登录可读」变成「有权限才可读」。

### 我方临时处置与替换路径
本仓把 `18084` 的绑定地址与 `INTERNAL_BIND_ADDR` **刻意分开**、默认仅回环（`deploy/docker-compose.yml:409-415`），
并在 `deploy/nacos/ypbin-iot.yaml:113-143` 写明安全边界（只开 `health,metrics,info`，`health` 关掉 details/components）。
**这是边界收窄，不是权限收口**：机制就位后应把网关白名单/权限码接上，并删除「靠回环兜底」的注释口径。

---

# B 组：`ypbin-admin`（含归属 `ypbin-starter` 的一条）

## UP-6（中｜凭据卫生）`deploy/install.sh` 的占位符替换是**全局 `sed`**，会把**注释里**的占位符替换成真实凭据

> 状态：上游 **未修**。上游 issue：**#74**（2026-09-27 开，[链接](https://github.com/wenbin-wb/ypbin-admin/issues/74)）。
> **重要订正（R4）**：在**上游 `ypbin-admin` 当前 `main`** 里，`deploy/nacos/*.yaml`
> **目前没有任何注释行含 `${...}` 占位符**（已 grep 复核）⇒ 这是**潜在**缺陷，不是已发生的事实；
> 它在我们 fork 的 `ypbin-iot.yaml` 上**已经真实发生过**（注释里写了 `${GATEWAY_SIGN_TOKEN}`，
> 被替换成真实值并 POST 进 Nacos 活配置）⇒ 上游采用同一修法可**预防**同类事故。

### 现象
`install.sh` 渲染 Nacos 配置时对**整份文件**做 `sed -e "s/\${KEY}/${KEY}/g"`；`sed` 不区分注释，
因此**注释里的占位符也会被替换成真实口令/签名标记** ⇒ 凭据进入 Nacos 配置存储的注释文本里
（对运行毫无作用，却扩大了凭据暴露面）。

### 一手证据（2026-09-27，本机 `ypbin-admin` 检出，`main` = `91a801e`）
```bash
cd /home/wenbin/projects/ypbin/ypbin-admin
sed -n '1275,1299p' deploy/install.sh
```
```sh
1279  for cfg in ypbin-common ypbin-gateway ypbin-auth ypbin-system ypbin-ai; do
1280      if [ -f "$NACOS_DIR/$cfg.yaml" ]; then
1286          sed -e "s/\${MYSQL_ROOT_PASSWORD}/${MYSQL_ROOT_PASSWORD}/g" \
1287              -e "s/\${REDIS_PASSWORD}/${REDIS_PASSWORD}/g" \
1288              -e "s/\${INTERNAL_TOKEN}/${INTERNAL_TOKEN}/g" \
1289              -e "s/\${GATEWAY_SIGN_TOKEN}/${GATEWAY_SIGN_TOKEN}/g" \
1290              "$NACOS_DIR/$cfg.yaml" > "$TMP_CFG"
```
```bash
# 上游 main 当前的占位符分布：全部在「配置行」，注释行 0 命中（⇒ 现状是潜在风险）
grep -rn "^\s*#.*\${" deploy/nacos/                 # ⇒ 无输出
grep -rn '\${[A-Z_]*}' deploy/nacos/ | wc -l          # ⇒ 6（common:3 / gateway:1 / system:1 / ai:1，跨 4 个文件）
```
**我们 fork 里的真实事故（同一段脚本）**：`ypbin-iot/deploy/nacos/ypbin-iot.yaml:88-90` 在**注释**里写了
`${IOTDB_PASSWORD}` / `${GATEWAY_SIGN_TOKEN}` ⇒ 全局 sed 会把真实网关签名标记写进 Nacos 活配置的注释里。
物证（本仓记录）：`docs/DEPLOY-BACKEND.md:233-240` 的受管清单
`/opt/ypbin/nacos-ypbin-iot.yaml.bak-20260925-013857` 命中 `gateway ×2（值行 + 注释行）`。

### 影响
真实凭据进入**配置存储的注释**（Nacos 活配置 + 任何 dump/备份），无功能收益、只有暴露面；
一旦有人把 Nacos 配置导出/贴图/提工单，凭据即随注释扩散。

### 期望能力
`sed` 表达式加**非注释行限定**：`/^[[:space:]]*#/! s/…/…/g`；并给一个可复现判据。

### 验收标准
1. 用一份**含注释占位符**的临时 Nacos yaml 跑渲染，断言：配置行已替换、**注释行保持 `${...}` 原样**；
2. 渲染产物中**不含**真实凭据出现在任何以 `#` 开头的行（可用 `grep -nE '^[[:space:]]*#.*<真实值>'` 反证）；
3. 上游仓库自身 `deploy/nacos/*.yaml` 可安全加入注释说明而不泄露。

**参考修法（本 fork 已用，`ypbin-iot/deploy/install.sh:1357-1378`）**：
```sh
# ⚠️ 只替换**非注释行**（`/^[[:space:]]*#/!s/.../`，2026-09-26 修）：注释里的占位符只是文档写法
sed -e "/^[[:space:]]*#/! s/\${MYSQL_ROOT_PASSWORD}/${MYSQL_ROOT_PASSWORD}/g" \
    -e "/^[[:space:]]*#/! s/\${REDIS_PASSWORD}/${REDIS_PASSWORD}/g" \
    -e "/^[[:space:]]*#/! s/\${INTERNAL_TOKEN}/${INTERNAL_TOKEN}/g" \
    -e "/^[[:space:]]*#/! s/\${GATEWAY_SIGN_TOKEN}/${GATEWAY_SIGN_TOKEN}/g" \
    "$NACOS_DIR/$cfg.yaml" > "$TMP_CFG"
```
（同时建议一并采用本 fork 的另外两点：渲染产物用 `mktemp`（600）+ 用后即删 + `EXIT` trap 兜底，
避免含真实口令的文件以 644 留在 `/tmp`。）

### 我方临时处置与替换路径
本 fork 已按上述修法改完（`ypbin-iot/deploy/install.sh:1357-1378`），并在
`docs/DEPLOY-BACKEND.md` §5.5/§5.6 登记了残留清理（22 个 `/tmp` 渲染残留已清、两枚 token 已轮换）。
上游采用后，本 fork 可在下次同步时删除本地差异
（**订正**：`deploy/install.sh` **在** Sync 白名单内——见 `.github/workflows/sync-whitelist.yml` 的 `ALLOWED` 正则，
故本 fork 允许改它；此前草稿误写「不在白名单」，以工作流为准）。

---

## UP-7（中｜部署可靠性）`install.sh` 的 `NACOS_DIR` 写死 `$ROOT/ypbin-admin/deploy/nacos`：fork 下**静默不导入自己的配置**

> 状态：上游 **未修**。与 UP-6 同属 `deploy/install.sh`，**合并提在同一个 issue**：
> **#74**（[链接](https://github.com/wenbin-wb/ypbin-admin/issues/74)）；本文件仍分两条写清证据与验收。

### 现象
`install.sh` 假定「本仓被检出为名为 `ypbin-admin` 的目录」：既用该名字做克隆目录
（`ypbin-admin.git` → `$ROOT/ypbin-admin`），又把它拼进 `NACOS_DIR`。
fork（检出名 `ypbin-iot`）下该路径不存在 ⇒ `[ -f … ]` 恒假 ⇒ **导入循环静默什么都不做**（没有 else 分支、没有 warn）。

### 一手证据（2026-09-27）

**上游源码**（本机 `ypbin-admin` 检出）：
```bash
cd /home/wenbin/projects/ypbin/ypbin-admin
grep -n "NACOS_DIR=" deploy/install.sh                      # 1278: NACOS_DIR="$ROOT/ypbin-admin/deploy/nacos"
grep -n 'git clone -b "\$BRANCH" "\$REPO_BASE/ypbin-admin.git"' deploy/install.sh   # 913
grep -n 'pull_repo "\$ROOT/ypbin-admin"' deploy/install.sh  # 963
sed -n '1278,1280p' deploy/install.sh                       # 1278 NACOS_DIR=… / 1279 for cfg … / 1280 if [ -f … ]
```
⇒ `1278` 的路径 + `1280` 的 `if [ -f … ]`（**无 else**）= 静默跳过。

**生产路径判定**（只读 SSH，2026-09-27；两个候选 ROOT 都判一遍）：
```bash
for R in /opt/ypbin/ypbin-iot /opt/ypbin/main; do
  echo "ROOT=$R"; [ -d "$R/ypbin-admin/deploy/nacos" ] && echo yes || echo NO
  for c in ypbin-iot ypbin-access ypbin-common; do
    [ -f "$R/ypbin-admin/deploy/nacos/$c.yaml" ] && echo "  $c yes" || echo "  $c NO"
  done
done
```
```
ROOT=/opt/ypbin/ypbin-iot          ROOT=/opt/ypbin/main
  dir exists: NO                     dir exists: yes
  ypbin-iot.yaml file: NO            ypbin-iot.yaml file: NO
  ypbin-access.yaml file: NO         ypbin-access.yaml file: NO
  ypbin-common.yaml file: NO         ypbin-common.yaml file: yes
```
⇒ **两种 ROOT 下，fork 自己的 `ypbin-iot.yaml` / `ypbin-access.yaml` 都不在 `$NACOS_DIR` 里**：
- 若 ROOT 指向 fork 检出 ⇒ 目录不存在 ⇒ 循环整体空转（**静默**）；
- 若 ROOT=/opt/ypbin/main（脚本默认）⇒ 命中的是**同名上游 admin 仓**的 **5 份旧模板**
  （`ypbin-common/gateway/auth/system/ai`）⇒ **IoT 的两份配置永远不会被导入**。
本 fork 的 `install.sh` 已经把循环列表扩成 7 份（`ypbin-iot/deploy/install.sh:1351`），但 `NACOS_DIR` **未改**
（`ypbin-iot/deploy/install.sh:1344` 仍是 `$ROOT/ypbin-admin/deploy/nacos`）⇒ 多出来的两份**正好被静默跳过**。

### 影响
- 「重跑 `install.sh` 就能让配置生效」是**假承诺**：在 fork 上重跑，要么不导入，要么导入**旧上游模板**
  （还可能整份覆盖 live 配置、抹掉 live-only 键——本仓已实测到该 drift，见 `docs/DEPLOY-BACKEND.md` §5.6.1）；
- 排查成本高：脚本**成功退出**且打印正常，没有任何失败信号。

### 期望能力
1. `NACOS_DIR` **按脚本自身位置解析**（例如 `SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`，
   取 `$SCRIPT_DIR/nacos`），或至少与克隆目录名解耦（`REPO_DIR_NAME` 可配）；
2. `[ -f … ]` 的假分支**不再静默**：`warn` 出「未找到 $cfg.yaml（期望路径 …），已跳过」；
3. 可选：导入列表由 `deploy/nacos/*.yaml` **自动枚举**，避免「清单漏一项就静默少导入」。

### 验收标准
1. 在检出名为 `ypbin-iot` 的目录里（`NACOS_DIR` 按脚本位置解析）跑 `install.sh`，
   **7 份配置全部被 POST**（日志逐份 `已导入`），且不含上游旧模板；
2. 故意删掉一份模板 ⇒ 输出明确的 `warn`（含期望路径），**不是静默跳过**；
3. ROOT 与检出目录名解耦：`YPBIN_ROOT=/opt/ypbin/ypbin-iot` 时仍能找到本仓模板。

### 我方临时处置与替换路径
本仓**不依赖 `install.sh` 做配置变更**，改用「Nacos live dump → diff → 显式渲染 → POST」的受控流程
（`docs/DEPLOY-BACKEND.md` §5.6.1 条 2 / §5.6.6，含 700 工作目录 + 600 凭据文件 + 回滚）。
上游修好后，本 fork 可把「不依赖 install.sh」的说明降级为「二选一」。

---

## UP-8（中｜凭据卫生）compose 健康检查把口令放进 `exec` 参数（`docker events` / 进程 args 可见）

> 状态：上游 **未修**。与 UP-9 同属「部署凭据卫生」，**合并提在同一个 issue**：
> **#75**（[链接](https://github.com/wenbin-wb/ypbin-admin/issues/75)）。

### 现象
`deploy/docker-compose.yml` 的两个健康检查把真实口令作为命令行参数传给容器内进程：

### 一手证据（2026-09-27，本机 `ypbin-admin`，`main` = `91a801e`）
```bash
cd /home/wenbin/projects/ypbin/ypbin-admin
grep -n "redis-cli\|mysqladmin" deploy/docker-compose.yml
```
```
53:      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD:?请在deploy/.env设置REDIS_PASSWORD}", "ping"]
74:      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-p${MYSQL_ROOT_PASSWORD}"]
```
Docker 的 healthcheck 以 `exec` 形式执行 ⇒ 该命令的 **argv 会出现在 `docker top` / 宿主进程列表**，
且每次探测都触发一次 `exec_create/exec_start` 事件，**`docker events` 的属性里带命令行**。每 10 s 一轮 ⇒ 口令在事件流里**持续**出现。
> **暴露面的本仓实测记录**（此机制不是我推断的、也不是只靠逻辑）：`ypbin-iot` 侧的
> `docs/DEPLOY-CREDENTIAL-HYGIENE.md` §5「argv / 凭据暴露面清点（`docker inspect` 全部容器 + `docker events` 采样 + 仓库值级 grep）」
> 把 healthcheck/脚本 argv 列为**本轮已修**项，并逐容器清点了暴露面与修法（Redis 配置化、MySQL `MYSQL_PWD`/`-e` 等）。
> 本条（UP-8）就是把那份处置**回推到上游模板**。

### 影响
真实 MySQL root / Redis 口令持续出现在进程参数与 Docker 事件流（可被同宿主低权限用户、
日志采集 agent、`docker events` 转发器获取）。与「口令只在 `.env`（600）」的承诺自相矛盾。

### 期望能力
健康检查**不带凭据**（探活语义足够）：
- Redis：`test: ["CMD-SHELL", "timeout 3 nc -z 127.0.0.1 6379"]`
  （注意：**不能**简单用 `redis-cli ping`——`NOAUTH` 时它仍可能 exit 0；而 `REDISCLI_AUTH`/`--no-auth-warning`
  仍会把口令放进环境/argv 的不同位置，需按「探活 vs 鉴权」区分）；
- MySQL：`test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1"]`（服务端起监听即 exit 0，不需要口令）。

### 验收标准
1. 改动后用 `docker inspect <container> --format '{{json .Config.Healthcheck}}'`，
   断言输出中**不含** `REDIS_PASSWORD`/`MYSQL_ROOT_PASSWORD` 的任何真实值（键名可留、值必须无）；
2. `docker events --filter event=exec_create --since 1m` 的属性里不含口令（可用一个占位口令做变异验证：
   改回旧写法时必须能抓到，证明判据有效）；
3. 健康状态语义不变：容器 `healthy` 仍能正确反映服务可用性（含「服务挂掉 ⇒ unhealthy」的反向用例）。

### 我方临时处置与替换路径
本 fork 已改为不带凭据的探活（`ypbin-iot/deploy/docker-compose.yml:70-79`（Redis，`nc -z`）、
`:99-106`（MySQL，`mysqladmin ping -h 127.0.0.1`），并在注释里写明「为什么不是 `-a <口令>`」）。
上游同步后，本 fork 的差异可在下次同步时消除
（`deploy/docker-compose.yml` **在** Sync 白名单内，见 `.github/workflows/sync-whitelist.yml` 的 `ALLOWED` 正则）。

---

## UP-9（中｜凭据卫生）`deploy/sql/005-xxl-job.sql` 内置默认口令 `123456` 的 SHA-256 哈希

> 状态：上游 **未修**。与 UP-8 合并为一个 issue：**#75**（[链接](https://github.com/wenbin-wb/ypbin-admin/issues/75)）。

### 现象
XXL-JOB 调度中心的用户表初始化脚本写死了内置账号 `admin` 的**默认口令哈希**，
口令即 `123456`（可离线复算并直接登录）。

### 一手证据（2026-09-27，本机 `ypbin-admin`，`main` = `91a801e`）
```bash
cd /home/wenbin/projects/ypbin/ypbin-admin
sed -n '189,190p' deploy/sql/005-xxl-job.sql
```
```sql
INSERT INTO `xxl_job_user`(`id`, `username`, `password`, `role`, `permission`)
VALUES (1, 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1, NULL);
```
```bash
printf '123456' | sha256sum
# 8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92  -
```
⇒ 哈希与 `sha256("123456")` **逐字相等**，即默认账号 `admin` / `123456`。
本 fork 记录的生产事实：`docs/microservice-deployment.md:69` 明确写着
「XXL-JOB 控制台仍是镜像默认 `admin/123456`（**未加固**）」。

**「该哈希就是登录时做的校验」已由 xxl-job 官方源码核实**（一手；访问日期 2026-09-27；tag `3.4.2`，
即本仓 compose 里 `xuxueli/xxl-job-admin:3.4.2` 的版本）：
```bash
curl -sS --http1.1 \
  https://raw.githubusercontent.com/xuxueli/xxl-job/3.4.2/xxl-job-admin/src/main/java/com/xxl/job/admin/framework/controller/LoginController.java \
  | sed -n '10p;67,68p'
#  10:import com.xxl.tool.crypto.Sha256Tool;
#  67:		String passwordHash = Sha256Tool.sha256(password);
#  68:		if (!passwordHash.equals(xxlJobUser.getPassword())) {
curl -sS --http1.1 https://raw.githubusercontent.com/xuxueli/xxl-job/3.4.2/doc/db/tables_xxl_job.sql \
  | sed -n '184,185p'
#  INSERT INTO `xxl_job_user`(...)
#  VALUES (1, 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1, NULL);
```
⇒ **上游官方仓库自己的 SQL 就是这一行**（本仓 `deploy/sql/005-xxl-job.sql` 系逐字继承），
且登录用 `Sha256Tool.sha256(password)` 与该列直接比对 ⇒「默认口令 = `123456`」链路完整。
来源：`xuxueli/xxl-job` tag `3.4.2`（官方仓库，一手），文件路径与行号如上，访问 2026-09-27。

### 影响
若 XXL-JOB 控制台可达（本仓 compose 里 `18085:8080`，`INTERNAL_BIND_ADDR` 默认 `0.0.0.0`），
攻击者用公开默认口令即可登录调度中心 ⇒ 可管理/触发任务（进一步影响业务系统）。
属「默认凭据」类缺陷，且因是**哈希**而不易被「扫明文口令」的工具发现。

### 期望能力
- 部署脚本在**首次初始化时生成随机口令**并写入受控位置（如 `.env` / 一次性输出），
  以该随机口令的哈希写入 `xxl_job_user`；或
- SQL 里保留 `NULL`/占位哈希，由 `install.sh` 在导入前用 `sed`（**非注释行**，见 UP-6）替换为随机值，
  并在安装日志里**一次性**提示「XXL-JOB 初始口令已随机生成，见 deploy/.env 的 XXL_JOB_ADMIN_PASSWORD」；
- 同时把控制台端口默认绑定收窄到回环（与 UP-8 的探活改造一并）。

### 验收标准
1. 全新部署后，`deploy/sql/005-xxl-job.sql` 的哈希**不等于** `sha256("123456")`，且
   `grep -c "$(printf '123456' | sha256sum | cut -d' ' -f1)" deploy/sql/005-xxl-job.sql` ⇒ `0`；
2. 用生成的随机口令可登录 XXL-JOB 控制台（正向）；用 `123456` 登录**失败**（负向）；
3. 该口令与其他服务口令**不同源**（不复用 `MYSQL_ROOT_PASSWORD`）。

### 我方临时处置与替换路径
本 fork 目前只在文档里**登记事实**（`docs/microservice-deployment.md:69`、
`docs/DEPLOY-CREDENTIAL-HYGIENE.md` §5），尚未改 SQL 模板（模板改动需与上游一致以免同步冲突）。
**建议优先级不高的理由**：该容器在本仓当前部署形态下**未运行**；但一旦启用即成为默认口令入口。

---

## UP-10（中｜DX / 文档陷阱）「未知路径/未暴露端点返回**业务 404 包在 HTTP 200**」极易被误判为「健康」

> 状态：上游 **未修**（文档未显式警示该陷阱，且有一处**文档与代码不一致**）。
> 上游 issue：**#55**（2026-09-27 开，[链接](https://github.com/wenbin-wb/ypbin-starter/issues/55)）。
> **归属订正（R4）**：本条**不属于 `ypbin-admin`**——`R` 与全局异常处理器都在 **`ypbin-starter`**
> （`ypbin-starter-core` 的 `R`、`ypbin-starter-web` 的 `GlobalExceptionHandler`、
> `ypbin-starter-cloud-gateway` 的 `GatewayExceptionHandler`），故 issue 开在 **starter**。

### 现象
对**未暴露/不存在的路径**，服务返回 **HTTP 200** + 业务体 `{"code":404,"message":"接口不存在","success":false}`。
因此 `curl -o /dev/null -w '%{http_code}' http://host:port/actuator/health` 得到 `200`，
**并不能证明服务健康**——它可能只是「这个 actuator 端点没暴露」的业务 404。

### 一手证据（2026-09-27）
**生产实测（只读）**：
```bash
for p in 18080 18081 18082 18084; do
  echo "--- $p"; curl -s -m 8 -w " http=%{http_code}\n" "http://127.0.0.1:$p/actuator/health"; done
```
```
--- 18080   {"code":404,"data":null,"message":"接口不存在","success":false,…}   http=200
--- 18081   {"code":404,"data":null,"message":"接口不存在","success":false,…}   http=200
--- 18082   {"code":404,"data":null,"message":"接口不存在","success":false,…}   http=200
--- 18084   {"groups":["liveness","readiness"],"status":"UP"}                   http=200
```
⇒ 同一个 HTTP `200`：前三个是**业务 404**，第四个才是**真健康文档**。本仓正是因此一度把
「返回 200」当作健康证据，后在生产复盘中纠正（`deploy/PROD-OPS-NOTES.md:277`、
`docs/DEPLOY-BACKEND.md:185-186`）。

**源码一手**（本机 `ypbin-starter`，`master` = `f6e841e`）：
```bash
cd /home/wenbin/projects/ypbin/ypbin-starter
sed -n '124,135p' ypbin-starter-web/src/main/java/cn/ypbin/starter/web/handler/GlobalExceptionHandler.java
```
```
131  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
132  public R<Void> handleNotFound(HttpServletRequest request) {
133      log.warn("[接口不存在] {}", LogSanitizer.sanitize(request.getRequestURI()));
134      return R.fail(GlobalErrorCode.NOT_FOUND.getCode(), "接口不存在");   ← 直接返回 R ⇒ HTTP 200
```
网关侧同构：`GatewayExceptionHandler.java:91/98` 同样 `return R.fail(NOT_FOUND, "接口不存在")`。
`R` 类无 `@ResponseStatus`（`R.java:36`；字段为 `code/message/data/success/timestamp`，
处理器所在类只有 `@RestControllerAdvice`）⇒ 默认 200。

**顺带发现的文档与代码不一致（建议随本条订正）**：
`docs/MODULES.md:53` 与 `GlobalExceptionHandler.java:129` 都写「（本模块）默认已开启
`spring.mvc.throw-exception-if-no-handler-found`」，但全仓检索该属性**只出现在文档与配置元数据里**：
```bash
grep -rn "throw-exception-if-no-handler" . | grep -v '/target/' | grep -v tools/
# ⇒ docs/MODULES.md:53、…/GlobalExceptionHandler.java:129（其余命中在 tools/ 的元数据 JSON）
```
`WebDefaultsEnvironmentPostProcessor` 实际注入两项（与 404 陷阱**均无关**）：
`spring.web.resources.add-mappings=false`（`:52`）与 `spring.threads.virtual.enabled=true`（`:55`）；
**没有**设置 `throw-exception-if-no-handler-found`。行为在 Spring Boot 4.1.1 下仍成立
（Framework 默认对无处理器抛 `NoResourceFoundException`），但**断言与配置不符**，会让读者以为该属性可依赖。

### 影响
- 运维把「HTTP 200」当健康信号 ⇒ **把不健康的服务判为健康**（本仓已实际发生并纠正）；
- 探活脚本/负载均衡/监控探针若只看状态码，会把「404 业务包」当成 UP；
- 该陷阱写在 README 里只有一句「HTTP 200 + 业务码」的**约定**，没有**鉴别方法**，新人极易踩。

### 期望能力
1. 在 starter 文档（README §约定 + `docs/MODULES.md` 全局异常处理）**显式写明该陷阱**并给出**鉴别判据**，
   例如可直接照抄的 `curl` 片段：
   ```bash
   # ❌ 不足以判健康：200 也可能是业务 404
   curl -s -o /dev/null -w '%{http_code}\n' http://host:port/actuator/health
   # ✅ 鉴别判据：既要 HTTP 200，又要 body 里没有业务错误码；真 actuator health 形如 {"status":"UP"}
   curl -s -w '\nhttp=%{http_code}\n' http://host:port/actuator/health | tee /dev/stderr \
     | grep -q '"status":"UP"'
   # 或统一判 R.code：body 里出现 '"success":false' / '"code":404' 即为「未暴露端点」而非健康
   ```
2. 明确「**哪些端点**是真 actuator、哪些路径会回落到业务 404」，并建议健康探针**只打真实暴露的端点**；
3. 订正 `docs/MODULES.md:53` / `GlobalExceptionHandler` Javadoc 中关于
   `throw-exception-if-no-handler-found` 的「已默认开启」表述（改为「由 Framework 默认抛出，本模块另注入
   `spring.web.resources.add-mappings=false` 作为防御性配置」），避免读者依赖一个未被本模块设置的属性；
4. （可选，更强）在 starter 提供**判健康的单一入口**（如 `R` 之外的 `/ypbin/health` 语义或文档化的探针脚本），
   让下游不必各自猜。

### 验收标准
1. 文档里给出的 `curl` 判据能在**两种真实响应**上分别给出正确结论（真 UP 文档 vs 业务 404 包），
   即判据本身可复现、可变异验证；
2. `docs/MODULES.md` 与实际注入的属性**逐条一致**（写进门禁或人工核对清单）——至少不再出现「本模块已默认开启 X」
   而代码未设置 X 的表述；
3. 本仓把 `deploy/` 下所有探活脚本改为「按 body 判据 + 真实端点」，端到端复验一次（已有先例，见上引两处文档）。

### 我方临时处置与替换路径
本仓已把探活口径改为「**只看真实 actuator 端点 + 判 body**」：`deploy/post-reboot-check.sh:9-10/21/99-100`、
`deploy/rollback-mem-20260926.sh:73-83`、`deploy/mem-round2/accept.sh:4/56`、
`docs/DEPLOY-BACKEND.md:184-188`（正确的 body 判据）、`deploy/PROD-OPS-NOTES.md:269/277-278`。
**这些是本仓的自救口径**，上游文档补齐后可直接引用上游判据、删除散落各处的重复说明。
> 本仓仍有一处**旧的「只看状态码」口径**未同步订正：`docs/NACOS-AUTH.md:211-212`
> （「`/actuator/health` = 18080/18081/18082/18084 **200**」——恰恰是把业务 404 当健康的写法）。
> 本轮**不改它**（超出本任务范围，且属本仓文档一致性事务），登记在此供后续本仓清理。

---

## 附：证据总表（复核范围、命令与状态）

> 全部复核在**本机**（`/home/wenbin/projects/ypbin/`）完成；涉及生产的两条为**只读** SSH/curl，未使用任何账号口令。
> 仓的检出与版本：`ypbin-admin`@`91a801e`(main)、`ypbin-starter`@`f6e841e`(master, 3.5.1-SNAPSHOT)、
> `ypbin-iot-starter`@`de296e2`(master) 与 tag `v0.1.0`@`878a493`、`ypbin-iot`@`ca685b8`(main)。

| # | 条目 | 仓 | 文件:行号 | 复核命令（可复现） | 状态 |
|---|---|---|---|---|---|
| A1 | UP-3 整帧 `byte[]` | iot-starter | `TcpSession.java:295`（v0.1.0 与 master 同号） | `grep -n "PointValue.good(address, payload" …TcpSession.java` | **已核实** |
| A1 | UP-3 姊妹模块有、TCP 无 | iot-starter | `MqttPayloadFormat.java:39/42/45`；`MqttProperties.java:43/72`；`TcpProperties.java`（无该字段） | `grep -rn "payload-format\|payloadFormat"`；`grep -c payloadFormat TcpProperties.java` | **已核实** |
| A1 | UP-3 core 无解码 SPI | iot-starter | `ypbin-iot-core/src/main/java/cn/ypbin/iot/core/spi/`（7 个类，无 Decoder） | `ls …/core/spi/`；`grep -rn "Decoder" …/core/src/main` | **已核实** |
| A2 | UP-4 只投递第 0 个点位 | iot-starter | `TcpSession.java:219`（`.get(0)`）、`:221`（闭包只用 `streamAddress`）、`:361-363`（`addresses()` 返回全部） | `sed -n '212,227p' …TcpSession.java` | **已核实** |
| A2 | UP-4 生产观测（4 点位仅 1 有数据） | iot | `docs/DEMO-DATA.md:23`、`docs/ACCESS-ENABLE.md:151-156` | `sed -n '18,26p' docs/DEMO-DATA.md` | **已核实**（本仓生产记录） |
| A3 | UP-2 默认超时硬编码 | iot-starter | `ConnectionSpec.java:51/63/78`；`NettyTransport.java:161` | `grep -n "DEFAULT_CONNECT_TIMEOUT" ConnectionSpec.java` | **已核实** |
| A3 | UP-2 配置项是死键 | iot-starter | `IotProperties.java:319-341`、`IotAutoConfiguration.java:200`、`DefaultAdapterSettings.java:55/82` | `grep -rn "settings()\.connectTimeout" …/src/main` ⇒ **0 命中** | **已核实** |
| A3 | UP-2 `bind` 阻塞调用方线程 | iot-starter | `IotLifecycle.java:73`（`BIND_TIMEOUT=10s`）、`:214-224`（`.join()`） | `sed -n '210,224p' …IotLifecycle.java` | **已核实** |
| A3 | UP-2 生产线程/节奏旁证 | 生产实例 | `docker logs ypbin-access`（线程 `scheduling-1`；每 2 分钟 9 台一轮） | `grep "failed to bind device"` + `grep -oE` 计数 | **已核实**（今日） |
| A3 | UP-2 `ConnectTimeoutException after 10000 ms` 原始形态 | iot | `docs/STARTER-FEEDBACK.md` §UP-2 证据段（2026-09-26 记录） | —（**当前生产已打补丁，无法再现**） | **已核实（历史记录，不可再现）** |
| A4 | SF-4 未修（**仅限 starter 侧这半条**） | starter | `IdentityStpLogic.java:63/90-96/109-111` | `sed -n '57,63p;89,96p;104,111p' …` | **已核实**（starter 侧代码；sa-token「`distUsableToken` 要求 `== null`」半条系引自 `docs/STARTER-FEEDBACK.md` 的既有字节码实证，本轮**未**重做） |
| A4 | SF-5 未修 | starter | `IdentityHeaderFilter.java:57-94`（无签名校验）；`FeignHeaderInterceptor.java:128-134`（仅 Feign 透传侧、默认 fail-open）；`GatewayProperties.java:223/226` | `grep -n "Signed\|trustedSource" …IdentityHeaderFilter.java` ⇒ 0 | **已核实** |
| A5 | UP-5 网关只认证不授权 | starter | `GatewayAuthGlobalFilter.java:76-84`（仅 `authenticate`） | `sed -n '76,84p' …` | **已核实** |
| A5 | UP-5 路由与白名单 | iot | `deploy/nacos/ypbin-gateway.yaml:49-54`（`/iot/**`+StripPrefix）、`:57-82`（白名单无 `actuator`）；`deploy/nacos/ypbin-system.yaml:90-94`（服务侧 `excludes: /actuator/**`） | `grep -n "Path=/iot/\*\*" -A 4 …` | **已核实** |
| A5 | UP-5 未登录被拒（登录是唯一门槛） | 生产实例 | `curl http://127.0.0.1:18080/iot/actuator/metrics` ⇒ `code=401`，HTTP 200 | 同上 | **已核实** |
| A5 | UP-5 服务侧直连**零认证** | 生产实例 | `curl http://127.0.0.1:18084/actuator/metrics`（不带任何认证头）⇒ HTTP 200 + `{"names":[…]}` | 同上 ⑥ | **已核实** |
| A5 | UP-5「非管理员账号**经网关**实测可读」 | — | — | 未使用账号口令 | **未核实** |
| B6 | UP-6 全局 `sed` | admin | `deploy/install.sh:1286-1298` | `sed -n '1275,1299p' deploy/install.sh` | **已核实** |
| B6 | UP-6 上游当前无注释占位符（潜在） | admin | `deploy/nacos/`（**6 处**占位符，跨 4 个文件，全在配置行） | `grep -rn "^\s*#.*\${" deploy/nacos/` ⇒ 无输出；`grep -rn '\${[A-Z_]*}' deploy/nacos/ \| wc -l` ⇒ **6** | **已核实** |
| B6 | UP-6 本 fork 的真实事故 | iot | `deploy/nacos/ypbin-iot.yaml:88-90`；物证 `docs/DEPLOY-BACKEND.md:233-240` | `grep -rn "^\s*#.*\${" deploy/nacos/` ⇒ 3 命中 | **已核实** |
| B7 | UP-7 `NACOS_DIR` 写死 | admin | `deploy/install.sh:1278`（+`:913/963` 克隆名、`:1280` 无 else） | `grep -n "NACOS_DIR=" deploy/install.sh` | **已核实** |
| B7 | UP-7 生产路径判定 | 生产实例 | 两种 ROOT 下 fork 的两份模板均 `NO` | `for R in …; do [ -f … ]` | **已核实** |
| B7 | UP-7 本 fork 循环已 7 份但目录未改 | iot | `deploy/install.sh:1344`、`:1351` | `grep -n "NACOS_DIR=\|for cfg in" deploy/install.sh` | **已核实** |
| B8 | UP-8 口令进 `exec` | admin | `deploy/docker-compose.yml:53`（redis `-a`）、`:74`（mysql `-p`） | `grep -n "redis-cli\|mysqladmin" deploy/docker-compose.yml` | **已核实** |
| B8 | UP-8 本 fork 已改为无凭据探活 | iot | `deploy/docker-compose.yml:70-79`、`:99-106` | `grep -n "nc -z\|mysqladmin" deploy/docker-compose.yml` | **已核实** |
| B9 | UP-9 默认口令哈希 | admin | `deploy/sql/005-xxl-job.sql:189-190` | `sed -n '189,190p' …` + `printf '123456' \| sha256sum` | **已核实** |
| B10 | UP-10 生产 200/404 误判 | 生产实例 | `18080/18081/18082` vs `18084` 的 `/actuator/health` | `for p in …; do curl -s -w ' http=%{http_code}\n' …; done` | **已核实** |
| B10 | UP-10 处理器源码 | starter | `GlobalExceptionHandler.java:131-135`；`GatewayExceptionHandler.java:91/98`；`R.java:36` | `sed -n '124,135p' …` | **已核实** |
| B10 | UP-10 文档断言与代码不一致 | starter | `docs/MODULES.md:53`、`GlobalExceptionHandler.java:129` vs `WebDefaultsEnvironmentPostProcessor.java:52` | `grep -rn "throw-exception-if-no-handler" .` | **已核实** |

**未核实项（不掩盖）**：
1. **UP-5「经网关」的非管理员越权读实证**：已证「网关仅认证、无权限码」+「服务侧 `18084` 直连**零认证**」，
   但**未**用非管理员账号**经网关**实测读取指标（未使用任何账号口令）。
2. **UP-2 的原始 `ConnectTimeoutException after 10000 ms` 生产日志**：为本仓 2026-09-26 的记录，
   当前生产已把 endpoint 改为快速失败，**无法再现**；今日仅取得「`scheduling-1` 线程 + 每轮 9 台」的旁证。
3. **（原第 3 条已补证、不再是未核实项）UP-9 的「镜像校验算法」**：`xuxueli/xxl-job` tag `3.4.2` 的
   `LoginController.java:67-68` 用 `Sha256Tool.sha256(password)` 与 `xxl_job_user.password` 列直接比对，
   官方 `doc/db/tables_xxl_job.sql:184-185` 就是本仓继承的同一行 ⇒ 链路完整（一手来源与访问日期见 UP-9 正文）。
   > 说明：本条原标「未核实」是**偏保守**的写法（只差官方源码这一步）；经独立复核者指出后已补齐证据并升级为**已核实**。
   > 保留此记录是为了让「标注如何被订正」本身可追溯。

---

## 关联文档
- 本仓 `docs/STARTER-FEEDBACK.md`（SF-1~SF-5 / UP-1~UP-3 完整版）
- 本仓 `docs/DEPLOY-BACKEND.md` §5.5/§5.6（凭据卫生与轮换实测）、`docs/DEPLOY-CREDENTIAL-HYGIENE.md`
- 本仓 `docs/VALUE-DECODE-DESIGN.md`（UP-3 过渡解码层与替换路径）
- 本仓 `docs/ACCESS-ENABLE.md` §3.1 / `docs/DEMO-DATA.md`（UP-4 生产观测）
