# 与 admin 基座的同步纪律（YPBIN-IOT SYNC）

> 本仓是 **`ypbin-admin` 的 fork**：把 admin 当基座（admin 此后**只更新基础功能**），
> IoT 业务以**增量**方式加在上面。
> 目标：**任何时候都能把 admin 的新提交同步下来，且合并代价不随时间上涨。**

## 一、同步怎么做

```bash
git fetch --no-tags upstream                # upstream = https://github.com/wenbin-wb/ypbin-admin.git
git log --oneline HEAD..upstream/main       # 看落后了什么
git merge upstream/main                     # 合并（冲突处置见第三节）
mvn -B -ntp -fae clean verify               # 同步后必须重跑门禁
```

- **必须带 `--no-tags`**：本仓继承了 admin 的 `release.yml`（`v*` 标签触发建 Release）。
  若同步时把 admin 的标签拉进来，之后 `git push --tags` 会在本仓给 **admin 的提交**发 Release。
- 只跟踪 **`main`** 一条基线。admin 的 `boot`（单体）与 `feature/miniapp-backend` **不跟**——
  跟得越多，合并面越大（这是显式取舍，不是遗漏）。
- **Dependabot 的 `github-actions` 更新 PR 一律不合**：它改的是 admin 拥有的
  `ci.yml` / `codeql.yml` / `release.yml` / `sync-starter-version.yml`，合进去就破坏了白名单
  （`Sync Whitelist` 门禁会自动把这类 PR 判红）。要升 actions 版本，**去 admin 仓升**，本仓通过同步拿到。

## 二、允许改 admin 的文件（全清单，越少越好）

IoT 代码一律放**新模块/新文件**；下面这些是唯一的例外，改动要尽量是「加法一行」。
**本清单与 `.github/workflows/sync-whitelist.yml` 里的白名单必须保持一致**（改了这里就改那里）。

> **白名单膨胀要记账**：目前 7 个文件。每增加一个都是「以后同步时的潜在冲突点」；
> 加之前先问：能不能用新文件/新模块实现？只能改既有文件时才加，并在提交信息里写明理由。

| 文件 | 改动 | 说明 |
|---|---|---|
| `pom.xml`（根） | `dependencyManagement` 加 `ypbin-iot-api` 一行 | 根 pom 统一管理各 `-api` 模块版本 |
| `ypbin-service/pom.xml` | 加 `<module>ypbin-iot</module> + <module>ypbin-access</module>` | 业务域聚合 |
| `ypbin-service-api/pom.xml` | 加 `<module>ypbin-iot-api</module>` | 契约聚合 |
| `deploy/install.sh` | SERVICES 加一行 + Nacos cfg 清单加 `ypbin-iot` + 同步「共 N 个」计数注释 | 部署脚本的服务清单 |
| `deploy/docker-compose.yml` | 新增 `ypbin-iot` 服务块 | 部署编排 |
| `deploy/nacos/ypbin-gateway.yaml` | routes 加 `iot` 一段（`Path=/iot/**` + `StripPrefix=1`） | 网关路由（IoT 路由也进仓，便于与其它服务同构） |
| `deploy/.env.example` | 端口段注释加 18084 | 环境变量示例（纯注释） |
| `deploy/sql/006-iot-schema.sql`、`007-iot-data.sql` | **新文件** | 全新安装用 |
| `deploy/sql/migration/*-iot-*.sql` | **新文件**（命名必须含 `-iot-`） | 已上线库用；按文件名排序拼接后与 `006+007` **语句等价**（有 CI 校验）。顺序即结构演进顺序：`device-schema` → `lease-schema` → `menu-data` |
| `admin-ui`（后续） | 路由/菜单注册 | 前端增量时再补清单 |

**口径说明（两组数字别混）**：`git diff upstream/main --stat` 的字面数字**包含新增文件**
（IoT 业务文件 + SYNC.md + 门禁脚本等），而本纪律关心的只有「**改动的既有文件**」——
用 `git diff --name-only --diff-filter=MDR upstream/main` 看，应当只有上表那几个。

**明确不动**的（改了就会长期冲突）：`ypbin-common`、`ypbin-auth`、`ypbin-system`、
admin 的既有 SQL（`001`–`005`）、admin 的既有工作流。

**部署时的目录名**：`deploy/install.sh` 里有 52 处按 `ypbin-admin/` 目录名拼路径（它原本服务 admin 仓）。
本仓**不改这些行**（改 52 行 = 每次同步都冲突）；部署时把本仓检出到名为 `ypbin-admin` 的目录即可
（`git clone <this-repo> ypbin-admin`），或等 admin 侧把目录名做成变量后再收敛。

## 三、冲突处置

1. `pom.xml` 的模块清单冲突：**两边都留**（admin 加了新域 + 我们的 iot 行）。
2. `deploy/sql/*` 冲突：admin 的 `00X` 编号是**顺序占用**的——若 admin 新增了 `006-*`，
   把我们的文件**改名顺延**（例如 `008`/`009`），并同步更新本文件、迁移脚本名与等价校验脚本里的路径。
3. `deploy/install.sh` / `docker-compose.yml` 冲突：同为「加法行」冲突，两边都留（服务块整体保留）。
4. 共享文件的代码冲突（例如都改了 `ypbin-common`）：**优先接受 admin 的版本**，
   把我们的需求改到 IoT 自己的模块里实现——这样下一轮同步不会再冲突。
5. 有任何冲突：修完后在提交信息里写清「同步 admin@`<sha>` + 冲突处置」，便于下次追溯。

## 三·五、fork 运维须知（不写在代码里会踩的）

1. **Code Scanning 的「结案」状态不随 fork 复制**：admin 仓按误报结案的告警，在本仓首次扫描时会
   重新以 open 出现（本仓建立当天就有 2 条，位于 admin 自己的 `ypbin-system` 里）。
   处置：**镜像 admin 的结论**（同 reason + 同理由引用），不要各判各的——否则两边会漂移出两套结论。
2. **依赖机器人（Dependabot）默认会改 admin 拥有的 workflow 文件**，合入即破坏白名单；
   `Sync Whitelist` 会把这类 PR 判红，正确做法是去 admin 仓升级、本仓靠同步获得。
3. **部署目录名**：`deploy/install.sh` 按 `ypbin-admin/` 目录名拼路径（52 处，不改）；
   部署时把本仓检出成 `ypbin-admin` 目录，或等 admin 侧把目录名做成变量。
4. **同步后要重扫 CodeQL**：同步会把 admin 的新代码带进来，其新增/结案状态同样要按第 1 条镜像处置。

## 四、门禁

- `mvn -B -ntp -fae clean verify`（架构约束 / 源码规范 / 覆盖率等；**admin 没有 spotless 插件**，
  别按别的仓的习惯去跑 `spotless:apply`）。
- admin 的 CI 有「starter 版本必须等于最新 Release」这类硬断言；同步后本仓会跟着它走，
  这是**期望行为**（基座只更新基础功能）。
- IoT 自己的门禁若要新增，**加新文件/新规则**，不要改 admin 已有规则的语义。

## 五、两条机器门禁（纪律的强制力）

| 工作流 | 触发 | 作用 |
|---|---|---|
| `Sync Whitelist` | PR → main、手动 | ① 改动的**既有 admin 文件**必须落在第二节白名单内；② IoT 迁移脚本与全新安装脚本**语句等价**（`tools/check-iot-sql-equivalence.sh`） |
| `Upstream Sync Check` | push main、每周一 03:00 UTC、手动 | **干跑** `git merge --no-commit --no-ff upstream/main`：有冲突即红，把漂移从「将来爆炸」变成「当次就报」；只报告、不推送 |
