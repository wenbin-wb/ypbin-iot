# SESSION-HANDOFF · ypbin-iot 断点交接（新会话启动包）

> **用途**：新会话读本文 + `docs/TASK-BOARD.md` 即可无缝接续，不必翻历史对话。
> **生成时间**：2026-09-30
> **状态**：⚠️ 本文档写在共享工作区，**尚未提交**（建议随下一批 PR 一并入库）。

---

## 0. 新会话第一句话（可直接粘）

> 继续 ypbin-iot 任务：先读 `ypbin-iot/docs/SESSION-HANDOFF.md` 与 `ypbin-iot/docs/TASK-BOARD.md`，按其中「下一步」推进；先确认当前在跑/未收口的批次（#7 CSV 批量注册）状态，再按看板顺序做下一项。

---

## 1. 环境坐标（一手实测，勿凭记忆改）

| 项 | 值 |
|---|---|
| **应用机（生产）** | `ssh -i ~/.ssh/id_ed25519_iot_test -p 22 root@113.142.217.58`；别名 `ypbin-prod`（`113.142.217.42:47048`）——**两者是同一台机**（`machine-id c8452f83fffa26f523c8c8b075c452f4`、主机名 `liuliangsI2V2XZCGTrr`，2026-09-30 实测一致 ✓）。有代理曾称"`.58` 已失效"是**误判** ✗ |
| **中间件机** | `ssh -i ~/.ssh/id_ed25519_ypbin_mw -p 61260 root@43.242.200.8`（主机名 `ECS06630032`，8C/16G）。**EMQX 在这里，不在应用机** ✓（应用机 `docker ps -a` 无 emqx、无 1883/8883） |
| **仓路径** | 后端/文档/SQL：`/home/wenbin/projects/ypbin/ypbin-iot`；前端：`/home/wenbin/projects/ypbin/ypbin-iot-ui`（**仓库根就是这两个目录**） |
| 红线 | 中间件机上**别人的项目**（`aicomic-*`、`sub2api`、宝塔 80/443）**一律不碰** ✗；生产机禁 `mvn` ✗；不 `prune` ✗；不改 sshd_config ✗ |

## 2. 已完成（全部已进 main）

| 批次 | 内容 | main SHA |
|---|---|---|
| 第一批 4 项 | 设备「全部停用」写入口 / i18n 键+菜单标题门禁 / README+许可 / 文档矛盾修复 | 后端 `b87080aa`、前端 `519941d6` |
| 第二批（安全） | **#6b** identity 部署阻塞面（starter 3.6.0 强制 `trusted-source-token`）+ **#6 SF-5** 入口侧身份校验（伪造头 `403`、正常链路 200、采集不停） | `ab066270` |
| 告警能力（更早） | 段 C1–C2 后端 + 段 C3 前端（含"表格无限撑高"修复、vxe 静默丢弃守卫） | `7002208c` / 前端 `625a5319` |
| **#5 SMTP（P0）** | 邮件参数写入 `sys_config` 的 `mail` 组；**真实发送 `SENT` + 用户确认收件箱收到** ✓（参数不在 .env/Nacos；UI 改即时生效、改 SQL 需重启 `ypbin-system`；已记 `deploy/PROD-OPS-NOTES.md §10`） | — |
| **#14 无收件人可见性** | 「无收件人 ⇒ GIVEN_UP」路径加 **WARN 日志 + TODO**，生产实测有原文（含 `NO_RECIPIENT` 与指引） | `f6b214a5` + `a9d33f9d` |
| **#9 TLS 文档** | `docs/EMQX-TLS-DESIGN.md`（445 行）+ 看板 #9 更正 | `71f99ec1` |

## 3. 进行中 / 未收口（**新会话首要确认项**）

> **本节已于 2026-09-30 更新**（上一版登记的 #7 未提交改动**已收口**，见下）。

- ✅ **#7 已完全收口**（**不再是未收口项**）：后端 PR **#109** → main `bf5cbe85`、前端 PR **#33** → main `f2cc8e0`、看板回写 PR **#110** → main `cbe7bf15`。两仓 CI 全绿后 squash 合并。**新会话不必再接 #7** ✓。
- 🔄 **#8 设计已交付、实施未开始**：`docs/MESSAGE-TRACE-DESIGN.md`（PR **#111** → main `b8d266dc`，含两轮独立复核修订）+ **顺带修复的既有租户隔离门禁缺口**（`iot_command_instance`/`iot_mqtt_ingest_receipt` 已登记 + 变异验证转红）。**下一步 = 按设计 §8 实施一批**（读侧聚合端点 + 定位建议纯函数 + 前端「消息跟踪」页签）。⚠️ 实施前请先读设计 §3.3（哪些阶段**一期不可得**，勿造状态）与 §5.1（**权限必须用 `iot:debug:get`，不要用 `iot:device:list`**）。
- ⏳ **共享工作区仍有 13 项未提交改动**（**本批刻意未动** ✗，待各自归属处理）：`deploy/PROD-OPS-NOTES.md`（#5 SMTP §10，属第二批遗留）、`deploy/emqx/*`（6 改 + 3 新增：`emqx-tls-rollback.sh`/`emqx-tls-selfcheck.sh`/`gen-certs.sh`，疑似**早期 TLS 代理残留**；**内容已由 PR #107 以文档形式落地**，代码/配置部分需**逐文件核对归属**：有价值 ⇒ 单独 PR，无价值 ⇒ 丢弃 ✓）、`docs/OPENAPI-DESIGN.md`/`docs/OPENAPI-PREFLIGHT.md`（**已有内容，建议单独文档 PR 入库**）、`docs/SESSION-HANDOFF.md`（**本文档，仍未入库** ⇒ 建议随下一文档批进仓）。

## 4. 待办（按建议顺序）

| # | 任务 | 备注 |
|---|---|---|
| 小修 A | `docs/EMQX-DEPLOY.md:88` 仍写「`8883/8083/8084` 已显式 `enable=false`」✗ **与实机不符**（8883 已启用）⇒ 极小文档 PR 改一行 |
| 小修 B | **云安全组 8883 登记自相矛盾**：仓内写"未放行（生产机实测 CLOSED）"，实测"应用机→`43.242.200.8:8883` TCP 可连通" ⇒ **只读复核**后回写（**不据此宣称公网可达/不可达** ✗） |
| 清理 C | **远端旧分支**清理（`docs/6b-board`、`chore/board-closeout-6b`、`docs/emqx-decisions`、`docs/fork-ops-notes`、`docs/receipt-corrections`、`docs/deploy-backend-56-fixes`、`chore/iotdb-password-hardening`、`docs/alerting-design` 等）：**逐个先核对"无独有内容"再删** ✓（`docs/emqx-tls-design` 已删 ✓） |
| **#8（下一项）** | 消息跟踪 + 「定位建议」：**设计已完成**（`docs/MESSAGE-TRACE-DESIGN.md`，含两轮独立复核）⇒ **下一步按设计 §8 实施一批**：读侧聚合端点（`GET /devices/{deviceId}/messages`，权限 **`iot:debug:get`**）+ 定位建议**纯函数**规则（每条至少一正一反用例）+ 前端「消息跟踪」页签（复用 `detail-debug.vue` 约定）。**不建新表、不改写入路径**（一期）；二批（上行结构化补齐 / 实时跟踪）触及采集链路 ⇒ 按生产发布纪律 + 外委独立复核 |
| #9 余项 | 正式 CA 证书 / **收回明文 1883**（须先确认云安全组放行 8883）/ 限来源 / **轮换明文窗口用过的设备凭据** |
| #10 | 平台自告警 + 指标大盘（复用已有 `iot.alert.*` 指标） |
| #11 | 开放 API（M-5）：**设计 `docs/OPENAPI-DESIGN.md` + 前置核验 `docs/OPENAPI-PREFLIGHT.md` 已完成**；**首期只读六项 O-1…O-6**，命令下发 O-7 列第 2 批（4 前置：publish 移出事务 / 客户端幂等键 / 限流配额 / 默认不授予）；实施第一步先做 **F-1/F-2 实跑验证**（虚拟主体+scopes 端到端、注解鉴权真执行），不过就停 ✗ |
| #12 | 傻瓜式易用性批（4 步向导 / 品类模板+必选功能 / 默认值闭环 / CSV 导入 / 筛选持久化 / 错误人话+定位建议） |
| #13 | 前端死能力接线（设备凭据 / 标签 / 影子写入 / 点位映射写 UI / **+ 邮件测试按钮**——`system:mail:test` 权限码在两端前端仓均零引用 ✗） |
| 其他 | **同步 `upstream/main`（修 `dry-run-merge` 长期失败**，落后 4 提交）· 评估**网关仓升 starter 3.6.0**（网关容器仍跑 3.5.0）· 邮件参数纳入例行备份 · `WARN 日志未被日志采集消费`（#14 已登记） |

## 5. 工作方式约定（用户明确要求，务必遵守）

1. **复核节奏**：**每完成一个大功能复核一次**（省时间与 token）✓；安全类（L3）与生产发布仍保留**一次**外委独立复核，但不逐轮重复 ✗。
2. **任务列表落盘**：`docs/TASK-BOARD.md` 是唯一进度台账，每批完成必须回写（含证据行 + 变更记录）✓。
3. **并发纪律**：同一工作区**不要并发跑 Maven**（会产生假红 ✗）；并行批次请用**独立 git worktree**（如 `/home/wenbin/projects/ypbin-wt/<name>`），完成后推分支走 PR。
4. **提交纪律**：走 PR、**不直推 main** ✓（有两仓 main 无分支保护，已发生过一次直推）；commit 不加 `Co-Authored-By`；只 `git add` 自己的路径。
5. **部署纪律**（`docs/DEPLOY-BACKEND.md`）：**先合并再部署**；生产机禁 mvn；本地构建 jar → 上传 → `docker compose build <svc>` → `up -d --no-deps <svc>`；声明 artifact 三元组（image id + 容器内 jar md5 + StartedAt）；先备份留回滚；验收窗口冻结其它容器动作 ✓。
6. **凭据纪律**：真实凭据只在 `.env`(600)/数据库参数/600 临时文件，**绝不进仓库、提交、日志、输出** ✗；只报**长度/指纹/键名**；PAT 在 `~/.git-credentials`，**只能写进 600 临时 `curl -K` 配置、用完即删、绝不打印** ✗。
   - ⚠️ **未决**：有代理曾把 **GitHub PAT 切片打进工具输出** ✗ ⇒ 会话记录若留存，**建议轮换该 PAT**（用户决定，提示一次即可）。
   - 明文 1883 窗口期用过的**设备凭据**，上生产前建议轮换 ✓（已登记 #9 余项）。

## 6. 踩过的坑（别重犯）

1. **判断 EMQX listener 是否启用**：`emqx ctl conf show listeners.ssl.default` 返回 `key_not_found` **不代表未启用** ✗ —— 它对**正常工作的 1883 也返回同样错误**（退出码同为 0）。**正确判据 = `emqx ctl listeners` 的 `running`** ✓（已作为"陷阱"写进 `docs/EMQX-TLS-DESIGN.md`）。
2. **EMQX 不在应用机**，在中间件机 ✓（在应用机查 8883 会得出错误结论 ✗）。
3. **Docker 发布端口不经 INPUT/ufw** ⇒ 真闸门是**绑定地址 + 云安全组**；按来源限制需 `DOCKER-USER` 或安全组 ✓。
4. **`docker compose config` / `emqx ctl conf show dashboard` 会打印明文口令** ✗（已发生两次）⇒ 不要整文件 dump。
5. **生产副本漂移**：`deploy/docker-compose.yml` 曾丢失 6 处 Nacos 凭据（⇒ 一重建就 403 反复重启 ✗），已与仓内对齐 ✓；改 compose 前先核对。
6. **vxe 表格高度**：`height:'auto'` 与 Tabs 夹层会形成 `ResizeObserver` 自反馈（表格无限撑高 ✗）⇒ 列表容器保持"确定高度链"（与设备台账同构）✓。
7. **vue-i18n 文案里的裸 `{`/`}`** 会在渲染期抛 SyntaxError ⇒ 整棵子树空白 ✗；有编译门禁 ✓。

## 7. 关键文档索引

`docs/TASK-BOARD.md`（进度台账）· `docs/PLATFORM-GAP-REPORT-2026-09-28.md`（三方差距审计）· `docs/OPENAPI-DESIGN.md` + `docs/OPENAPI-PREFLIGHT.md` · `docs/EMQX-TLS-DESIGN.md` · `docs/ALERTING-DESIGN.md` · `docs/EMQX-INGRESS-DESIGN.md` / `EMQX-INTEGRATION.md` / `EMQX-DEPLOY.md` · `deploy/PROD-OPS-NOTES.md`（§10 邮件配置）· `docs/DEPLOY-BACKEND.md` · `docs/DEPLOY-CREDENTIAL-HYGIENE.md` · `AGENTS.md §5`（三机坐标与部署纪律）
