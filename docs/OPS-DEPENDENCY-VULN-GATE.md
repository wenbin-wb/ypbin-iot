# 依赖漏洞门禁（OWASP Dependency-Check）运维说明

> 落实 issue **#237**。本文件是这条门禁的**单一入口**：为什么选它、数据源与缓存策略、
> 本地怎么复现、报告怎么读、抑制规则怎么写、边界在哪里。
> 关联文件：`.github/workflows/dependency-vuln-gate.yml`（工作流）、
> `deploy/dependency-check-suppressions.xml`（抑制规则）、`docs/DEPLOY-BACKEND.md` §2.0（制品与 SBOM）。

## 0. 结论（TL;DR）

- **选方案 B：`org.owasp:dependency-check-maven`（自包含，不依赖 GitHub 安全特性）**；
  **不选方案 A：`dependency-review-action`**——本仓的 Dependency Review 实测不可用（证据见 §1）。
- 判定线：**CVSS ≥ 7 判红**（`-DfailBuildOnCVSS=7`）。
- 数据源：**NVD API 2.0**（可缓存）；**关闭 OSS Index / RetireJS**（二者在扫描期联网、易限流）。
- 缓存：漏洞库按 **ISO 周**缓存（`actions/cache`），**更新 best-effort、扫描强制离线**（`autoUpdate=false`）。
- 冷缓存且更新失败 ⇒ **显式判红**并给修复指引，**不让空库静默放行**。

## 1. 为什么不是 Dependency Review（方案 A）——证据与现状

仓内 issue #237 记录了 2026-10-12 的一手实测：

1. 新建的 `Dependency Review` 工作流（`actions/dependency-review-action@v4`，`fail-on-severity: high`）
   CI 直接失败：`##[error]Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled`；
2. API 侧复核：`GET /repos/wenbin-wb/ypbin-iot/dependency-graph/compare/{base}...{head}` → **403 Forbidden**；
3. 即便 `PATCH /repos/wenbin-wb/ypbin-iot` 把 `security_and_analysis.dependency_graph` 置为 `enabled` 并返回 **200**，
   compare 仍然 **403**。

⇒ 这是「该特性在当前仓库/权限下不可用」，不是工作流写法问题（合并一个恒红的工作流只会把 CI 变成噪音）。

**本次（2026-10-13）的复核边界（如实登记）**：本机没有 GitHub 凭据（无 `gh`、无 token），
**无法独立复验**上述 403，故采信仓内 issue #237 的实测记录。
若日后仓库可见性/设置/权限变化使依赖图可用，可**并行**加回 `dependency-review-action`
（它按 PR 的依赖 diff 判定，比全量扫描更省时），两条门禁不冲突。

## 2. 触发条件与成本

| 事件 | 何时跑 | 说明 |
|---|---|---|
| `workflow_dispatch` | 手动 | 可勾选是否先更新 NVD 库、可只扫指定模块（`-pl`） |
| `schedule` | 每周一 04:00 UTC | 刷新漏洞库并全量复扫（与 `codeql.yml` 的 03:00 错开） |
| `push` → `main` | 仅当 `pom.xml` / `**/pom.xml` / 本工作流 / 抑制规则变更 | 依赖变了才跑，控成本 |
| `pull_request` → `main` | 同上（仅依赖相关文件变更） | PR 阶段阻断“引入已知高危依赖” |

成本：全量扫描是“解析全反应堆依赖 + 逐库比对 NVD 数据”，分钟级到十几分钟（首次全量更新漏洞库更久）。
为控成本，**PR/main 只在依赖相关文件变更时触发**；需要临时全量复扫用 `workflow_dispatch`。
本机（2 核）本地复现时必须 `flock /tmp/build.lock` 串行化，见 §4。

## 3. 数据源与缓存策略（“不产生随机红”的关键）

### 3.1 数据源

| 数据源 | 是否启用 | 说明 |
|---|---|---|
| **NVD API 2.0**（`https://services.nvd.nist.gov/rest/json/cves/2.0`） | ✅ | CVE/CPE 判定数据；可整体落盘缓存（H2 库 `odc.mv.db`） |
| NVD data feed（`nvdDatafeedUrl`） | ❌ | 不使用；插件默认走 NVD API 2.0，本策略只用可整体缓存的 API 数据源 |
| **OSS Index**（Sonatype） | ❌ 显式关闭 | 扫描期实时联网、有独立限流 ⇒ 关掉以消除随机红与“离线不可用” |
| RetireJS（前端 JS 库） | ❌ 显式关闭 | 本仓是后端 Maven 工程，前端在 `ypbin-iot-ui` 仓另管 |
| hosted suppressions | ✅ 默认 | 依赖库自带的社区误报抑制文件，随更新步骤一并拉取 |

关闭 OSS Index 的**代价如实登记**：OSS Index 能覆盖一部分 NVD 未收录的生态漏洞，
关闭后这部分覆盖缺失；换来的是“扫描可完全离线、结果只由缓存数据决定”，这是本任务明确要的口径。

### 3.2 NVD API Key（可选，但强烈建议）

- 无 Key 时 NVD API 限速更严、首次全量更新更慢（插件 `nvdApiDelay` 默认值：
  有 Key **3500ms**、无 Key **8000ms**；`nvdMaxRetryCount` 默认 10。
  **来源**：OWASP Dependency-Check Maven 配置文档 <https://dependency-check.github.io/DependencyCheck/dependency-check-maven/configuration.html>（一手，访问 2026-10-13）；
  本次另从 `dependency-check-maven:13.0.0` 构件内的 `META-INF/maven/plugin.xml` 逐参核对过参数名）。
- 配置方式：仓库 `Settings → Secrets and variables → Actions` 新增 **`NVD_API_KEY`**（NVD 官网免费申请：<https://nvd.nist.gov/developers/request-an-api-key>，访问 2026-10-13）。
- 工作流用 **`-DnvdApiKeyEnvironmentVariable=NVD_API_KEY`** 传入，让插件从**环境变量**读取，
  **不把 Key 写进 Maven 命令行**（避免出现在调试日志里；插件作者也对 `-DnvdApiKey` 给了安全告警）。
- 未配置 Key 时工作流只打印 `::warning::`，**不判红**——但首次冷缓存更新可能很慢。

### 3.3 缓存策略

- 缓存路径：`$GITHUB_WORKSPACE/.dependency-check-data`（工作流 `ODC_DATA_DIR`），库里是 `odc.mv.db`。
- 缓存键：`odc-nvd-<os>-<ISO周>`，`restore-keys: odc-nvd-<os>-` 前缀回退到**最近一次**的库；
  save 用 `...-<run_id>-<run_attempt>` 唯一后缀（缓存条目不可覆盖，唯一键避免保存失败）。
- 更新步骤 `continue-on-error: true`；保存步骤 `if: always()` ⇒ **更新失败也把已有副本存下来**。
- 扫描步骤固定 `-DautoUpdate=false` ⇒ 扫描**不触网**，结果只由缓存决定（不会因 NVD 抖动转红）。
- 冷缓存（无缓存且更新失败）⇒ “断言漏洞库可用”步骤**显式判红**，并提示配置 `NVD_API_KEY` 后重跑预热。

> 缓存容量提示（**量级估计，本仓未实测**）：NVD 全量库为数百 MB 级；GitHub 每仓缓存有总容量上限，
> 按周滚动 + LRU 淘汰属预期。

## 4. 本地复现（与 CI 同一条命令）

```bash
# 本机只有 2 核：任何 maven 构建都用 flock 串行化（仓库运维约定）
# ① 先更新一次漏洞库（可选；联网、耗时。key 从环境变量读，不落命令行）
export NVD_API_KEY=...   # 没有可跳过
flock /tmp/build.lock mvn -B -ntp org.owasp:dependency-check-maven:13.0.0:update-only \
  -DdataDirectory="$PWD/.dependency-check-data" \
  -DnvdApiKeyEnvironmentVariable=NVD_API_KEY -DretireJsAnalyzerEnabled=false

# ② 离线扫描（CI 的真实判定命令；用缓存库，不触网）
flock /tmp/build.lock mvn -B -ntp -DskipTests \
  org.owasp:dependency-check-maven:13.0.0:aggregate \
  -DautoUpdate=false \
  -DdataDirectory="$PWD/.dependency-check-data" \
  -DoutputDirectory="$PWD/target/dependency-check-report" \
  -DfailBuildOnCVSS=7 \
  -DfailOnError=true \
  -DfailBuildOnUnusedSuppressionRule=false \
  -DossIndexAnalyzerEnabled=false \
  -DretireJsAnalyzerEnabled=false \
  -Dformats=HTML,JSON,SARIF \
  -DsuppressionFiles=deploy/dependency-check-suppressions.xml
```

只扫单个模块加 `-pl <module>`（`aggregate` 会聚合该模块；PR 慢时可这样控范围）。

## 5. 报告怎么读

工作流把 `target/dependency-check-report/` 作为 artifact `dependency-check-report` 上传（保留 30 天），含：

| 文件 | 用途 |
|---|---|
| `dependency-check-report.html` | 人工阅读：依赖 → CPE → CVE → CVSS |
| `dependency-check-report.json` | 机器处理/留痕：`dependencies[].vulnerabilities[].cvssv3.baseScore` 等 |
| `dependency-check-report.sarif` | 上传 Code Scanning（Security → Code scanning alerts），在 PR/告警页内联定位 |

- SARIF 上传是**尽力而为**（该步 `continue-on-error`）：未开启代码扫描也不影响门禁结论。
- 门禁结论只由 `-DfailBuildOnCVSS=7` 决定：任一依赖的 CVSS ≥ 7 即 `BUILD FAILURE`。

## 6. 抑制规则政策（`deploy/dependency-check-suppressions.xml`）

只允许抑制两类：

1. **误报（FP）**：CVE 与本仓实际用法/版本不符，须给可核对的证据；
2. **已评估并接受的风险**：短期无法升级，须给到期日（`<suppress until="YYYY-MM-DDZ">`）与补偿措施。

**不得**抑制“已知该修、暂不修”的漏洞——那是藏问题，不是治理问题（与 `SYNC.md` 的“禁静默降级”同源）。
每条必须写：CVE/GHSA 编号 + 判定 + 理由 + 复核人 + 复核日期。
工作流设 `-DfailBuildOnUnusedSuppressionRule=false`（不因“规则没命中”随机判红），
但人工必须定期清理失效/过期规则，否则抑制会慢慢变成黑洞。

## 7. 边界与未核实（如实登记）

- **只覆盖 Java/Maven 依赖**；前端 `ypbin-iot-ui`（pnpm）需在该仓另设 `pnpm audit` 类门禁。
- **不替代 SAST**：CodeQL 负责源码漏洞，本门禁只回答“第三方依赖的已知 CVE”。
- **只对 NVD 已知漏洞、且以缓存数据的时点为准**；不承诺“无 CVE”，新披露漏洞要等下一次更新/扫描。
- **未在 GitHub Actions 真跑**：本机无法执行 Actions；已完成的是 YAML 解析、插件本地干跑与命令/参数核对。
- **未配置 `NVD_API_KEY`**（审计记录显示本仓 GitHub secrets 长期未配全）⇒ 首次全量更新较慢；
  这是**一次性**成本，之后靠缓存，不影响“限流下不随机红”的设计。
- 首次 PR 时若默认分支尚无缓存，断言步骤会显式判红并提示预热；预热后 PR 用 main 的缓存离线扫描。

## 8. 与 Release 制品 / SBOM 的关系

- `docs/DEPLOY-BACKEND.md` §2.0 的 Release 制品含 **CycloneDX SBOM**（依赖清单）。
  **SBOM ≠ 漏洞扫描**：SBOM 回答“这个版本里有哪些依赖”，本门禁回答“这些依赖有没有已知高危 CVE”。
- 两者可以配合：用 SBOM 定位某 CVE 的影响面，用本门禁在合并/发版前阻断。
