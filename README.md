<div align="center">

# 🏭 ypbin-iot

**物联网平台 · IoT Platform**

> 基于 [`ypbin-admin`](https://github.com/wenbin-wb/ypbin-admin) 的 **fork**，在其基座上增量构建的 IoT 业务域：
> 设备台账、产品与物模型（TSL）、点位映射、影子、断档与可用率、历史曲线、设备凭据、
> MQTT 接入、下行指令（在线调试）、告警中心。

**Java 21 · Spring Boot 4.1 · Sa-Token · MyBatis-Plus · IoTDB · EMQX · Vue 3 · Ant Design Vue**

[![CI](https://github.com/wenbin-wb/ypbin-iot/actions/workflows/ci.yml/badge.svg)](https://github.com/wenbin-wb/ypbin-iot/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

</div>

---

## 📌 这是什么

`ypbin-iot` 把 admin 基座（RBAC、多租户、数据权限、任务调度、消息推送、AI）当作地基，
在上面**新增**了一整套 IoT 业务能力。设备从「建产品 → 定义物模型 → 绑设备 → 映射点位 →
采集入库 → 查询/告警/指令」是完整闭环，而不是一堆孤立页面。

**它不是什么**（避免误解，口径见 [刻意不做](docs/TASK-BOARD.md)）：不是通用组态/SCADA，
不自研 Broker 与时序库（用 EMQX 与 IoTDB），不做计费/SLA/工单，不做可视化规则编排
（远期待定）、不做 Webhook 通知（唯一 SSRF 面，刻意不做）。

与上游的关系（**必读**）：本仓是 fork，**基座只同步基础功能**，IoT 代码一律以新模块/新文件加入，
只有极少数 admin 既有文件在白名单内被「加法一行」地修改。同步纪律与白名单全清单见
[`SYNC.md`](SYNC.md)，且白名单有 CI 门禁（`Sync Whitelist`）自动校验。

## 🏗️ 架构（文字版）

```
                        ┌────────────────────────────┐
   浏览器 ──────────────▶│ ypbin-iot-ui (19000)        │  前端（Vue 3 + Ant Design Vue）
                        │  设备台账/产品/物模型/告警…  │  静态产物 + /api 反代到网关
                        └──────────────┬─────────────┘
                                       │ /iot/**  →  StripPrefix=1
                        ┌──────────────▼─────────────┐
                        │ ypbin-gateway              │  鉴权 + 路由
                        └───┬──────────┬─────────┬───┘
                            │          │         │
        ┌───────────────────▼──┐  ┌────▼──────┐  ┌▼─────────────┐
        │ ypbin-iot (18084)    │  │ypbin-auth │  │ypbin-system  │  admin 基座服务
        │  业务/管理面          │  │ypbin-ai   │  │（用户/消息）  │
        │  设备·产品·物模型·点位 │  └───────────┘  └──────────────┘
        │  影子·事件·可用率·告警 │
        │  凭据·指令·MQTT 入站   │
        └──┬───────────┬────────┘
           │           │
           │ Feign     │ JDBC
           │ /internal │
   ┌───────▼──────┐  ┌─▼──────────────┐  ┌──────────────┐
   │ ypbin-access │  │ MySQL          │  │ Redis        │
   │  接入面       │  │ 台账/物模型/告警│  │ 最新值/租约  │
   │  租约·对账    │  └────────────────┘  └──────────────┘
   │  规格下发     │            │
   │  采集/订阅    │  ┌─────────▼──────┐  ┌──────────────┐
   │  断档检测     │  │ IoTDB          │  │ EMQX         │
   └───┬──────────┘  │ 历史时序        │  │ MQTT 接入/下行│
       │             └────────────────┘  └──────┬───────┘
       │  Modbus / TCP / MQTT / OPC UA          │
       └─────────────── 现场设备 ────────────────┘
```

要点（这些是设计里真正决定形状的地方，不是装饰）：

- **管理面与接入面分离**：`ypbin-iot` 管台账与配置，`ypbin-access` 真正连设备采集。
  两者之间是**租约**（谁采哪个租户）+ **配置版本号对账**（配置变了怎么让接入侧知道），
  不是共享内存也不是定时全量拉取。
- **「采什么」只有一个事实源**：`ypbin-access` 通过 `GET /internal/device-specs` 拿采集规格，
  由 `DeviceSpecServiceImpl` 组装；它只下发**启停位为启用**且**物模型属性行存在**的点位。
- **静默失效是头号敌人**：设备/点位变更与「推进配置版本号」在**同一事务**里，否则接入侧永远
  不知道配置变了（历史上是 G7 静默零更新）。
- **`/internal/**` 是内网面**：只对内部凭证开放，与用户侧 `/iot/**` 分离；MQTT 入站是唯一
  用原始 HTTP 状态码的例外（EMQX 规则要按状态码决定重试）。

## 🚀 快速开始

### 一键安装（推荐）

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/wenbin-wb/ypbin-iot/main/deploy/install.sh)
```

脚本会拉取代码、起基础设施（MySQL / Redis / Nacos / IoTDB）、建库导配置、构建并拉起全部服务。
前提：一台能跑 Docker 的 Linux 主机、能访问 GitHub（或 Gitee 镜像，脚本会自动切换）、Java 21 与 Maven。

> ⚠️ **检出目录名**：脚本既能从 `ypbin-admin` 检出目录跑，也能从本 fork 的检出目录跑
> （Nacos 配置模板目录带回退查找；两个候选目录都不存在时**直接报错中止**，不会静默跳过配置导入）。

### 手工部署

按部署文档逐步执行，它们记录了口径、陷阱与回滚方式：

| 文档 | 覆盖 |
|---|---|
| [`docs/DEPLOY-BACKEND.md`](docs/DEPLOY-BACKEND.md) | 后端：构建 jar → 上传 → 重建单个服务 → 逐字节核对产物 |
| [`docs/DEPLOY-UI.md`](docs/DEPLOY-UI.md) | 前端：CI 真实产物覆盖 dist → 重启 → 核对 |
| [`docs/DEPLOY-TIMESERIES.md`](docs/DEPLOY-TIMESERIES.md) | 时序库（IoTDB）部署与验通 |
| [`docs/DEPLOY-CREDENTIAL-HYGIENE.md`](docs/DEPLOY-CREDENTIAL-HYGIENE.md) | 凭据纪律（口令轮换、argv 落点、临时文件） |
| [`docs/microservice-deployment.md`](docs/microservice-deployment.md) | 微服务部署总览 |
| [`docs/NACOS-AUTH.md`](docs/NACOS-AUTH.md) | Nacos 开启鉴权与口令处置 |
| [`docs/EMQX-DEPLOY.md`](docs/EMQX-DEPLOY.md) · [`docs/EMQX-INTEGRATION.md`](docs/EMQX-INTEGRATION.md) | EMQX 部署与平台侧集成（MQTT 入站 / 下行指令） |

## ✅ 已实现能力

> 状态口径：**已验证**指有测试/CI 门禁或生产实测证据。下方「未做」一栏如实登记，不把计划当交付。

### 🏭 设备与物模型

| 能力 | 说明 | 状态 |
|---|---|---|
| 产品管理 | 产品编码/名称/品类，数据格式与厂商信息 | 已验证 |
| 物模型 TSL | 服务 → 属性/命令/事件，数据类型、读写、枚举/范围约束 | 已验证 |
| 版本化与发布 | `draft`/`published` 两态；已发布版本不可变；设备绑定已发布版本 | 已验证 |
| TSL 导入导出 | JSON 形态导入导出（**zip 批量导入与发布前 diff 未做**） | 已验证 |
| 设备台账 | 设备编码/名称/协议/端点、绑定产品与版本、在线状态 | 已验证 |
| **设备启停** | 停用后**不再进入采集规格下发** ⇒ 接入侧解绑并撤销点位订阅（真的停止采集，不只是写标记） | 已验证 |
| 点位映射 | 属性 ↔ 寄存器地址、地址类型、采集周期、缩放/偏移、字节序、读写 | 已验证 |
| 设备分组与标签 | 分组树 + 标签，用于批量组织与筛选 | 已验证 |

### 📈 数据面

| 能力 | 说明 | 状态 |
|---|---|---|
| 采集规格下发 | 接入侧按租户拉规格，含属性标识与数据类型（采集侧据此解码，不靠帧猜类型） | 已验证 |
| 历史时序 | IoTDB 存储与查询；设备级采集周期取点位周期最小值 | 已验证 |
| 最新值 | Redis 最新值哈希（「最新一条」，历史走曲线） | 已验证 |
| 断档与可用率 | `quality=GOOD` 口径 + 连续 K×周期未上报判断档；逐台可用率 | 已验证 |
| 历史曲线 | 多点位时序查询 + 图表 + CSV 导出 | 已验证 |
| 影子 | `reported`/`desired` 双区读写 | 已验证 |
| 运行期事件 | 断档/恢复等事件落库与查询 | 已验证 |

### 🔐 接入与凭据

| 能力 | 说明 | 状态 |
|---|---|---|
| Modbus / TCP / MQTT / OPC UA | 协议栈接入（`ypbin-access` 侧采集） | Modbus/TCP 实机已验证；OPC UA 路径未实机 |
| 设备凭据 | 签发 / 轮换 / 吊销；明文永不下发，只同步哈希 | 已验证 |
| **MQTT 入站** | EMQX 规则 + HTTP 动作 → 薄适配端点，含幂等回执 | 已验证（生产实测） |
| **下行指令** | 六态状态机、周期超时扫描、幂等回执、手动重发 | 已验证（生产实测） |
| 在线调试 | 选设备 → 选动作 → 填参 → 下发 → 轮询状态与回执 | 已验证 |
| 租约与对账 | 租户归属租约 + 配置版本号对账 + 周期安全网兜底 | 已验证 |

### 🔔 告警

| 能力 | 说明 | 状态 |
|---|---|---|
| 告警规则 | 模板向导、作用域（点位/设备/产品/租户）、连续 N 次 / 持续 T 秒、阈值与级别 | 已验证 |
| 告警实例 | `PENDING → FIRING → ACKED → RESOLVED` 状态机，一键确认/批量确认/静默 | 已验证 |
| 通知投递 | 站内信 + 邮件，投递记录含状态与失败原因（**邮件需配置 SMTP**） | 站内信已验证；邮件待配 SMTP |
| 前端告警中心 | 列表/规则两页签、从设备台账带筛选跳入、设备详情页签 | 已验证 |

### ⏳ 未做（如实登记，勿当已完成）

- **明文 1883 的生产收紧**：开发测试期已按用户决策开放并完成外部端到端验证；
  **TLS 8883、限来源、限流未做**（生产发布门禁）。
- **EMQX 集群**：当前单节点 standalone。
- CSV 批量注册 + 批次管理、消息跟踪「定位建议」、开放 API（API Key/OpenAPI）、
  平台自告警指标大盘、前端死能力接线（设备标签/影子写入等后端已就绪但前端 0 引用）。
- 单租户部署与「台账无该租户」时的变更信号细节、多实例部署下的告警评估分布式锁
  （未确认项与代价见 [`docs/IOT-ROADMAP.md`](docs/IOT-ROADMAP.md) 与
  [`docs/ALERTING-DESIGN.md`](docs/ALERTING-DESIGN.md)）。

完整路线与优先级见 [`docs/IOT-ROADMAP.md`](docs/IOT-ROADMAP.md) 与看板
[`docs/TASK-BOARD.md`](docs/TASK-BOARD.md)；平台完整设计总纲见
[`docs/IOT-PLATFORM-DESIGN.md`](docs/IOT-PLATFORM-DESIGN.md)。

## 📁 目录结构

```
ypbin-iot/
├── ypbin-service-api/                 # 跨服务契约（DTO / 实体 / Feign 接口）
│   ├── ypbin-iot-api/                 #   IoT 契约：实体、请求/响应模型、枚举、内部端点契约
│   ├── ypbin-system-api/              #   system 契约（本仓按需追加内部端点）
│   └── ypbin-ai-api/
├── ypbin-service/                     # 可部署服务
│   ├── ypbin-iot/                     #   ★ IoT 业务/管理面（18084）
│   │   └── src/main/java/cn/ypbin/admin/iot/
│   │       ├── controller/            #     用户侧 + /internal 内网端点
│   │       ├── service/impl/          #     台账/规格/物模型/点位/影子/凭据…
│   │       ├── alert/ availability/   #     告警评估、断档与可用率
│   │       ├── command/ mqtt/ emqx/   #     下行指令、MQTT 入站、EMQX 协同
│   │       ├── lease/ retention/      #     租约、数据保留
│   │       └── timeseries/ values/    #     IoTDB 读写、最新值
│   ├── ypbin-access/                  #   ★ IoT 接入面：租约、对账、采集、断档检测
│   ├── ypbin-system/                  #   admin 基座（用户/角色/菜单/消息）
│   └── ypbin-ai/
├── ypbin-architecture-tests/          # 架构门禁（分层、循环内 DB/RPC、源码约定）
├── deploy/
│   ├── install.sh                     # 一键安装
│   ├── docker-compose.yml             # 全栈编排
│   ├── nacos/                         # Nacos 配置模板（占位符，导入时渲染）
│   └── sql/                           # 建库脚本：006/007 全新安装 + migration/ 增量
├── docs/                              # 设计、部署、路线、看板（见下表）
├── tools/                             # SQL 等价性校验、口令轮换等运维脚本
├── SYNC.md                            # ★ 与 admin 基座的同步纪律 + 白名单全清单
└── LICENSE                            # Apache-2.0（见下）
```

**SQL 双写纪律**：`deploy/sql/006-iot-schema.sql` + `007-iot-data.sql`（全新安装）与
`deploy/sql/migration/*-iot-*.sql`（已上线库增量）**语句必须等价**，由
`bash tools/check-iot-sql-equivalence.sh` 校验——两份人工同步必然漂移，所以有门禁。

## 🔒 许可

本项目采用 **Apache License 2.0**，与上游 [`ypbin-admin`](https://github.com/wenbin-wb/ypbin-admin) 一致。
全文见 [`LICENSE`](LICENSE)。

- 本仓是 `ypbin-admin` 的 **fork**，其原始代码同样以 Apache-2.0 授权。
  **fork 关系与改动范围**：IoT 业务代码为新增；对 admin 既有文件的修改仅限
  [`SYNC.md`](SYNC.md) 白名单内条目（每条都附「为什么不能用新文件实现」的理由）。
- 保留上游版权与许可声明；新增文件按仓库既有约定标注
  `Copyright (c) 2026-present ypbin-admin authors.`。
- Apache-2.0 允许商用与修改，但**不提供任何担保**，且需保留版权、许可与变更声明（见 `LICENSE` §4）。

> 仓内另有 [`LICENSE-USAGE.md`](LICENSE-USAGE.md)，那是基座自带的**商业 License 授权系统使用说明**
> （讲述如何给**你自己的软件**接入授权校验），**不是**本项目的许可条款——本项目许可以 `LICENSE` 为准。

## 📚 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/IOT-PLATFORM-DESIGN.md`](docs/IOT-PLATFORM-DESIGN.md) | 平台完整设计总纲（物模型对齐 IoTDA，里程碑 M-1~M-7） |
| [`docs/IOT-ROADMAP.md`](docs/IOT-ROADMAP.md) | 增量路线、每个增量的完成证据与「仍未闭环」清单 |
| [`docs/TASK-BOARD.md`](docs/TASK-BOARD.md) | 跨会话进度看板（唯一台账）+ 用户拍板决策 |
| [`docs/DEMO-DATA.md`](docs/DEMO-DATA.md) | 演示数据与设备 |
| [`docs/DEVICE-CREDENTIAL.md`](docs/DEVICE-CREDENTIAL.md) | 设备凭据实现口径 |
| [`docs/EMQX-INGRESS-DESIGN.md`](docs/EMQX-INGRESS-DESIGN.md) | MQTT 入站 / 下行设计（含契约留白） |
| [`docs/ALERTING-DESIGN.md`](docs/ALERTING-DESIGN.md) | 告警设计、复核整改与生产演示证据 |
| [`docs/VALUE-DECODE-DESIGN.md`](docs/VALUE-DECODE-DESIGN.md) | 原始值解码口径 |
| [`docs/ACCESS-ENABLE.md`](docs/ACCESS-ENABLE.md) · [`docs/LEASE.md`](docs/LEASE.md) | 接入面启用与租约 |
| [`SYNC.md`](SYNC.md) | ★ 与上游同步的纪律与白名单（改 admin 文件前必读） |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) · [`CHANGELOG.md`](CHANGELOG.md) | 贡献指南与变更记录 |

## 🤝 贡献

先读 [`SYNC.md`](SYNC.md)：**改 admin 既有文件需要白名单登记与理由**，
IoT 代码优先用新模块/新文件实现。提交前请跑通仓库既有门禁（Maven 单测 + 架构门禁 +
SQL 等价性校验 + 前端 i18n/类型/用例门禁），新增功能必须附带测试。
