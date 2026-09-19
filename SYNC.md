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

| 文件 | 改动 | 说明 |
|---|---|---|
| `pom.xml`（根） | `dependencyManagement` 加 `ypbin-iot-api` 一行 | 根 pom 统一管理各 `-api` 模块版本 |
| `ypbin-service/pom.xml` | 加 `<module>ypbin-iot</module>` | 业务域聚合 |
| `ypbin-service-api/pom.xml` | 加 `<module>ypbin-iot-api</module>` | 契约聚合 |
| `deploy/install.sh` | SERVICES 加一行 + Nacos cfg 清单加 `ypbin-iot` + 同步「共 N 个」计数注释 | 部署脚本的服务清单 |
| `deploy/docker-compose.yml` | 新增 `ypbin-iot` 服务块 | 部署编排 |
| `deploy/sql/006-iot-schema.sql`、`007-iot-data.sql` | **新文件** | 全新安装用 |
| `deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql` | **新文件** | 已上线库用；与 006/007 **语句等价**（有 CI 校验） |
| `admin-ui`（后续） | 路由/菜单注册 | 前端增量时再补清单 |

**明确不动**的（改了就会长期冲突）：`ypbin-gateway` 路由（IoT 路由配在 **Nacos** 的
`ypbin-gateway.yaml` 里，不进仓）、`ypbin-common`、`ypbin-auth`、`ypbin-system`、
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
