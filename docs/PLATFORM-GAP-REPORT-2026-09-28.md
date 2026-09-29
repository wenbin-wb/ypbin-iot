# ypbin-iot 差距分析报告（现状 × 计划承诺 × 成熟平台做法）

> **日期**：2026-09-28
> **性质**：本报告由多代理审计生成，**待人工审阅**（未 commit、未 push，纯新增文件）。
> **范围**：`ypbin-iot`（HEAD `d11a15d`，branch `feat/alert-c1-core`，clean）+ `ypbin-iot-ui`（HEAD `4394e53`，clean）双仓；
> 以仓内设计/路线/部署文档为「计划承诺」，以公开市场研究为「成熟平台做法」参照。
> **证据口径（R1）**：后端文档证据 = 当前工作区文件行号（关键 6 处已抽验并标注）；市场做法 = 市场研究子代理的一手官方抓取（均附官方 URL、访问日期 2026-09-28）；无法独立核实的条目单独标注「未核实」，不推断充数。
> **判级口径（严格不放大）**：**P0**＝有占位/假按钮/承诺未做且用户会碰到（含安全高危）；**P1**＝成熟平台标配或显著提升体验/可靠性；**P2**＝可选增强。P0 仅 4 项。
> **⚠️ 文档矛盾治理**：本文引用发现的多处文档间矛盾均「以更晚部署/验收证据为准」，并在对应行标注应回写何处；见 §4.0、§8。

---

## 0. 结论先行

### 0.1 成熟度定位

> 一句话：**核心链路已具备雏形且单机生产可跑（物模型 ↔ MQTT 出入站 ↔ 时序存取 ↔ 告警闭环 ↔ 前端七页），但距「成熟 IoT 平台」还差 5 条主线。**

| 主线 | 一句话差距 | 等级 |
|---|---|---|
| ① 告警通知渠道承诺「做」但生产邮件必败；设备「全部停用」无写入口 | 邮件渠道设计为「做」，但生产未配 SMTP ⇒ 每封必 FAILED；设备 `status` 无写入口 | **P0** |
| ② 外部接入面 TLS/集群/凭据对账未闭环 | 单节点明文 1883 测试期开放、TLS/集群未开、台账↔EMQX 差集对账未做、入口免校验可伪造身份 | P1 系（SF-5 为 P0） |
| ③ 开放 API（M-5）、批量注册、消息跟踪、审计日志四项「成熟平台标配」整块缺失 | 均为未开始/无规格 | P1 |
| ④ 傻瓜式易用性在规格层就缺 | onboarding/products/devices 仍是裸表单，无向导/模板/默认值闭环/CSV/人话错误提示 | P1 |
| ⑤ M-6 发布向（README/许可/一键安装指向）全未做 | README 仍是 admin 原版、无许可证、`install.sh` 硬编码部署 upstream | P1（发布门禁） |

> **刻意不做项（Webhook/OTA/自动重试/双状态/可视化编排远期）不是缺口**，引用方勿报；但建议把 OTA 从「非目标」升为「远期候选」登记，以对齐对外口径（见 §8 决策点 4）。

### 0.2 最该先做的 10 项（按收益/成本排序，详见 §7）

| # | 事项 | 等级 | 量级 |
|---|---|---|---|
| 1 | 生产 SMTP 配置 + 送达验收（邮件渠道从必败到可用） | P0 | 纯配置 |
| 2 | 设备「全部停用」写入口（`IotDeviceReq` 加 `status` + 端点 + 前端开关） | P0 | 半天 |
| 3 | i18n 键补全 + 菜单键自检扫描（防显示原始 key） | P1 | 低 |
| 4 | SF-5 入口侧身份签名校验（或等上游 3.5.1） | **P0（安全）** | 中 |
| 5 | CSV 批量注册 + 批次管理（两张表 + CSV 解析 + 错误行下载） | P1 | 中 |
| 6 | README/许可证重写（M-6）+ `install.sh` 目录名拍板 | P1（发布门禁） | 低 |
| 7 | 消息跟踪 + 「定位建议」（设备详情页签，复用 debug 组件） | P1 | 中 |
| 8 | TLS 8883 开放 + 收回明文 1883（EMQX 配置为主） | P1（生产门禁） | 低 |
| 9 | 平台自告警 + 指标大盘（Q7/A12/C4，复用已产出 `iot.alert.*` 指标） | P1 | 中 |
| 10 | 开放 API（M-5 起步：OpenAPI 规范 + API Key 鉴权挂接现有端点） | P1 | 中高 |

---

## 1. 已实现能力一览

> 为避免把「已做」误报为差距，先把证据明确的已实现能力登记如下。上架判定：文档标注 ✅ 已闭环 / 生产实测证据 / git 历史可查。

**A. 物模型域**
- TSL 单文件导入导出 + 全字段校验已落地（`IOT-PLATFORM-DESIGN.md:291-332`）。
- 产品→服务→属性/命令+事件的 IoTDA 对齐结构（`IOT-PLATFORM-DESIGN.md` 总纲，物模型域 M-1 已交付）。

**B. 接入域（EMQX）**
- **段 A（MQTT 入站）已完成并合并**（PR #81 `398ddcb`，2026-09-27，已并入 origin/main 且为当前 HEAD 祖先）：
  薄适配端点 `POST /internal/mqtt/readings`、EMQX 规则 + HTTP 动作（`max_buffer_bytes=16MB`）、设备凭据与 EMQX 内置库同步（签发/轮换→`import_users` 只上报哈希；吊销→删账号）、双向通道（生产机 autossh + 中间件机 socket-proxyd 中继）。
- **段 B（下行 / 在线调试）已实现并生产实测通过**：`iot_command_instance` 六态状态机 + 下发/查询/手动重发端点 + 周期超时扫描（不自动重试）+ `POST /internal/command-replies` 幂等回执 + `iot:debug:send/get` 权限码与菜单（`EMQX-INTEGRATION.md` §6.2.3，2026-09-27）。
- **段 C（前端「在线调试」页）已上线**（`ypbin-iot-ui` PR #26 + 补丁 #27/#28）：设备详情抽屉第 5 个页签，历史分页 + 状态筛选 + 手动重发同一 requestId + 三态如实展示（`EMQX-INTEGRATION.md` §6.2.3）。
- 1883 开发测试期对外暴露并完成外部端到端验收（commit `c083b93` PR #89；`EMQX-DEPLOY.md` §10 U-A「2026-09-27 已按用户决策开放」）——注意这是开发期显式风险接受，H3「无 TLS 不得裸暴露」未被推翻。

**C. 数据面**
- IoTDB 表级 TTL 90 天已落地（PR #34，`IOT-ROADMAP.md:855`）。
- 影子 `reported` 已接入（G2 项部分闭环）。

**D. 告警域（段 C1–C2）**
- 告警模型已有 level / active-ack-cleared / 去重（`uk_alert_active` 列序生产真库确认）/ 抑制 / 手动关闭留痕（`ALERTING-DESIGN.md:376,691-703`）。
- 生产实测闭环：`FIRING`（连续 3 次越界）→ 一键 ACK → 条件恢复 `RESOLVED` 全链路跑通；站内信真实落库到目标用户；邮件因未配 SMTP 如实失败（`ALERTING-DESIGN.md:748-756`）。
- 前端 `alerts` 页已上线（`views/iot/alerts/index.vue` + 测试）。

**E. 规则 / 对账 / 安全兜底**
- 声明式 JSON 条件-动作规则已落地（`IOT-PLATFORM-DESIGN.md:681-689`）。
- 对账 R8-3 已修（同一版本号只计一次、零设备租户走廉价重试）；G8 有周期安全网兜底（最坏一个轮转周期收敛）；G6′ 属性改名/新版本发布推进 `config_epoch` 已覆盖点位地址/类型/周期/字节序（`IOT-ROADMAP.md:404-414`）。
- 凭据对账任务主体已按 P0-A-3 落地（`DEVICE-CREDENTIAL.md`）。

**F. 前端页面目录（已探针确认实存）**
- `ypbin-iot-ui` 七个 iot 页面目录：`devices / groups / maintenance / onboarding / products / alerts / tenant-ledger`。

> ⚠️ **文档口径冲突登记**：`IOT-ROADMAP.md` 顶部状态更新块（§1 上方）声称段 A/B/C 已完成，内容与 git 历史吻合，但其标注日期「2026-10-02」为未来日期、且 `EMQX-DEPLOY.md` §10 U-C 仍标注「平台侧集成未做」——**段 A 以 git `398ddcb` 与 ROADMAP 顶部为准（已完成），EMQX-DEPLOY §10 对应行应回写**；ROADMAP 顶部日期应修正。

---

## 2. 占位 / 未完成总账

> 覆盖现状盘点全部登记事项，按事项去重归类。状态：⬜未做/未验证｜🟡部分/临时｜✅已闭环（保留）｜➖刻意不做（非缺陷）｜❓未核实。

| 类别 | 条目 | 现状 | 建议下一步 |
|---|---|---|---|
| **告警·功能** | 邮件 SMTP 生产配置（`ALERTING-DESIGN.md:748,756`） | ⬜ 配置缺失→投递 FAILED | 配 SMTP+送达验收；明示渠道状态 |
| | 平台自告警/大盘 Q7、指标接大盘 A12/C4（`IOT-ROADMAP.md:476,413,552`） | ⬜ | 接大盘+健康阈值规则 |
| | 告警 7 项验证边界（真库/指标读数/分布式锁/量级/DURATION 线性）（`ALERTING-DESIGN.md:699-711`） | ⬜ 部分由生产演示缓解 | 多实例+压测验收清单 |
| | 告警模型增强（四态/指派/评论） | 🟡 已有 level/ack/cleared | P2 再加指派/评论 |
| | 断档类告警真实触发未验（`ALERTING-DESIGN.md:770-772`） | ⬜ outage.mapped=0 | 造断档实测一次 |
| | 前端 alerts 页测试缺口 `list-states.test.ts`（`ALERTING-DESIGN.md:742`） | ⬜ | 依赖解决后补测 |
| **对外接入** | TLS 8883/WS/WSS、EMQX 集群（U-B/U-A3） | ⬜ 单节点明文 | 发布门禁：8883+收回明文 |
| | 上报失败重试持久化通道 A9（`IOT-ROADMAP.md:474`） | ⬜ 丢弃 | MQ 通道+退避重试 |
| | U4 值转义、U-A5 503 重试生产实测、U-A7 批量上报、U-A9 凭据对账、U-A11 防火墙持久性、U-A12 停摆告警、U-A13 幂等非原子、U-A14 防火墙 URL 变更实测（`EMQX-INTEGRATION.md:513-528`） | ⬜/🟡 全部登记 | 按 P1 优先级逐项；U13 多节点前必修 |
| | 入站凭证最小化 U-A8（复用全局 INTERNAL_TOKEN） | ⬜ P1-5 | 独立凭据 |
| | 下行 U-B1 writeDesired / U-B2 离线排队 / U-B3 取消端点 | ⬜ 端点显式拒绝；U-B4 ➖刻意不做 | U-B1/B3 看设备侧约定；U-B2 P1 与设备厂商定 |
| **对账/一致性** | R8-2 tick 越界、R8-4 epochs 不按节点过滤、R8-5 fence 不清理订阅、R8-6 规格变化只重发 ADD、R8-7 平台级不变量无门禁、R8-8 同事务无守卫、R8-9 安全网成本（`IOT-ROADMAP.md:406-414`） | ⬜（R8-3 已修、R8-9 指标已缓解） | 多租户/多节点扩展前按序做；现阶段不触发 |
| | G6′ 改名不推进 config_epoch / G8 单租户无信号（已周期安全网兜底） | 🟡 G8 有兜底；G6′ ⬜ | G6′ 小改；G8 维持兜底+登记代价 |
| | 四点十七 坐标碰撞/ts 护栏（`IOT-ROADMAP.md:722-723,731-740`） | 🟡 仅计数告警 | 部署 access 后处理 |
| **设备/台账** | G7′ 设备全部停用无写入口（`IOT-ROADMAP.md:404`，已抽验） | ⬜ **P0** | 加 status 字段+端点+开关 |
| | 四点十 P6/P7/P8/P10（静默回落/容量回收/status 过滤/死锁面） | ⬜ 4 项未做 | 逐项小改 |
| | G8M-1 productName/groupName 未做 | ⬜ | DTO 补字段 |
| | G4 产品级点位模板 | ⬜ | 品类模板+预置点位 |
| | 批量注册/批次/量产 CSV（阿里批次管理） | ⬜ 无规格 | 两张表+CSV 解析 |
| | 动态分组、双状态（Neuron 式）、拓扑关系 | ⬜ 评估项/非必要不做 | 保持评估，不引入通用关系表 |
| | 影子 `shadow_json` 死列清理（G2/P2-3） | 🟡 reported 已接入；死列 ⬜ | P2-3 清理 |
| **验证未做** | 四点二十 5 项（TTL 越界/start-cli -e/compose healthy/quality=null/浏览器渲染）（`IOT-ROADMAP.md:868-869`） | ⬜ | 发布检查清单逐项实测 |
| | L3/L6 会话判等/并发 bind 真 socket e2e、L4/L5/L7 缺实现（`IOT-ROADMAP.md:518-522`） | ⬜ | 接 mqtt/opcua 后兑现 L7 |
| | M3 窗口边界/宽松口径、M5 链级端点用例、M6 整窗维护三态待拍板、M7 并发重复（`IOT-ROADMAP.md:597-601`） | ⬜/待拍板 | M6 需用户拍板；其余按增量排 |
| | C1 偏移滤波、C4 接入侧指标链路（`IOT-ROADMAP.md:549,552`） | ⬜（C2/C3/C5 已闭环） | C4 并入 A12 |
| | 四点二十一 Modbus 位宽/符号/位域/解码；真实设备 | ⬜ | 需真实设备立项（高成本） |
| **安全/上游反哺** | SF-4 identity 登录、SF-5 身份伪造（`STARTER-FEEDBACK.md:8-14`） | 🟡 部署配置侧临时处置，仓内未改 | SF-5 **P0** 本仓入口校验或等 3.5.1 |
| | UP-1/2/3/4/5/11/12（`.github` 白名单/建链超时/TCP 解码/订阅第 0 点位/actuator 无权限码/bean 竞争/WARN 措辞） | 🟡 部分临时绕开，上游未修 | 随上游版本跟进；UP-5 本仓可先行 |
| | UP-6~UP-10（install.sh 注入/口令 argv/xxl-job 默认口令/HTTP200-404 陷阱）（`UPSTREAM-FEEDBACK-2026-09-27.md:450-795`） | 🟡 fork 部分处置 | 逐条回写处置状态 |
| | Nacos 超管账号/匿名调用点未扫描/口令改写链路未验（`NACOS-AUTH.md:163,273-276`） | ⬜ | 扫描+收紧 |
| | 网关无权限码残余边界、access health 挂起未查明（`DEPLOY-BACKEND.md:174-180,272-275`；`DEVICE-CREDENTIAL.md:254`） | ⬜ 未决 | 单独排障项 |
| **运维登记** | PROD-OPS §7/§8/§9 刻意不做 9 项 + 回滚资产只登记未删（`PROD-OPS-NOTES.md:476-511,552-564`） | 🟡 13 文件 SAME=13 已对账 | 按维护窗口逐项；不阻塞 |
| | 凭据卫生 6 项只登记未修（root 口令 argv/install.sh 打印 AK/rollback 脚本明文等）（`DEPLOY-CREDENTIAL-HYGIENE.md:161-172,255-260`） | ⬜ 登记 | 维护窗口清理；容器未运行项低危 |
| | IoTDB/部署残留（JMX_OPTS 生效路径未核实、全新安装口令人工步骤未修、argv 明文未消尽）（`DEPLOY-TIMESERIES.md:384-411,243-246`） | ⬜ | 核实+补渲染 |
| **占位（刻意不做/远期，防误报）** | 计费/SLA/OTA/自研 Broker/自研时序/自研组态；BACnet/HJ212 新协议需写码；补采默认不做；热路径禁 DB/RPC；编解码插件占位；Webhook（唯一 SSRF 面）；无升级链/抑制/分组路由；无人工关闭已改为做；离线告警 opt-in；下行自动重试；批量上报落地计划；平台入站限流；Logging 降级占位；树模型不采用；整窗维护三态待拍板；前端多元素扇出后端后续项；`ILeaseClient` 随增量 3；access 指标潜在；业务层零代码目标（`IOT-PLATFORM-DESIGN.md:79-81,180-181,426-428`；`ALERTING-DESIGN.md:19,386,447-449,666,680`；`EMQX-INGRESS-DESIGN.md:79,521,523-530,1009`；`VALUE-DECODE-DESIGN.md:52,89-91`；`IOT-ROADMAP.md:598,659`；`IOT-UX-PROPOSAL.md:1105-1108`；`EMQX-INTEGRATION.md:344-352,430-432`） | ➖ 文档明示 | **引用方勿当缺口报**；仅建议 OTA 升「远期候选」+ M6 三态等用户拍板 |
| **未核实** | 旧仓 ypbin-iot-cloud 归档；EMQX 集群内置库复制/U11-13；OSS bootstrap_file U18；树模型拒裸聚合；insertTablet 性能；OPC UA ByteString 路径（`VALUE-DECODE-DESIGN.md:84`）；`IOTDB_JMX_OPTS` 生效路径（`DEPLOY-TIMESERIES.md:411`）；「主键 field=0 兼容性」实为未部署 access 的推论（`IOT-ROADMAP.md:751`） | ❓ | 逐项按 R1 核实后再闭环 |

---

## 3. 与计划文档差距表（我们承诺了什么 vs 现状）

> 与 §4 的区别：本表只收**计划文档自己承诺/自标未完成**的项（含文档间矛盾），对照「计划承诺 → 现状证据 → 等级」；§4 收「成熟平台有而我们缺」的市场对照项（两表允许同一能力域出现，视角不同不合并）。

| 能力域 | 计划承诺（出处） | 现状与证据 | 等级 | 建议 |
|---|---|---|---|---|
| TLS/集群（生产门禁） | ROADMAP 顶部行 19「仍未做：真设备 1883 对外暴露、TLS/8883、EMQX 集群」；EMQX-DEPLOY U-A/U-B 已开放明文 1883、TLS 未开 | **文档矛盾**：`IOT-ROADMAP.md:19` 状态滞后于 commit `c083b93`（1883 已开放）与 `EMQX-DEPLOY.md:832` — **以 deploy 侧为准**，ROADMAP 该行应回写 | **P1**（生产上线前升 P0） | 开 8883 + 收回明文 1883 到回环/限来源，作为「生产发布门禁」一项 |
| 上报失败重试（A9） | 上报失败即丢弃，断档被算长；EMQX/MQ 持久化通道（access 出口侧）未实施（`IOT-ROADMAP.md:474`） | 现状即上式，未实施 | **P1** | access 出口加 MQ 持久化通道 + 带 requestId 去重的指数退避重试（复用告警侧退避模型） |
| value 特殊字符（U-A4） | 值含双引号/反斜杠未 JSON 转义 → 400（`EMQX-INTEGRATION.md` U-A4） | 未做 | P1 | 上报侧接入 `json_encode` 转义（文档已标 P1 用此方案） |
| 批量上报（U-A7/U24） | 契约 P0=单点位一条消息；多点位批量「当前无落地计划」（`EMQX-INGRESS-DESIGN.md:523,527-530`） | 无落地计划 | P2 | 兼容单条消息多点位（数组 payload），向后兼容单点位 |
| 凭据对账（U-A9） | 台账↔EMQX 差集对账未做；吊销时 EMQX 不可达留残留账号 | 未做；`DEVICE-CREDENTIAL.md` 已用 P0-A-3 落地对账任务主体 | P1 | 补定时对账任务（台账↔EMQX 差集），吊销时标记残留 |
| 设备「全部停用」（G7′） | `IotDeviceReq` 无 `status` 字段，只能删设备/删映射触发（已抽验 `IOT-ROADMAP.md:404`） | 未做 | **P0** | 加 `status` 字段 + 端点 + 前端开关，联动 `DeviceSpecServiceImpl` 过滤 |
| 批量注册/量产 | 表清单无批量相关（`IOT-PLATFORM-DESIGN.md:746-763`）；现状仅有单条创建 | 无规格 | P1 | 两张表（批次+导入记录）+ CSV 解析 + 错误行下载重传 |
| 产品级点位模板（G4） | 未做（探针 `point-template` 无命中）；产品创建无模板/品类库 | 未做；UX 线框已有（`IOT-UX-PROPOSAL.md:671`） | P1 | 内置基础品类模板（温湿度/开关等）+ 创建产品时可选并预置点位 |
| 详情字段 productName/groupName | `IotDeviceResp` 仅 `productId`（探针 grep 无命中；M-1 G8 未做） | 未做 | P1 | 响应加 `productName/groupName` 冗余字段 |
| TSL 批量导入/diff | 单文件导入导出+全字段校验已落地（`IOT-PLATFORM-DESIGN.md:291-332`）；zip 批量、发布前 diff 缺（`IOT-UX-PROPOSAL.md:172`） | 部分 | P2 | 导入支持 zip；发布前差异对比视图 |
| 开放 API（M-5） | **未开始**：仓内 `grep openapi` 无命中；API Key 设计已有（哈希/轮换/限流，`IOT-PLATFORM-DESIGN.md:707-714`） | 未开始 | P1 | 按 M-5 里程碑启动：OpenAPI 规范 + API Key 鉴权接入现有端点 |
| 一键安装指向 | `install.sh` 硬编码 `$ROOT/ypbin-admin/deploy/nacos` ⇒ **部署的是 upstream 不是本仓**（已抽验 `IOT-ROADMAP.md:116-119`）；「30 分钟起起来」未验收 | 未做 | **P1**（发布门禁，需拍板目录名） | 拍板目录名（admin/iot）后改 `NACOS_DIR` 并端到端验收 |
| README/许可（M-6） | README 仍为 admin 原版「🛡️ ypbin-admin」无 IoT 章节（`README.md:3-5`）；许可声明未做（`IOT-PLATFORM-DESIGN.md:808`） | 未做 | P1 | M-6：重写 README（IoT 章节+快速开始）+ 许可证文件 |
| i18n 键盲区 | `page.iot.credential.*` 键前端未补（`EMQX-INTEGRATION.md:344-352`）；补丁 PR #27 已修 `page.iot.debug.get`，证明该类键会显示原始 key | 部分 | P1（低成本小修） | 补全 i18n 键 + 用「菜单标题=键」自检扫描 |
| 平台自告警/大盘（Q7/A12/C4） | 「指标未接大盘/告警」（`IOT-ROADMAP.md:476`）；access 侧指标暴露链路未接（`:413,552`）；`/actuator/metrics` 未真实进程读值（`ALERTING-DESIGN.md:699-711`） | 未做 | P1 | 接指标到大盘 + 3-5 条平台健康规则（评估器 lag/队列积压/投递失败率） |
| M6 整窗维护三态 | 待拍板（`IOT-ROADMAP.md:597-601`） | 待拍板 | — | 见 §8 决策点 2 |

---

## 4. 与成熟平台差距表

> 市场依据：阿里云 IoT 平台（help.aliyun.com，2026-09-28）、华为云 IoTDA（support.huaweicloud.com，2026-09-28）、ThingsBoard（thingsboard.io docs，2026-09-28）。判级见 §0。

### A. 外部接入面（EMQX 集成）

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| MQTT-TLS/集群 | 仅单节点、`0.0.0.0:1883` 明文测试期开放（判定已开放，凭 commit `c083b93` + `EMQX-DEPLOY.md:832`）；TLS 8883/WS/WSS 容器内显式 disable、集群未涉及（`EMQX-DEPLOY.md:833,835`）；ROADMAP 顶部行 19 仍写「未做」——**文档矛盾，以 deploy 侧为准**（已抽验 `IOT-ROADMAP.md:19`） | ThingsBoard 默认 MQTT 即支持 TLS；EMQX Dashboard 在线热更新 cluster 配置（docs.emqx.com guides/dashboard） | **P1**（生产上线前升 P0；文档 U-B 自标 P1） | 开 8883 + 收回明文 1883 到回环/限来源，作为「生产发布门禁」一项 | 需证书与对外端口放行；EMQX 容器内已显式 disable，改动面小 |
| 上报失败重试（A9） | 上报失败即丢弃，断档被算长；EMQX/MQ 持久化通道（access 出口侧）未实施（`IOT-ROADMAP.md:474`） | 阿里规则引擎：重试 1s/3s/10s 三次后丢弃并记错误、消息 ID 去重；AMQP 服务端订阅消费组（help.aliyun.com iot overview-4） | **P1** | access 出口加 MQ 持久化通道 + 带 requestId 去重的指数退避重试（复用告警侧退避模型） | 依赖 EMQX 数据桥/MQ 选型，中成本 |
| value 特殊字符 | 值含双引号/反斜杠未 JSON 转义 → 400（非静默丢，`EMQX-INTEGRATION.md` U-A4） | EMQX 规则引擎 SELECT 变换可做字段级转义；ThingsBoard 载荷 JSON 原生处理 | **P1** | 上报侧接入 `json_encode` 转义（文档已标 P1 用此方案） | 低；单点位契约内小改 |
| 批量上报（U-A7/U24） | 契约 P0=单点位一条消息；多点位批量「当前无落地计划」（`EMQX-INGRESS-DESIGN.md:523,527-530`） | ThingsBoard MQTT 原生支持一条消息多设备多点位 + 时间戳分离语义 | **P2** | 兼容单条消息多点位（数组 payload），向后兼容单点位 | 中；需与 U24 实测联动 |
| 凭据对账（U-A9） | 台账↔EMQX 差集对账未做；吊销时 EMQX 不可达留残留账号（`EMQX-INTEGRATION.md` U-A9） | 华为/阿里凭据吊销即踢会话、控制面闭环；本仓 `DEVICE-CREDENTIAL.md` 已用 P0-A-3 落地对账任务主体 | **P1** | 补定时对账任务（台账↔EMQX 差集），吊销时标记残留 | 中；凭据生命周期已就绪，仅差对账调度 |

### B. 设备管理

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 设备「全部停用」 | **无写入口**：`IotDeviceReq` 无 `status` 字段，只能靠删设备/删映射触发（已抽验 `IOT-ROADMAP.md:404` G7′＝未做） | 三家均把设备启用/停用作为台账基本操作（阿里设备管理/华为设备状态页） | **P0** | `IotDeviceReq` 加 `status` 字段 + 端点 + 前端开关，联动 `DeviceSpecServiceImpl` 过滤 | 低；后端字段+端点+一页开关 |
| 批量注册/量产 | **无规格**：表清单无批量相关（`IOT-PLATFORM-DESIGN.md:746-763`）；现状仅有单条创建 | 阿里：CSV 模板批量 ≤1 万、批次管理、错误码 460/6251…、量产 CSV 下载（help.aliyun.com create-multiple-devices）；ThingsBoard：CSV 批量预置名单（device-provisioning）；华为：批量绑定解绑 ≤100 | **P1** | 两张表（批次+导入记录）+ CSV 解析 + 错误行下载重传 | 中；无新依赖，实现面小，量产刚需 |
| 设备详情丰富度 | 前端单屏台账列表（`views/iot/devices/index.vue`），无设备详情多区块 | 华为设备详情=影子+消息跟踪+群组+标签多页签；阿里设备详情 10 区块 | **P2** | 详情页左侧信息+右侧图数据/在线调试页签 | 中；复用已有 detail.vue 模块 |
| 产品级点位模板（G4） | 未做（探针 `point-template` 无命中）；产品创建无模板/品类库 | 涂鸦：选品类→平台预置标准 DP（必选不可删）；ThingsBoard Device Profile 集中默认值；华为导入库模型 | **P1** | 内置基础品类模板（温湿度/开关等）+ 创建产品时可选并预置点位 | 中；纯前端+种子数据，UX 已有线框（`IOT-UX-PROPOSAL.md:671`） |
| 动态分组 | 仅静态分组+标签（`IOT-PLATFORM-DESIGN.md:375-382`），动态分组未设计 | 华为动态群组（类 SQL，规则创建后不可改）；阿里静态/动态分组 | **P2** | 分组条件化（按属性筛选），复用现有 groups 页 | 中；需后端条件查询支持 |
| 详情字段 productName/groupName | `IotDeviceResp` 仅 `productId`（探针 grep 无命中；M-1 G8 未做） | 三家设备列表/详情均展示所属产品名 | **P1** | 响应加 `productName/groupName` 冗余字段 | 低；纯 DTO 补字段 |

### C. 物模型

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| TSL 批量导入/diff | 单文件导入导出+全字段校验已落地（`IOT-PLATFORM-DESIGN.md:291-332`）；zip 批量、发布前 diff 缺（`IOT-UX-PROPOSAL.md:172`） | 阿里：单个 512KB/zip ≤2.5MB 多产品模型 + 「View Differences」 | **P2** | 导入支持 zip；发布前差异对比视图 | 中；TSL 校验已存在 |
| 编解码插件 | `data_format=json/binary` 是**产品级占位、未参与取值路径**（已抽验 `VALUE-DECODE-DESIGN.md:52`） | 阿里/华为编解码插件为一等能力（华为脚本化 `function invalid()` 解码）；配置化=本仓远期 M-7 | **P2** | 保持占位；进 M-7 立项时做「协议内嵌解析规则」 | 远期；文档已明示不承诺 |
| Modbus 位宽/符号/位域 | 缺 `word_count/bit_offset/signed` 列（SQL 已核实无）；16/32 位、大小端、hex/JSON 解码均未做；无真实 Modbus/OPC UA 设备（`IOT-ROADMAP.md:911-914`、`VALUE-DECODE-DESIGN.md:48-49,106-118`） | ThingsBoard IoT Gateway 原生 Modbus/OPC-UA/BACnet；华为/阿里标准协议支持 | **P1** | 映射表加位宽/符号/位域列 + 解码器扩展；接入 1-2 台真实设备验收 | **高**：依赖真实设备与协议实测（文档四点二十一） |

### D. 告警

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 邮件通知生产可用 | 邮件渠道设计为「做」（复用 JavaMail，`ALERTING-DESIGN.md:385`）但**生产未配 SMTP ⇒ 邮件必 FAILED**（已抽验 `ALERTING-DESIGN.md:748,756`：「邮件未配置或配置不完整」如实失败） | 三家告警通知均为默认能力（阿里云监控报警、华为告警管理、ThingsBoard Email/SMS/Slack） | **P0** | 生产配 SMTP（host/username/from）+ 一次性送达验证；不配则前端明示「邮件渠道未启用」 | 低；纯配置+一次验收，但属生产门禁 |
| 告警中心完整模型 | 已有 level/active-ack-cleared/去重/抑制/手动关闭留痕（`ALERTING-DESIGN.md:376,691-703`），前端 alerts 页已上线（`views/iot/alerts/index.vue` + 测试） | ThingsBoard：四态（Active/Cleared × Ack/Unack）+ 五级严重度 + 指派 + 用户/系统双注释 + 传播 + 专用 widget | **P2** | 加「指派+评论」两列（模型已有字段可扩展） | 中；前后端各一屏 |
| 平台自告警/大盘（Q7/A12/C4） | 「指标未接大盘/告警」（`IOT-ROADMAP.md:476`）；access 侧指标暴露链路未接（`:413,552`）；`/actuator/metrics` 未真实进程读值（`ALERTING-DESIGN.md:699-711`）；无平台自身健康阈值 | EMQX 有独立系统告警页（Active/History+Webhook 通知）；阿里云监控资源级报警是一等能力 | **P1** | 接指标到大盘 + 3-5 条平台健康规则（评估器 lag/队列积压/投递失败率） | 中；复用已有 iot.alert.* 指标（生产实测已产出 rounds/triggered/notify.* 系列） |
| 告警验证边界 | 7 项未验证：真库行为仅 CI `-Pit`+生产演示、metrics 未真实读值、无分布式锁、量级未校准、DURATION 线性（`ALERTING-DESIGN.md:699-711`、`:721-726`） | 成熟平台发布前均有压测与多实例验证基线 | **P1** | 多实例部署测试 + 真实量级校准 + DURATION 查询压测 | 中；纯验证工程，无新功能 |

### E. 规则 / 可观测

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 规则可视化编排 | 声明式 JSON 条件-动作已落地（`IOT-PLATFORM-DESIGN.md:681-689`）；可视化=M-7 远期（`:676,809`） | ThingsBoard 规则链（7 类节点+Debug mode+Test 按钮）；EMQX Flow Designer；阿里 SQL 规则 | **P2** | 保持 JSON 编辑器，远期再上画布 | 远期；不承诺 |
| 消息跟踪/定位建议 | 无设备消息时序追踪；命令状态仅回执无下发历史（现状盘点 §1.2 M-3 例外；华为模式缺） | 华为：消息跟踪（≤10 台、可导出、失败项「定位建议」按钮）（support.huaweicloud.com iot_01_0030_0） | **P1** | 设备详情加「消息跟踪」页签：上行/下行/回执时间线 + 失败原因人话 | 中；后端需补统一的 trace 持久层，前端复用 debug 页组件 |
| 审计日志 | 仅表级审计字段（`IOT-PLATFORM-DESIGN.md:189-191`），无操作审计流水 | 华为「查看审计日志」独立页（iot_01_0030）；ThingsBoard 审计日志按实体掩码+UI/REST+ES sink | **P1** | 审计表 + 拦截器记录增删改（操作人/对象/时间），前端一页查询 | 中；通用中间件，无新依赖 |

### F. 开放能力

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 开放 API（M-5） | **未开始**：仓内 `grep openapi` 无命中；API Key 设计已有（哈希/轮换/限流，`IOT-PLATFORM-DESIGN.md:707-714`） | 阿里云端 API+SDK（Java/Python/…）；华为应用侧 API；ThingsBoard REST API 全开放 | **P1** | 按 M-5 里程碑启动：OpenAPI 规范 + API Key 鉴权接入现有端点 | 中高；M-5 整块里程碑 |
| OTA | 明确**非目标**（`IOT-PLATFORM-DESIGN.md:80`） | 阿里整包/差分/灰度/批次/成功率统计；华为 LwM2M/MQTT 软固件升级；ThingsBoard 固件+软件 OTA 三级指派 | **P2**（建议「远期候选」登记） | 从「非目标」单列「远期候选」，至少写入 roadmap 统一口径 | 远期；勿当缺口报 |
| 设备模拟器 | 无可视化模拟器（在线调试已做，M-3）；验收靠真实 socket e2e（`IOT-ROADMAP.md:213`） | 华为在线调试含应用模拟器+设备模拟器（每产品 1 虚拟设备）；ThingsBoard widget Function 随机数据源 | **P2** | 在线调试页内嵌「模拟上报」面板（发一条演示消息） | 中；前端为主 |

### G. 数据面

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| TTL/保留策略运维 | IoTDB 表级 TTL 90 天已落地（PR #34，`IOT-ROADMAP.md:855`）；手工触发清理端点、前置快照/审计未做（`:846-847`） | 阿里数据服务/时序生命周期管理为配置项 | **P2** | 加手工触发清理端点（带审计） | 低；TTL 已兜底 |
| 四点二十 5 项验证 | TTL 越界语义/`start-cli.sh -e` 退出码/compose healthy/`quality=null` 落库/前端浏览器渲染均未验证（`IOT-ROADMAP.md:868-869`） | 成熟平台发布前逐项验收 | **P1** | 5 项作为发布检查清单逐项实测回写 | 低；纯验证 |
| 坐标碰撞（四点十七） | `PointMappingIndex` 仅计数告警；ts 落后无护栏；「主键形态 field=0」是未部署 access 的结果而非兼容性证明（`IOT-ROADMAP.md:722-723,731-740,751`） | 三家点位唯一性均有显式约束 | **P2** | 部署 access 后按四点十七处理读侧兼容 | 待 access 部署；当前不触发 |
| 覆盖阈值/链路原因码（A4/A5） | 未做（后续增量，`IOT-ROADMAP.md:482-485`） | ThingsBoard 告警按设备覆盖可用率是标配 | **P2** | 断档可用率加「按设备覆盖阈值」 | 中；后续增量 |

### H. 安全（以下为高危面，均以现状登记为准）

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 身份伪造（SF-5） | `IdentityHeaderFilter` 不校验 `X-Gateway-Signed`，直连下游可伪造身份（高/安全，`STARTER-FEEDBACK.md:8-14`）；本仓临时处置仅部署配置侧（`IOT-ROADMAP.md:782-783`） | 成熟平台网关签名校验为安全基线 | **P0** | 本仓入口侧加校验（不依赖上游 3.5.1）或等上游修复后升级 | 上游依赖（目标 3.5.1）；**安全高危，不阻塞也应优先** |
| identity 模式登录（SF-4） | identity 模式 auth 登录结构性失败（高/可用性）；本仓以 `identity.enabled=false` 绕过，单机可用 | — | **P1** | 随 starter 3.5.1 升级后复测 | 上游依赖 |
| `/actuator/**` 无权限码（UP-5） | 上游未修（`UPSTREAM-FEEDBACK-2026-09-27.md:371-443`），网关可达无权限码残余边界已登记（`DEPLOY-BACKEND.md:174-180`） | — | **P1** | 网关加权限码过滤 | 低；本仓可独立做 |
| Nacos 超管/匿名调用 | 服务仍用超管账号连 Nacos；全仓匿名调用点未扫描；开 auth 后需重验（`NACOS-AUTH.md:163,273-276`） | 中间件最小权限为合规基线 | **P1** | 扫描匿名调用点 + 收紧为专用账号 | 中；运维面 |

### I. 发布 / M-6

| 能力域 | 我们现状与证据 | 成熟平台做法 | 等级 | 建议实现形态 | 依赖/成本提示 |
|---|---|---|---|---|---|
| 一键安装指向 | `install.sh` 硬编码 `$ROOT/ypbin-admin/deploy/nacos` ⇒ **部署的是 upstream 不是本仓**（已抽验 `IOT-ROADMAP.md:116-119`）；「30 分钟起起来」未验收 | — | **P1**（发布门禁，需拍板目录名） | 拍板目录名（admin/iot）后改 `NACOS_DIR` 并端到端验收 | 低；唯一阻塞是命名决策 |
| README/许可 | README 仍为 admin 原版「🛡️ ypbin-admin」无 IoT 章节（`README.md:3-5`）；许可声明未做（`IOT-PLATFORM-DESIGN.md:808`） | 成熟开源项目 README=上手入口 | **P1** | M-6：重写 README（IoT 章节+快速开始） + 许可证文件 | 低；纯文档 |
| 旧仓归档（收尾） | ypbin-iot-cloud 归档/文档迁移**无完成记录**（`IOT-ROADMAP.md:97-100`；本分析亦未能核实） | — | **未核实** | 先核实归档状态再决定 | 未核实，不得推断 |
| i18n 键盲区 | `page.iot.credential.*` 键前端未补（`EMQX-INTEGRATION.md:344-352`）；补丁 PR #27 已修 `page.iot.debug.get`，证明该类键会显示原始 key | — | **P1**（低成本小修） | 补全 i18n 键 + 用「菜单标题=键」自检扫描 | 低 |

---

## 5. 傻瓜式易用性改进清单

> 市场依据：ThingsBoard「仅 Name 必填+默认值闭环」、CSV 预置、Basic/Advanced 双档、可定制空态文案（thingsboard.io docs，2026-09-28）；涂鸦 5 分钟快速入门+品类必选功能（developer.tuya.com，2026-09-28）；华为消息跟踪「定位建议」+审计（support.huaweicloud.com）；阿里 CSV 模板+错误码（help.aliyun.com）。我方 UX 提案已有线框未落地（`IOT-UX-PROPOSAL.md:671,444-461`）。

| # | 改什么（落地到具体页面） | 为什么（市场证据→我方缺口） | 优先级 |
|---|---|---|---|
| 1 | **引导式创建**：onboarding 页做成 4 步编号向导（选品类→功能→接入→体验），承接 `views/iot/onboarding/index.vue` | 涂鸦「5 分钟快速入门」编号 01/02/03 是上手标杆；我方 UX 诊断「用户不知道从哪下手」（`IOT-UX-PROPOSAL.md:444-461`）且线框未落地 | **P1** |
| 2 | **品类模板+必选功能锁定**：products 创建表单内置 2-4 个基础品类（温湿度/开关/网关），预置关键点位且必选不可删 | 涂鸦「选品类→标准功能预置必选」+ ThingsBoard Device Profile 集中默认值；我方无模板（DESIGN:737 仅手工编辑） | **P1** |
| 3 | **默认值闭环**：devices/form.vue 与 products 表单除 Name 外全部可空，默认规则链/传输/点位周期由产品级继承 | ThingsBoard「仅 Name 必填」；我方表单堆叠（UX:3.1-3.4 诊断） | **P1** |
| 4 | **CSV 批量导入**：devices 列表页「批量导入」入口（模板下载+错误行下载重传） | 阿里批次管理（错误码 460/6251…）；ThingsBoard CSV 预置与「预置名单」双策略 | **P1** |
| 5 | **搜索/筛选增强**：各列表页（设备/告警/租约）顶部加状态+时间+关键字组合筛选并**持久化到 URL** | 华为消息跟踪按状态/类型/时间过滤；我方仅有基础筛选 | **P1** |
| 6 | **错误提示人话**：错误码字典（后端错误码→中文解释+修复建议），关键失败显示「定位建议」式按钮 | 华为「定位建议」按钮；阿里错误码表；我方错误处理照搬后端 message（EMQX-INTEGRATION.md:344-352 已见「显示原始 key」类门面问题） | **P1** |
| 7 | **空态行动指引**：设备无数据/告警空列表显示「还没收到数据？检查设备连接或时间范围」式文案+操作按钮，代替裸「暂无数据」 | ThingsBoard 官方空态示例即行动指引（"check the device connection or time range"） | **P2** |
| 8 | **帮助内联**：每 iot 页右上角「帮助」链接到对应设计文档/操作说明章节 | ThingsBoard 每页文档链接+Getting Started；我方文档完善但入口藏在仓里 | **P2** |
| 9 | **权限分级可见性**：按租户/角色过滤可见设备与仪表盘数据（最小可见性：看仪表盘≠看数据） | ThingsBoard System→Tenant→Customer 分配制官方明文「数据还得单独授权」；我方 permission-rollout 文档存在但前端无体现 | **P1** |
| 10 | **配置面 Basic/Advanced 双档**：告警规则/点位映射表单折叠高级项（阈值时间段/抑制窗口等收进「高级」） | ThingsBoard widget Basic/Advanced 双档=一屏多用不同人 | **P2** |
| 11 | **「模拟上报」体验**：设备详情 detail-debug.vue 增加一键发送演示消息按钮 | 华为设备模拟器+ThingsBoard Function 随机数据源让「无设备也能看到效果」 | **P2** |
| 12 | **列表反馈即时化**：alerts 页已有刷新守卫（refresh-guard.ts），推广到 devices/tenant-ledger：失败保留旧数据+原始错误，不弹全局 2 秒提示 | PR #28 已证明轮询失败弹全局提示是事故（`IOT-ROADMAP.md:17`）；成熟平台均为静默刷新+可见状态 | **P1** |

---

## 6. 建议下一批实施顺序（收益/成本，前 10）

| # | 事项 | 等级 | 为什么先做 / 说明 |
|---|---|---|---|
| 1 | **生产 SMTP 配置+送达验收** | P0 | 纯配置，立即消除「每封告警邮件必败」 |
| 2 | **设备「全部停用」写入口** | P0 | 加 `status` 字段+端点+前端开关，半天量级 |
| 3 | **i18n 键补全+菜单键自检扫描** | P1 | 低成本门面修复，防「显示原始 key」 |
| 4 | **SF-5 身份签名校验** | **P0 安全** | 本仓入口侧加固（不阻塞等上游 3.5.1） |
| 5 | **CSV 批量注册+批次管理** | P1 | 两张表+CSV 解析，量产刚需、无新依赖 |
| 6 | **README/许可证重写（M-6）+ install.sh 目录拍板** | P1 发布门禁 | 纯文档+一处变量 |
| 7 | **消息跟踪+「定位建议」** | P1 | 复用 debug 页组件与已有 trace 数据，排障体验提升最大 |
| 8 | **TLS 8883+收回明文 1883** | P1 生产门禁 | EMQX 配置为主，与 1 同属生产发布清单 |
| 9 | **平台自告警+指标大盘（Q7/A12/C4）** | P1 | 复用已产出的 `iot.alert.*`/`iot.access.*` 指标，接大盘+3-5 条健康规则 |
| 10 | **开放 API（M-5 起步）** | P1 | OpenAPI 规范+API Key 鉴权挂接现有端点，为第三方接入铺路 |

---

## 7. 需要用户拍板的决策点

| # | 决策点 | 背景（证据） | 选项 | 影响 |
|---|---|---|---|---|
| 1 | **`install.sh` 部署目录名** | 硬编码 `NACOS_DIR="$ROOT/ypbin-admin/deploy/nacos"` ⇒ 部署的是 upstream（已抽验 `IOT-ROADMAP.md:116-119`） | A. 继续叫 `ypbin-admin`（改动最小、兼容现有运维）／B. 改名 `ypbin-iot`（语义正确、路径/文档/运维习惯全改） | 阻塞 M-6 一键安装指向唯一决策 |
| 2 | **M6 整窗维护三态** | 待拍板（`IOT-ROADMAP.md:597-601`） | 按维护窗口的三态语义定案 | 影响维护窗口功能规格 |
| 3 | **明文 1883 何时收回** | 开发测试期已开放并验收（commit `c083b93`；`EMQX-DEPLOY.md` §10 U-A「生产前必须收起或改 TLS 8883+限来源+限流」；H3「无 TLS 不得裸暴露」未推翻） | A. 立即收回／B. 与「生产首发」同批做（开 8883 才收 1883） | 决定生产发布门禁日期 |
| 4 | **OTA 是否升「远期候选」** | 现为「非目标」（`IOT-PLATFORM-DESIGN.md:80`），但三家均有 OTA 且为对外口径常见问题 | A. 保持非目标／B. 单列「远期候选」写入 roadmap | 统一对外口径，防引用方误报 |
| 5 | **四点二十一 Modbus/OPC UA 真实设备立项** | 需真实设备+协议实测，高成本（`IOT-ROADMAP.md:911-914`） | A. 立项（1-2 台设备验收）／B. 维持文档登记不启动 | 决定 P1 项是否排期 |
| 6 | **多租户/多节点扩展时点** | R8-2/4/5/6/7/8、U-A13 幂等非原子均标注「现阶段不触发」（`IOT-ROADMAP.md:406-414`） | A. 现阶段不做，扩展前按序做／B. 提前立项 | 决定 R8 系列排期 |

---

## 8. 未尽事项（未核实项，不得当既定事实引用）

> R1：以下均核不到、不得推断充数，逐项核实后再闭环。

| # | 未核实项 | 出处 | 核实途径建议 |
|---|---|---|---|
| 1 | 旧仓 ypbin-iot-cloud 归档/文档迁移完成状态 | `IOT-ROADMAP.md:97-100` | 查旧仓状态与迁移记录 |
| 2 | EMQX 集群内置库复制语义 / U11-U13 | `EMQX-DEPLOY.md` §10 | 集群部署实测 |
| 3 | OSS bootstrap_file（U18） | `EMQX-DEPLOY.md` §10 | 查 OSS 配置与文件 |
| 4 | 树模型拒裸聚合的成立性 | `IOT-PLATFORM-DESIGN.md` | 对照设计口径实测 |
| 5 | insertTablet 性能 | 现状盘点 §1 | 基准测试 |
| 6 | OPC UA ByteString 路径语义 | `VALUE-DECODE-DESIGN.md:84` | 协议实测 |
| 7 | `IOTDB_JMX_OPTS` 生效路径 | `DEPLOY-TIMESERIES.md:411` | 容器内验证生效路径 |
| 8 | 「主键形态 field=0 兼容性」实为未部署 access 的推论 | `IOT-ROADMAP.md:751` | 部署 access 后验证 |
| 9 | ROADMAP 顶部状态块日期（2026-10-02 为未来日期）与 EMQX-DEPLOY §10 U-C「未做」的口径冲突 | `IOT-ROADMAP.md` 顶部；`EMQX-DEPLOY.md` §10 | 段 A 已由 git `398ddcb` 证实完成；回写 U-C 行并修正 ROADMAP 日期 |

---

## 附：引用文档索引（本报告涉及的仓内文档）

| 文档 | 用途 |
|---|---|
| `IOT-PLATFORM-DESIGN.md` | 平台完整设计总纲（物模型/规则/里程碑 M-1~M-7/API Key 设计） |
| `IOT-ROADMAP.md` | 增量路线 + 每一增量验收证据（六处关键证据抽验所在） |
| `IOT-UX-PROPOSAL.md` | UX 诊断与线框（易用性改进口径来源） |
| `EMQX-INTEGRATION.md` / `EMQX-INGRESS-DESIGN.md` / `EMQX-DEPLOY.md` | 接入契约、入站设计与部署/未验证项（U-A~U-G） |
| `ALERTING-DESIGN.md` | 告警设计 + 生产实测证据（SMTP 必败/链路跑通） |
| `VALUE-DECODE-DESIGN.md` | 编解码占位（`data_format` 未参与取值路径） |
| `STARTER-FEEDBACK.md` / `UPSTREAM-FEEDBACK-2026-09-27.md` / `NACOS-AUTH.md` | 上游缺陷与安全反哺（SF-4/5、UP-1~12） |
| `DEPLOY-*.md` / `DEVICE-CREDENTIAL.md` / `PROD-OPS-NOTES.md` / `DEPLOY-CREDENTIAL-HYGIENE.md` / `DEPLOY-TIMESERIES.md` | 部署、凭据对账与运维登记 |
| `ypbin-iot-ui`（`views/iot/*`） | 前端七个页面目录（devices/groups/maintenance/onboarding/products/alerts/tenant-ledger） |

---
*合规声明：本文为纯新增文件，未修改其它任何文件、未 commit、未 push；本文外部结论均转引自市场研究子代理的一手官方抓取（URL+访问日期 2026-09-28 见 §4 表头与 §5 表头），未新增任何未经出处的对外事实；「未核实」条目均加粗标注。*