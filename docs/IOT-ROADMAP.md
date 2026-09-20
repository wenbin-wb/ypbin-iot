# ypbin-iot 增量路线（admin 基座 + IoT 业务）

> 本仓 = `ypbin-admin` 的 fork。**基座只更新基础功能**，IoT 业务按下面的增量逐个加。
> 每个增量都必须遵守 `SYNC.md` 的纪律：只改白名单内的 admin 文件、其余一律新增文件/新模块、
> 跑门禁、**外委复核**、SQL 双写（`006/007` 与 `migration/*`）并跑 `tools/check-iot-sql-equivalence.sh`。

## ✅ 增量 1：最小可跑通的业务域（已完成，`c49302f`）

- `ypbin-service-api/ypbin-iot-api`：实体 `IotDevice`（`@TableName("iot_device")` extends `TenantBaseEntity`）
  + 查询/请求/响应模型。
- `ypbin-service/ypbin-iot`：第 3 个业务域、**独立部署单元（18084）**、`BaseServiceImpl` + Controller
  （权限码 `iot:device:{list,create,delete}`）、`IotPermissionProvider`（**不实现它端点必然 403**）、
  `repackage`（**不声明产物就不是可执行 jar**）。
- 部署：`Dockerfile`、`docker-compose.yml` 的 `ypbin-iot` 块、`deploy/nacos/ypbin-iot.yaml`、
  `install.sh` 的服务与配置清单、网关 `Path=/iot/**` + `StripPrefix=1`。
- 纪律与门禁：`SYNC.md` + `Sync Whitelist`（白名单 + SQL 等价）+ `Upstream Sync Check`（干跑合并）。
- 复核：两轮（首轮 PASS(有条件) → P0/P1 修复 → 限定复验 → 末轮确认），证据含「权限告警 1→0」的负向对照、
  `java -jar` 实跑、白名单门禁变异会红、SQL 等价脚本 green→red→green。

## ✅ 增量 2：租户节点归属与租约（已完成）

- 表 `tenant_node_assignment`（平台表，租户插件按 `ignore-tables` 忽略）+ 契约 DTO（14 个）+
  `LeaseEpochRules` 纯判据；服务端状态机与内部端点 `/internal/lease/**`（6 个）+ 失效扫描 + 启动自检 + 指标。
- **并发靠数据库 CAS**（单条原子 UPDATE / `INSERT IGNORE` / 批量续期），不依赖进程内锁 ⇒ 多副本只有一个赢家；
  **无循环内 DB 调用**（架构门禁 `loopsMustNotCallDbOrRpc` 会拦）。
- 语义与取舍写在 **`docs/LEASE.md`**（含「epoch 每次归属变更都推进」——它解决了旧栈 ADR-0001 的悬空项；
  以及「本地 epoch 落后不再吊销、改为回执带服务端 epoch」）。
- 测试：`LeaseEpochRulesTest`（纯判据 6 条）+ `NacosTenantIgnoreConfigTest`（配置门禁 4 条）+ `LeaseServiceImplTest`（CAS 守卫/分支/语义 21 条）；
  单测还抓出过一个真实缺陷：`maxTenants=null`（默认「不限」）曾因 `ConcurrentHashMap` 不接受 null 而 NPE。
- 待办（M0b）：节点注册表落库（同时让**容量**变成数据库级原子：节点行 + `SELECT ... FOR UPDATE`）、
  可分配租户改读台账表、**真库并发用例**（多副本抢同一租户，旧栈用 200 轮抓出过双主）、
  到期时间改用数据库时钟（防跨副本时钟偏移）、`ILeaseClient`（随增量 3）。
- **跨仓待办（需在 admin 仓做，本仓靠同步获得）**：网关拒绝 `/internal/**` 路径 + 把 `X-Internal-Token`
  加进头部剥离名单（当前 `POST /iot/internal/lease/release` 会被转发进服务，见 `docs/LEASE.md` 的已知缺口）。

## 增量 2 原始设计说明（保留供追溯）

**目标**：把「哪个节点采哪个租户」变成可查、可续约、可失效、可接管的服务端权威。


- 表：`tenant_node_assignment`（tenant_id / access_node / epoch / lease_expire_at / state）
  + 必要的索引；SQL 双写（006/007 风格 + `migration/`）。
- `ypbin-iot-api`：租约契约 DTO（register / acquire / renew / release / assignment / epochs）。
- `ypbin-iot`：租约状态机（注册、领取、续约、释放、失效扫描、epoch 推进）、内部端点
  （`/internal/lease/**`，走 admin 既有入站可信校验）、启动自检、Micrometer 指标。
- 原子性与互斥：**必须**用事务/行锁保证「同一租户同一时刻只有一个 owner」——
  旧仓在这里踩过「单副本并发也能双主」的坑（60 轮出现 12 次双主），修法是公平锁 + 原子 compute。
- 测试：状态机单测 + **并发双主用例**（这是关键回归）+ epoch 规则。
- 参考实现（可直接读、按 admin 的 DB/租户体系重写）：旧仓 `ypbin-iot-cloud` 的
  `ypbin-iot-cloud-core/src/main/java/cn/ypbin/iotcloud/core/lease/*` 与其 `docs/adr/0001-*.md`（P3 四轮复核结论）。

## 增量 3：access 接入单元（有状态、独立部署）

- 新 app（第 6 个部署单元，端口建议 18086）：`ypbin-service/ypbin-access`（+ `-api`）。
- 依赖 **`ypbin-iot-starter`**（协议层，独立仓，通过 `ypbin-iot-bom` 引入；本仓 CI 需先取源安装并**锁 SHA**）。
- 三个宿主 SPI（`DeviceRegistry` / `ConnectionSpecProvider` / `DataSink`）+ `IotTenantLinkManager`
  （租约归属 → **真建链/真断链**）+ self-fencing 三路径（revoked / nodeFenced / 本地过期）+ 周期重领。
- 三个必守耦合（读 iot-starter 源码得到，写死也要遵守）：`ypbin.iot.devices.enabled` 必须为 true、
  变更 revision 严格递增、就绪期 `loadAll()` 与 `ApplicationRunner` 的先后。
- 参考实现：旧仓 `ypbin-iot-cloud` 的 `ypbin-iot-cloud-access/*`（P4b 三轮复核，含**真实 socket** e2e：
  会话 0→1→0、OS 层 EOF、跨租户不误伤）。

## 增量 4：前端（admin-ui fork，同模式）

- 新建 `ypbin-admin-ui` 的 fork（`upstream` 指向 admin-ui），按同样的白名单纪律加 IoT 页面。
- 页面：设备台账（列表/新增/删除）、租约与接管状态；菜单/权限码已由增量 1 的 SQL 提供
  （`page.iot.device.title` / `/iot/devices` / `iot:device:*`）。

## 增量 5：数据上行与存储

- `DataSink` 从「只计数」换成「上报到 `ypbin-iot`（含租户上下文）」；设备时间序列/最新值存储选型。
- 参考旧仓 README/spec 的取舍记录（EMQX、批量写入、背压、丢弃计数）。

## 收尾

- IoT 逻辑搬完后归档 `ypbin-iot-cloud`（保留历史与 ADR/契约文档），并把其 `docs/` 里仍有效的部分
  迁到本仓 `docs/`。
