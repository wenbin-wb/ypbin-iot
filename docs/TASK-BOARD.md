# ypbin-iot 任务看板（TASK-BOARD）

> **用途**：跨会话的进度台账，防止遗忘。每完成一项就更新本文件的状态列。
> **建立日期**：2026-09-28
> **依据**：`docs/PLATFORM-GAP-REPORT-2026-09-28.md`（三方对照：现状 × 计划承诺 × 成熟平台做法）
> **状态口径**：⬜ 待办 ｜ 🔄 进行中 ｜ ✅ 已完成（附证据）｜ ⏸ 等待外部输入 ｜ ➖ 刻意不做

---

## 0. 工作方式（用户指示，2026-09-28）

1. **复核节奏**：不再"每小段一复核"，改为**每完成一个大功能复核一次**（省时间与 token）。自验 + CI 为日常门禁；**安全类（L3）与生产发布仍保留一次外委独立复核**，但不做逐轮重复复核。
2. **任务列表落盘**：本文件为唯一进度台账；会话内 todo 仅作当前批次提示，以本文件为准。
3. **用户已拍板**：
   - `install.sh` 目录名：**维持 `ypbin-admin`**（A 方案，改动最小）；
   - 明文 1883 收回：**与 TLS 8883 同批做**（生产发布门禁）；
   - OTA：**登记为"远期候选"**（从"非目标"升级表述，统一对外口径）；
   - Modbus/OPC UA 真实设备：**暂不立项**，维持文档登记；
   - 多租户/多节点扩展（R8 系/U-A13）：**扩展前再做**，现阶段不做；
   - M6 整窗维护三态：**按文档提案定案**。

---

## 1. 第一批（已完成，2026-09-29）

| # | 任务 | 等级 | 状态 | 证据/备注 |
|---|---|---|---|---|
| 1 | 设备「全部停用」写入口 | P0/L2 | ✅ | `IotDeviceReq.status`（与实体/DB 同名字段 `Integer`，不做改名映射）+ 独立端点 `PUT /devices/{id}/status/{status}`（复用 `iot:device:update`）+ 前端台账启停开关（Popconfirm 二次确认）+ 详情页状态行。联动：`DeviceSpecServiceImpl` 只下发 `status=EntityStatus.ENABLED` 的设备（取值由裸字面量 `1` 改为枚举）。**门禁**：`mvn -o -pl ypbin-service/ypbin-iot -am clean test` **620/0/0**；架构门禁 **48/0**（与基线一致）。**变异验证**：注释掉 `status` 过滤 ⇒ 620 中 **2 条转红**（`specQueryMustFilterByEnabledStatusCode`、`disabledDeviceMustBeFilteredOutOfSpecDelivery`）；前端翻转启用判据 ⇒ `status-toggle.test.ts` 4/5 转红。**生产验证**：见变更记录（规格下发 12 → 11 → 12 实测） |
| 2 | i18n 键补全 + 菜单标题键自检 | P1/L1 | ✅ | 新增 `scripts/check-menu-i18n-keys.mjs`：扫后端 SQL 里全部 `'page.*'` 菜单标题键 ↔ zh-CN/en-US 语言包，缺则非零退出，且**读不到目录 / 0 个键时显式失败**（防空跑假绿）。**落地前实测缺 11 个**（10 个 `page.ai.*` + `page.iot.alert.rule.list`，全是 `type='button'` 的权限按钮标题 ⇒ 会在权限分配界面渲染原始 key）；补齐后 **56/56 全解析**。注意键按**字面路径**落位（`page.ai.knowledge.list` → `page.json` 的 `ai.knowledge.list`），**不是** `ai.permission.*` 那套驼峰键。三条 i18n 门禁全绿：键存在性（61 文件）/ 菜单标题（56 键）/ 文案编译（44 语言包 9498 条） |
| 3 | README/许可证重写（M-6）+ install.sh 目录名 | P1/L1 | ✅ | `README.md` 由 admin 原版**重写为 IoT 版**（是什么 / 文字架构图 / 快速开始（一键 + 手工部署指向 `docs/DEPLOY-*.md`）/ 已实现能力（含「未做」如实登记）/ 目录结构 / 许可 / 文档索引；README 内全部本地链接已逐一核实存在）。**许可**：核实 upstream `LICENSE` 与 canonical Apache-2.0 **逐字节一致**（含 `END OF TERMS AND CONDITIONS`），故**不新增文件**（再添一份等于重复），改为在 README 说明 fork 关系、改动范围与「`LICENSE-USAGE.md` 不是本项目许可」。`install.sh`：**`NACOS_DIR` 目录名不变**（A 方案），加**回退**（`ypbin-admin/…` → `ypbin-iot/…`）+ **两候选都不存在即 die**（消除「静默跳过配置导入」）；循环内模板缺失改为**逐条 warn**（不再无声跳过）。三种场景用最小脚本实测通过（fork 检出 / 两目录并存取前者 / 都不存在则非零退出） |
| 4 | 文档矛盾修复 | L0 | ✅ | ① ROADMAP 顶部 `2026-10-02`（未来日期）→ `2026-09-29`；② 同块「真设备 1883 未做」回写为「开发测试期已开放（`c083b93`）、**TLS/8883/限来源/限流未做**」；③ `EMQX-DEPLOY.md` §10 **U-C/U-D** 回写为已完成（段 A PR #81 / 段 B，指向 `EMQX-INTEGRATION.md`）；④ 前端 3 处「暂无菜单」注释（`onboarding/index.vue`、`tenant-ledger/index.vue`、`devices/index.vue`）改为「菜单已由迁移补齐（**3205/3206/3200**）」；⑤ `ALERTING-DESIGN.md` 段 C3 标注为**已落地**（`ypbin-iot-ui`），并把「本平台目前完全没有告警能力」标为**立项时**的历史陈述。附带：ROADMAP 的 `G7′` 行回写为已处置 |

## 2. 待办（按建议顺序）

| # | 任务 | 等级 | 状态 | 备注 |
|---|---|---|---|---|
| 5 | 生产 SMTP 配置 + 送达验收 | P0 | ✅ **已完成** | **邮件真的能发出去**：走平台自身发送链路，`EMAIL` 投递行到达 **`SENT`**（`attempt=1`、`last_error=NULL`），同轮 `INBOX` 对照行亦 `SENT`（FIRING 与 RESOLVED 两个事件面各一条）；`ypbin-iot` 日志「到期 2、成功 2、失败待重试 0、放弃 0」。<br>**★ 用户已确认收件箱收到告警邮件**（2026-09-30，用户亲自确认）⇒ **送达闭环，不再有「平台无法证明」的未验证项**。<br>**配置面**：邮件参数在 `sys_config` 的 **`mail` 组**（7 键，`MAIL_PORT=465` / `MAIL_SSL_ENABLED=true`）；变更经平台 UI（`系统管理 → 系统参数 → mail`）保存后 `AFTER_COMMIT → refreshCache` **即时生效，未重启任何容器**。**UI 路径与两条生效路径**（UI 改参即时生效 / `POST /system/mail/test` 测试按钮，权限码 `system:mail:test`）已登记 `deploy/PROD-OPS-NOTES.md` **§10**。<br>**收尾**：遗留测试规则 `x`（`notify_targets=NULL`，此前每 10 分钟刷一条 `GIVEN_UP`）已**停用**（`enabled=0`，未删除，可一键回滚）。<br>**过程留痕**：曾因 QQ `535` 授权码问题阻断，用户更换授权码后复测通过（详见变更记录 2026-09-29 / 2026-09-30 两行）。 |
| 6 | SF-5 入口侧身份签名校验 | P0 安全 | ✅ | **上游已修，本仓补纵深防御**（PR #102 → main `16ffc7ce`）。<br>**一手核实**：starter **3.6.0 已修 SF-5**（上游 issue #53 由 `3927366d` 关闭、随 v3.6.0 发布）：`IdentityHeaderFilter#isFromTrustedSource` 校验 `X-Gateway-Signed`，缺失/不匹配即拒绝（fail-closed）；本仓 `8a3ba56` 已升到 3.6.0 ⇒ **入口侧校验已随制品生效**，无需等 3.5.1（该目标版本实际由 3.6.0 承载）。3.5.0 制品无此逻辑（两个 sources jar diff 已证）。<br>**本仓补的是另一件事**：`ypbin.gateway.header-sanitize.headers` 默认表（jar 内 `GatewayProperties$HeaderSanitize:180-181`）**不含 `X-Gateway-Signed`**，客户端可自带该头穿透；已在 `deploy/nacos/ypbin-gateway.yaml` 显式并入（该键是**整体覆盖**语义，默认 5 头已列全并由门禁断言）。<br>**生产实测（直连 18084 绕过网关）**：伪造 `X-User-Id`+`X-Tenant-Id` 不带签名 ⇒ `code:403「非法身份来源」`；错签名 ⇒ 同样 403；且下游 `WARN ... 来源标记缺失或不匹配，拒绝建立身份`**明确记日志（不静默）**。正常链路 19000 `/`、`/api/*` 全 200，下游误拒计数 **0**；采集链路 `iot.timeseries.write.rows` 810→988 持续增长。<br>**独立复核**：#102 复核发现「`header-sanitize.enabled=false` 可整块关掉清洗且门禁漏检」⇒ #103 补断言（shell + Java 双门禁，变异 V5 已转红）。 |
| **6b** | **【部署阻塞面】starter 3.6.0 的 identity fail-closed 与仓内配置不匹配** | **P0 部署** | ✅ **仓内已修 + 白名单已登记 + 生产已验证不退化** | **现象**（2026-09-29 首次真正部署 `8a3ba56` 后的制品时暴露）：`deploy/nacos/ypbin-common.yaml` 显式 `ypbin.security.identity.enabled: true`，而 starter **3.6.0 起**该组合**强制**要求 `ypbin.security.identity.trusted-source-token`（jar 内 `IdentityAutoConfiguration` 在为空时抛 `IllegalStateException`），否则**启动即失败**。仓内**只有**旧的 `ypbin.cloud.feign.trusted-source-token`（Feign 出站透传用）——**命名空间不同，不满足该校验** ⇒ 任何用本仓配置 + 3.6.0 制品构建的服务都起不来。<br>**为什么直到部署才暴露**：本仓升到 3.6.0 后**从未真正部署过**。<br>**✅ 闭环（PR #102 → main `16ffc7ce`）**：① 仓内补 `ypbin.security.identity.trusted-source-token: ${GATEWAY_SIGN_TOKEN}`（**占位符，真值不入库**）；② 白名单 **18 → 19**（`sync-whitelist.yml` 的 `ALLOWED` 与 `SYNC.md` §2 **两处同步**），理由「问题就在**这一行**：`enabled=true` 已写在既有文件里，新文件无法表达『给既有键补兄弟键』」+ 记账「多一个 merge 冲突点」+ 注明**更好的长期做法是改上游 admin 仓**（已开 **ypbin-admin#78**）；③ 回归门禁**双入口**：`tools/check-identity-config.sh`（CI，解析**渲染后** YAML）+ `NacosIdentityConfigTest`（本机 `mvn test`）。<br>**变异验证 8/8 全部转红**（删键 / 键被注释 / 键名拼错 / 挂错旧命名空间 / 两侧不同串 / 清洗表漏头×2 / 顶层重复键）；Java 门禁删键后 3 用例转红。<br>**生产验证（不再退化）**：按 `install.sh` 的 sed 口径渲染后发布到 live Nacos ⇒ 两侧 `trusted-source-token` **同指纹 `60d359ccf23b`（len=64）**；网关重启后日志 `Refresh keys changed: [ypbin.gateway.header-sanitize.headers[0..5]...]` 证明绑定生效；`ypbin-iot` 采集链路 `write.rows` 810→988 增长。<br>**回滚物（2026-09-29 收尾批复核后更正）**：`/root/nacos-backup/ypbin-common.yaml.20260929-051006`（改动前 live Nacos 快照）。<br>⚠️ **更正**：此处原写 `/root/deploy-backup-6b/20260929-053946/`，经收尾批**全盘 find 实测不存在**，属上一批登记错误，已按实际存在的回滚物更正。jar 侧回滚物为上一批 `247ef292…`；**本批未改 Java 代码，jar 无需回滚**。 |
| 7 | CSV 批量注册 + 批次管理（两张表 + CSV 解析 + 错误行下载重传） | P1 | ⬜ | 对标阿里批次管理 |
| 8 | 消息跟踪 + 「定位建议」（设备详情页签，复用 debug 组件） | P1 | ⬜ | 对标华为消息跟踪 |
| 9 | TLS 8883 开放 + 收回明文 1883（+限来源） | P1→P0 发布门禁 | ⬜ | 与 #5 同批 |
| 10 | 平台自告警 + 指标大盘（Q7/A12/C4，复用 `iot.alert.*`） | P1 | ⬜ | 有现成指标 |
| 11 | 开放 API（M-5 起步：OpenAPI 规范 + API Key 鉴权挂接现有端点） | P1 | ⬜ | 整块里程碑 |
| 12 | 傻瓜式易用性批：4 步向导 / 品类模板+必选功能 / 默认值闭环 / CSV 导入 / 筛选持久化 / 错误人话+定位建议 | P1 | ⬜ | 报告 §5 |
| 13 | 前端死能力接线：设备凭据、设备标签、影子写入、点位映射增删改（后端已就绪、前端 0 引用） | P1 | ⬜ | 报告 §3 A 类 |
| 14 | **告警规则未配通知对象 ⇒ 目前只落 `GIVEN_UP`（`last_error` 写明原因，但只在 DB 里、运维不会主动看）** | P1 | 🔄 **本批已加 WARN 日志 + TODO 标记（部分完成）** | **现象（真实事故）**：遗留规则 `x`（`notify_targets=NULL`）+ 兜底 `ypbin.alert.notify-fallback-emails=[]` ⇒ **每 10 分钟静默产生一条 `GIVEN_UP`**，`target=""`、`attempt=maxAttempt`、`nextRetryTs=null`，平台侧**只有 DB 里有 `last_error`**，无人知晓 ✗。<br>**本批落地（用户 2026-09-30 决定：先日志 + TODO）**：① 在「不可投递 ⇒ `GIVEN_UP`」同一路径加**一条 WARN**（含租户 id / 规则 id·名称 / 设备 id / 渠道 / **原因码 `NO_RECIPIENT`** / 可执行指引「请在规则的『通知对象』里填写收件人；或联系平台管理员配置 `ypbin.alert.notify-fallback-emails / -inbox-user-ids`」），使其在 `docker logs ypbin-iot` 直接可见；② **同一处**加 `// TODO(board #14)` 注释指向本条；③ **保持既有行为不变**（仍落 `GIVEN_UP`、仍不重试、`FAILED` 退避路径不受影响）。<br>**刻意未做**（用户明确「先打印日志 + TODO」）✗：**不配兜底收件人配置**、**不做前端可见提示**。<br>**生产实测（2026-09-30，已部署并验证）**：PR #106 → squash 合并 main `f6b214a5`，CI **5/5 全绿**；制品三方一致 md5 `3aa4036755688fb119ad8507a726c2fe`（构建机 = 上传件 = 容器 `/app/app.jar`）。构造「无收件人」场景（演示规则临时 `notify_targets=NULL`，测后已还原）⇒ **`docker logs ypbin-iot` 实测出现新 WARN**：<br>`WARN ... AlertNotifyPlanner : [iot] 告警通知不可投递，已放弃（GIVEN_UP）：NO_RECIPIENT tenantId=1 ruleId=2104373415356145665 ruleName=演示-温度超上限 deviceId=9300012 channel=EMAIL instanceId=2104954170376466434 指引=请在规则的「通知对象」里填写收件人；或联系平台管理员配置 ypbin.alert.notify-fallback-emails / ypbin.alert.notify-fallback-inbox-user-ids`<br>同轮 DB 对照：`EMAIL` 行 `GIVEN_UP`（`target=""`、`attempt=4`、`next_retry_ts=NULL`、`last_error` 有值）⇒ **行为不变**；`INBOX` 行 `SENT`（回落规则创建者成功）⇒ 只对**真正解析不出收件人**的渠道告警，未误报 ✓。<br>**回滚物**：镜像 tag `ypbin/ypbin-iot:rollback-20260929-151550` + jar 备份 `/root/ypbin-iot-jar-rollback-20260929-151550.jar` + 一键回滚脚本 `/root/alertlog-backup/rollback-alertlog.sh`（旧 jar md5 `247ef292133f31fd8b98e47f01ed20f3`）。<br>**后续可做**：前端在规则「通知对象」为空时给出可见提示（保存时校验/列表页标红）；或按需配置平台兜底收件人。 |

## 3. 可选增强（P2，择机）

- 告警中心加「指派 + 评论」（对标 ThingsBoard 四态模型）；
- 设备详情多区块（对标华为/阿里详情页）；
- 动态分组（类 SQL 条件分组）；
- 空态行动指引文案、帮助内联入口、Basic/Advanced 双档表单、模拟上报面板；
- TSL zip 批量导入 + 发布前 diff；
- 数据保留手工触发端点、坐标碰撞/ts 护栏（部署 access 后）。

## 4. 刻意不做（防误报，非缺口）

计费/套餐/账单、SLA/工单、自研 Broker/时序库/组态编辑器、Webhook 通知（唯一 SSRF 面）、下行自动重试、设备侧离线排队、平台入站限流、双状态（Neuron 式）、树模型、可视化规则编排（M-7 远期）、AI 异常检测、复合条件告警。

## 5. 已知未核实项（不得当既定事实）

旧仓 `ypbin-iot-cloud` 归档状态、EMQX 集群内置库复制语义（U11-13）、OSS `bootstrap_file`（U18）、树模型拒裸聚合、`insertTablet` 性能、OPC UA `ByteString` 路径、`IOTDB_JMX_OPTS` 生效路径、「主键形态 field=0 兼容性」（实为未部署 access 的推论）。

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-28 | 建立看板；第一批 4 项开工；用户拍板 6 项决策；复核节奏调整为「大功能一次」 |
| 2026-09-29 | **第一批 4 项全部完成**（后端 PR #100 → main `d46301b0`；前端 PR #32 → main `519941d`）。后端门禁 620/0/0、架构 48/0、SQL 等价 OK；前端 i18n 三门禁 + typecheck + 164 用例全绿。两项变异验证：后端去掉 `status` 过滤 ⇒ 2 条转红；前端翻转启用判据 ⇒ 4/5 转红。<br>**生产验证（规格联动实测）**：对 `9300002 demo-dev-offline` 停用 + 推进 `config_epoch` ⇒ `GET /internal/device-specs/tenant?tenantId=1` 下发设备数 **12 → 11**（`9300002` 消失），access 侧随即停止对其的 ADD 重试；再启用 ⇒ **11 → 12**（恢复）。演示设备最终状态与部署前一致（13 台：12 启用 / 1 停用，`9300004` 本就 status=0）。<br>**生产部署**：后端 jar md5 `247ef29…`（构建机 = 上传件 = 容器内 `/app/app.jar` 三方一致），仅重建 `ypbin-iot`；前端 CI 产物 **432 个文件与服务器逐字节一致**，19000 → 200。<br>**顺带发现 #6b**（starter 3.6.0 identity fail-closed 与仓内配置不匹配，部署阻塞面，已临时处置并登记）。顺带回写 ROADMAP `G7′` 为已处置 |
| 2026-09-29 | **#6b + #6（SF-5）同批完成**（PR #102 → main `16ffc7ce`；复核整改 PR #103 → main `b38f8439`）。<br>**#6b 闭环**：仓内补 `ypbin.security.identity.trusted-source-token`（占位符，真值不入库）+ 白名单 18→19（两处同步）+ 双入口回归门禁（shell CI + Java）。**变异 8/8 全红**。生产按 `install.sh` 口径渲染发布，两侧同指纹 `60d359ccf23b`（len=64）⇒ **重跑 install.sh 不再退化**。<br>**#6 澄清一手事实**：starter **3.6.0 已修 SF-5**（issue #53 → `3927366d`，随 v3.6.0 发布），本仓 `8a3ba56` 已在 3.6.0 ⇒ 入口侧校验**已随制品生效**；本仓补的是网关清洗表未含 `X-Gateway-Signed` 的**纵深防御**缺口。<br>**生产实测**：直连 18084 伪造身份头 ⇒ `code:403「非法身份来源」`（缺签名与错签名各一次）+ 下游 WARN 明确记日志；正常链路 19000/`/api` 全 200、误拒 0；采集 `write.rows` 810→988 增长。<br>**独立复核（本批仅一次）**：发现「`header-sanitize.enabled=false` 可整块关掉清洗、双门禁均漏检」⇒ 最小整改 #103 补断言并复测转红；其余 5 个对抗变异均已转红。<br>**门禁**：iot 625/0/0/0（含新增 5 用例，原 624→625）、架构 **48/0**、SQL 等价 OK。**反哺上游**：ypbin-admin#78（模板缺键）+ 已核 starter#53 关闭。 |
| 2026-09-29 | **收尾/验证批（本批，PR #104 → main `f5ec2208`）**。上一批执行者被中途中止、未留回执，本批**盘点 → 补齐 → 验证**：<br>**① 盘点**：`ypbin-iot` main 实含 `16ffc7ce`(#102)/`b38f8439`(#103)；白名单**确为 19**（`sync-whitelist.yml` 注释+`ALLOWED` 与 `SYNC.md` §2 两处一致）。<br>**② 补收尾**：`docs/6b-board` 看板回写**走正常流程合并**（PR #104 → CI 6/6 全绿 → squash → main `f5ec2208`）；本地 `docs/6b-board` 已删（内容与 main 逐字一致）。<br>**③ 生产验证（本批核心，全部实测）**：<br>　• **#6b 闭环（加强证据）**：按 `install.sh` 口径渲染**仓内模板**所得配置 `ypbin.security.identity.trusted-source-token` = len 64 / 指纹 `60d359ccf23b`，与 **live Nacos 值**、`.env` 的 `GATEWAY_SIGN_TOKEN` **三方同指纹** ⇒ 重跑 `install.sh` 覆盖不回退（`env_key_backfill` 保证该键存在、sed 保证占位符被替换；非注释行**零残留占位符**）。<br>　• **SF-5 fail-closed**：直连 `18084`（绕过网关）伪造身份头 3 组 ⇒ 均 `code:403「非法身份来源：身份头缺少可信网关签名」`，下游 WARN 记日志（不静默）；正常链路 `19000/`、`19000/api/*`、`18084/actuator/health`（UP）全 200；`auth/system/access` 误拒计数 **0**（仅 iot 有 5 条，其中 3 条为本批自测、2 条为上一批自测）。<br>　• **平台未受影响**：`write.rows` 8865→8895（+30/60s）、`points.collected=8899`、`write.failed=0`；10 个关键容器全 Up、`RestartCount` 全 0。<br>　• **部署状态**：`ypbin-iot` 制品内含 **starter 3.6.0**（`ypbin-starter-security-3.6.0.jar`，含 `IdentityHeaderFilter`）⇒ SF-5 逻辑确在跑；#102/#103 **未改 Java 代码**（仅 yaml/shell/test）⇒ **无需重新部署 jar**，Nacos 配置已生效并已重启网关绑定。<br>**④ 门禁（本批实跑）**：`mvn -o -pl ypbin-service/ypbin-iot -am clean test` ⇒ **625 / 0 / 0 / 0**；架构模块 ⇒ **48 / 0**（与基线一致）。<br>**⑤ 更正登记**：#6b 行的回滚物路径原先记的 `/root/deploy-backup-6b/20260929-053946/` **实际不存在**（已全盘 find 确认），已改为真实存在的 `/root/nacos-backup/ypbin-common.yaml.20260929-051006`。<br>**⑥ 已知未核实/遗留（如实登记）**：网关容器跑的是 **starter 3.5.0** 制品（2026-09-25 构建，来自独立仓 `ypbin-gateway`）——`header-sanitize` 配置绑定已生效（日志 `Refresh keys changed`），但网关侧 3.6.0 代码级修复**未部署**；`Sync Whitelist` 的 `dry-run-merge` 因**落后 upstream/main 4 个提交**长期失败（非本批引入，历史各提交同样失败）。 |
| 2026-09-30 | **#5 结项 + 新增 #14（告警「无收件人」可观测性，本批）**。<br>**① #5 标 ✅**：**用户已确认收件箱收到告警邮件** ⇒ 送达闭环（此前「平台无法证明对方是否收到」的未验证项**已消除**）；邮件参数在 `sys_config` 的 `mail` 组，UI 路径与两条生效路径（UI 改参即时生效 / `POST /system/mail/test`）已登记 `deploy/PROD-OPS-NOTES.md` **§10**。<br>**② 新增 #14 并部分落地**：真实事故（遗留规则 `x` 未配收件人 + 平台兜底为空 ⇒ 每 10 分钟静默落一条 `GIVEN_UP`，**只有 DB 知道**）⇒ 在 `AlertNotifyPlanner` 的「不可投递 ⇒ `GIVEN_UP`」路径加 **WARN 日志**（租户/规则/设备/渠道/原因码 `NO_RECIPIENT`/可执行指引）+ 同处 `// TODO(board #14)` 标记。**保持既有行为不变**：仍落 `GIVEN_UP`、不重试、`FAILED` 退避路径不受影响。**用户明确「先日志 + TODO」⇒ 本批不配兜底收件人、不做前端提示** ✗。<br>**③ 用例与变异**：新增 2 用例（WARN 内容断言 + 行为不变断言）；**变异**（删掉该 WARN）⇒ 2 条**转红**（`Expected size: 2 but was: 0`）⇒ 断言真的会咬人。<br>**④ 门禁（本批实跑，隔离 worktree）**：`mvn -o -pl ypbin-service/ypbin-iot -am clean test` ⇒ **627 / 0 / 0 / 0**（基线 625，+2 新用例）；架构模块 ⇒ **48 / 0**（与基线一致）。<br>**⑤ R6 定级**：本批属 **L1/L2**（加日志 + 用例，不改接口/数据契约），按用户新口径**不派独立复核**，但证据齐备（用例 + 变异 + 门禁数字 + 生产实测日志 + 回滚物）。 |
| 2026-09-30 | **#14 生产部署与实测（本批，PR #106 → main `f6b214a5`；看板回写本 PR）**。<br>**① 合并**：CI **5/5 全绿**（构建与校验 / whitelist / CodeQL / 集成测试×2）→ squash 合并 → main **`f6b214a5`**。<br>**② 制品三方一致**：构建机 jar = 上传件 `/tmp/new.jar` = 容器内 `/app/app.jar`，md5 均为 **`3aa4036755688fb119ad8507a726c2fe`**；部署前容器为 `247ef292133f31fd8b98e47f01ed20f3`。<br>**③ 部署纪律**：本地构建 jar → scp 上传 → `install` 覆盖 → `docker compose build ypbin-iot` → `up -d --no-deps ypbin-iot`（**只动这一个容器**；部署前后其余 10 个容器 uptime/RestartCount 均未变）；启动 `Started IotServiceApplication in 28.841 seconds`、`Load config[ypbin-common.yaml] success`、`ERROR` 计数 **0**、健康 `UP`、`RestartCount=0`。<br>**④ 生产实测（核心验收，构造场景）**：演示规则 `演示-温度超上限`(`2104373415356145665`) 临时 `notify_targets=NULL`（**改前整行 600 备份 + 阈值 `100→20` 一并备份**）⇒ **`docker logs ypbin-iot` 出现新 WARN**（原文见 #14 行，含 `NO_RECIPIENT`/`tenantId=1`/`ruleId=...`/`ruleName=演示-温度超上限`/`deviceId=9300012`/`channel=EMAIL`/`instanceId=2104954170376466434`/可执行指引）。<br>**⑤ 行为不变（同轮 DB 对照）**：`EMAIL` 行 `GIVEN_UP`（`target=""`、`attempt=4`、`next_retry_ts=NULL`）；`INBOX` 行 `SENT`（回落创建者）⇒ **只对真正无收件人的渠道 WARN**，未误报。<br>**⑥ 恢复现场（已附证据）**：`notify_targets` 与 `threshold` 均已还原，**与改前备份逐列一致**（`update_time` 亦相同：规则 `2026-09-28 08:57:34`、点位 `2026-09-29 21:58:17`）；恢复后 90s 内 **`NO_RECIPIENT` 新增 0 条**（触发条件已消失，实例自动 `RESOLVED`）；采集链路 `iot.timeseries.write.rows` **132 → 147（+15/30s）** 持续增长 ⇒ 平台未被搞挂。<br>**⑦ 回滚物**：镜像 tag `ypbin/ypbin-iot:rollback-20260929-151550`、jar 备份 `/root/ypbin-iot-jar-rollback-20260929-151550.jar`、规则/点位改前备份 `/root/alertlog-backup/rule*-before-20260929-151841.tsv`、一键回滚脚本 `/root/alertlog-backup/rollback-alertlog.sh`。<br>**⑧ 并发纪律**：全程在独立 worktree `/home/wenbin/projects/ypbin-wt/alertlog` 开发与跑门禁，**未在共享工作区并发跑 Maven**（该区另有 CSV 导入批在跑）。<br>**⑨ 未验证项**：WARN 是否会被日志采集/告警系统进一步消费（本批只做「打印」，未接任何日志侧告警规则）✗。 |
