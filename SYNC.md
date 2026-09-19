# 与 admin 基座的同步纪律（YPBIN-IOT SYNC）

> 本仓是 **`ypbin-admin` 的 fork**：把 admin 当基座（admin 此后**只更新基础功能**），
> IoT 业务以**增量**方式加在上面。
> 目标：**任何时候都能把 admin 的新提交同步下来，且合并代价不随时间上涨。**

## 一、同步怎么做

```bash
git fetch upstream                 # upstream = https://github.com/wenbin-wb/ypbin-admin.git
git log --oneline HEAD..upstream/main     # 看落后了什么
git merge upstream/main                   # 合并（冲突处置见第三节）
mvn -B -ntp -fae clean verify             # 同步后必须重跑门禁
```

- 只跟踪 **`main`** 一条基线。admin 的 `boot`（单体）与 `feature/miniapp-backend` **不跟**——
  跟得越多，合并面越大（这是显式取舍，不是遗漏）。
- CI 里有一条 **`Upstream Sync Check`**（每周一 + 手动触发 + push main 时）：
  它 `git merge --no-commit --no-ff upstream/main` 干跑一次，**有冲突就红**。
  它只报告、不推送，放在独立 workflow 文件里（零冲突面）。

## 二、允许改 admin 的文件（全清单，越少越好）

IoT 代码一律放**新模块/新文件**；下面这些是唯一的例外，改动要尽量是「加法一行」：

| 文件 | 改动 | 说明 |
|---|---|---|
| `pom.xml`（根） | `dependencyManagement` 加 `ypbin-iot-api` 一行 | 根 pom 统一管理各 `-api` 模块版本 |
| `ypbin-service/pom.xml` | 加 `<module>ypbin-iot</module>` | 业务域聚合 |
| `ypbin-service-api/pom.xml` | 加 `<module>ypbin-iot-api</module>` | 契约聚合 |
| `deploy/sql/006-iot-schema.sql`、`007-iot-data.sql` | **新文件** | 全新安装用 |
| `deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql` | **新文件** | 已上线库用；与 006/007 内容等价，**改一边必须同步另一边** |
| `admin-ui`（后续） | 路由/菜单注册 | 前端增量时再补清单 |

**明确不动**的（改了就会长期冲突）：`ypbin-gateway` 路由（IoT 路由配在 **Nacos** 的
`ypbin-gateway.yaml` 里，不进仓）、`ypbin-common`、`ypbin-auth`、`ypbin-system`、
根 `pom.xml`（`ypbin-service`/`ypbin-service-api` 已在其中）、admin 的既有 SQL。

## 三、冲突处置

1. `pom.xml` 的模块清单冲突：**两边都留**（admin 加了新域 + 我们的 iot 行）。
2. `deploy/sql/*` 冲突：admin 的 00X 编号是**顺序占用**的——若 admin 新增了 `006-*`，
   把我们的文件**改名顺延**（例如 008/009），并同步更新本文件与 migration 文件名。
3. 共享文件的代码冲突（例如都改了 `ypbin-common`）：**优先接受 admin 的版本**，
   把我们的需求改到 IoT 自己的模块里实现——这样下一轮同步不会再冲突。
4. 有任何冲突：修完后在提交信息里写清「同步 admin@<sha> + 冲突处置」，便于下次追溯。

## 四、版本与门禁

- admin 的 CI 有「starter 版本必须等于最新 Release」这类硬断言；同步后本仓会跟着它走，
  这是**期望行为**（基座只更新基础功能）。
- 同步后必须重跑：`mvn -B -ntp -fae clean verify`（架构约束/源码规范/spotless/覆盖率等）。
- IoT 自己的门禁若要新增，**加新文件/新规则**，不要改 admin 已有规则的语义。
