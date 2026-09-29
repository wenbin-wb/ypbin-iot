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
| 5 | 生产 SMTP 配置 + 送达验收 | P0 | ⏸ | **需用户提供 SMTP 凭据**（host/账号/密码/发件人） |
| 6 | SF-5 入口侧身份签名校验（本仓加固，不等上游 3.5.1） | P0 安全 | ⬜ | 保留一次外委复核 |
| **6b** | **【新发现·部署阻塞面】starter 3.6.0 的 identity fail-closed 与仓内配置不匹配** | **P0 部署** | ⚠️ **临时处置已生效，仓内未修** | **现象**（2026-09-29 首次真正部署 `8a3ba56` 后的制品时暴露）：`deploy/nacos/ypbin-common.yaml` 显式 `ypbin.security.identity.enabled: true`，而 starter **3.6.0 起**该组合**强制**要求 `ypbin.security.identity.trusted-source-token`（`IdentityProperties`，与网关 `ypbin.gateway.auth.trusted-source-token` 同串），否则**启动即失败**。仓内**只有**旧的 `ypbin.cloud.feign.trusted-source-token`（Feign 出站透传用）——**命名空间不同，不满足该校验** ⇒ 任何用本仓配置 + 3.6.0 制品构建的服务（auth/system/ai/iot）**都起不来**。<br>**为什么直到今天才暴露**：生产一直跑着升级前的旧 jar（starter 3.5.0，无此校验），`8a3ba56` 合并后**从未真正部署过**。<br>**临时处置（部署实例侧，已生效）**：live Nacos `ypbin-common.yaml` 的 `ypbin.security.identity` 段补 `trusted-source-token`（值 = `.env` 的 `GATEWAY_SIGN_TOKEN`）；备份 `/root/nacos-backup/ypbin-common.yaml.20260929-051006`。部署后 `ypbin-iot` 启动正常、health UP。<br>**为什么仓内没改**：`ypbin-common.yaml` 是 admin 所有的既有文件、**不在 SYNC 白名单（18 个）**，改它会让 `Sync Whitelist` 转红——需按纪律**先登记白名单 + 说明理由**（与 #6 同批做，属同一安全面）。<br>**风险如实说明**：重跑 `install.sh` 会把 Nacos 配置**覆盖回缺该键的版本** ⇒ 3.6.0 制品将再次起不来。**建议优先级提到 #6 之前** |
| 7 | CSV 批量注册 + 批次管理（两张表 + CSV 解析 + 错误行下载重传） | P1 | ⬜ | 对标阿里批次管理 |
| 8 | 消息跟踪 + 「定位建议」（设备详情页签，复用 debug 组件） | P1 | ⬜ | 对标华为消息跟踪 |
| 9 | TLS 8883 开放 + 收回明文 1883（+限来源） | P1→P0 发布门禁 | ⬜ | 与 #5 同批 |
| 10 | 平台自告警 + 指标大盘（Q7/A12/C4，复用 `iot.alert.*`） | P1 | ⬜ | 有现成指标 |
| 11 | 开放 API（M-5 起步：OpenAPI 规范 + API Key 鉴权挂接现有端点） | P1 | ⬜ | 整块里程碑 |
| 12 | 傻瓜式易用性批：4 步向导 / 品类模板+必选功能 / 默认值闭环 / CSV 导入 / 筛选持久化 / 错误人话+定位建议 | P1 | ⬜ | 报告 §5 |
| 13 | 前端死能力接线：设备凭据、设备标签、影子写入、点位映射增删改（后端已就绪、前端 0 引用） | P1 | ⬜ | 报告 §3 A 类 |

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
