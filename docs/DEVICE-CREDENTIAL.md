# 设备凭据：签发 / 查看 / 重置 / 吊销（+ 接入信息 + 校验）

> 建立于 **2026-09-27**。设计依据：`docs/EMQX-INGRESS-DESIGN.md` §5.3（凭据生命周期）、§5.1（主题规范）、
> 附录 A（SQL 草案），实施项 **P0-A-3**。
> 本文只写**实际实现**的口径：与设计不一致的地方**单列一节**说明差异、理由与迁移路径（见 §2）。

---

## 0. 结论先行

| # | 结论 |
|---|---|
| **C1** | 4 个设计端点 + 1 个内部校验端点已落地：`POST/GET/DELETE /iot/devices/{id}/credential`、`GET /iot/devices/{id}/connection`、`POST /internal/device-credential/verify`。 |
| **C2** | 口令**只在签发响应里出现一次**；服务端只存 `sha256(password + salt)`（盐后缀，与 EMQX 内置库同口径）；查看/接入信息端点**结构上不存在**秘密字段。 |
| **C3** | 🔴 **与设计的一处有意差异**：设计假设「哈希只存在 EMQX 内置库里」⇒ 平台侧不存任何秘密。本环境**未部署 EMQX**（资源决策见设计文档末节），照设计实现会使「吊销后设备不得再认证成功」**没有任何可执行的判据**。因此平台侧**自持哈希**（放独立表 `iot_device_credential`），校验走内部端点；broker 接入后这两列可直接 import 进 EMQX。详见 §2。 |
| **C4** | 设备口令长度取自设计已有的配置键 `ypbin.emqx.credential-password-length`（默认 **32 字节** ⇒ base64url 43 字符）。 |
| **C5** | 「吊销后不可用」的**判据强度**：平台侧校验（真实哈希比对）**强**；broker 侧（EMQX 拒绝 CONNECT）**未验证**——本环境没有 broker。见 §7。 |

---

## 1. 端点与权限

| 方法 | 路径（客户端经网关） | 权限码 | 语义 |
|---|---|---|---|
| `POST` | `/iot/devices/{deviceId}/credential` | `iot:credential:issue` | **签发或轮换**（= 重置）：版本 +1、返回**一次性明文**、旧口令立即失效 |
| `GET` | `/iot/devices/{deviceId}/credential` | `iot:credential:get` | **只回元信息**：username / 版本 / 签发时刻 / 吊销时刻 / 是否已签发 / 是否可用 |
| `DELETE` | `/iot/devices/{deviceId}/credential` | `iot:credential:revoke` | **吊销**（幂等）：清空秘密列、置吊销时刻、清空凭据引用 |
| `GET` | `/iot/devices/{deviceId}/connection` | `iot:credential:get` | 接入信息装配：broker 地址/TLS、username、clientId、上下行主题前缀（**不含口令**） |
| `POST` | `/internal/device-credential/verify` | —（`X-Internal-Token` 守卫） | 校验 username + 口令 → `allow/deny` + 原因码 |

- **幂等口径（生产实测后定稿）**：
  - `POST /credential` 带 `@Idempotent`（默认窗口 **5 秒**）：连点两次会让调用方抄到口令 A、而库里生效的是口令 B
    ⇒「抄对了却连不上」。代价是**窗口内不能连续轮换两次**，确需立刻再换请等过一个窗口（验收脚本里就等了 6 秒）。
  - `DELETE /credential` **不加** `@Idempotent`：设计 P0-3 要求「重复 DELETE 返回 200（幂等）」，
    而该注解会把窗口内的重复调用判成 `R.code=409`（生产实测：秒内重复 DELETE 得到 409「请勿重复提交」）⇒ 与契约冲突。
    吊销的幂等性由服务层实现（已吊销 ⇒ 直接返回、不改写首次吊销时刻），重复调用实测返回 `code=200`。
- 签发端点带 `@Log` 并**显式排除请求体与响应体**。
- 内部端点在 `/internal/**` 下 ⇒ 由 `InternalTokenGuardWebConfig` 的守卫保护（凭证未配置即 fail-closed 拒绝）。
  **它是为了「让吊销可验证」而开的**，同时是将来 EMQX **HTTP 认证源**（官方支持）的后端形态。

---

## 2. 与设计的差异（必须让评审看到）

| 维度 | 设计（`EMQX-INGRESS-DESIGN.md`） | 本实现 | 为什么 |
|---|---|---|---|
| 秘密存放 | 只在 EMQX 内置库；平台侧只存非密引用 `credential_ref` | **平台侧自持哈希**（独立表 `iot_device_credential`，`sha256` + 盐后缀） | 本环境**未部署 EMQX**。照设计实现 ⇒ 平台侧没有任何可校验的东西 ⇒「吊销后不得再认证成功」只能靠「那列非空」自证（那是**状态**不是**行为**）。要留下可执行判据，必须有一处能比对口令 |
| 哈希形态 | EMQX 内置库的 `sha256` + `salt_position=suffix` | **同口径**（`password_algo='sha256-suffix'`；盐按**存储形态 hex 字符串**参与拼接） | 让 `password_hash`/`password_salt` 将来能**直接** import 进 EMQX（官方 `import_users` 收 `password_hash` + `salt`）。口径差一点就会变成「平台侧通过、broker 侧连不上」 |
| 表结构 | `iot_device` + 3 列（`credential_version`/`credential_issued_at`/`credential_revoked_at`） | **3 列完全按设计**；秘密另放一张表 | 秘密不放进 `iot_device`：那张实体在分页/详情/接入规格下发里会被整体序列化或下发，秘密一旦成为它的字段，泄露与否就取决于「下游有没有人顺手序列化」 |
| 吊销动作 | 删除 EMQX 用户 + 踢会话；`credential_ref` 置空 | 清空 `password_salt`/`password_hash`（空串）+ 置吊销时刻 + `credential_ref` 置空 | 无 broker 时的等价动作。空哈希**不可能**与任何口令匹配（`matches` 有显式空值分支） |
| 4 个端点 | `POST/GET/DELETE …/credential`、`GET …/connection` | 同左（**一个不少、语义不变**） | — |
| 第 5 个端点 | 设计中无 | **新增内部 `verify`** | 见上：没有它，「吊销后不可用」不可验证 |

> 🔴 **该差异需要用户确认**（R6 L3）：它把「平台自持一份设备凭据哈希」这个**新的秘密存储面**引入系统。
> 选型理由与代价都写在上面；若用户判定「未接 broker 期间不应自持哈希」，退化方案是**只留元信息**
> （签发仍返回一次性明文，但平台无法自证吊销）——那会让本功能的验收退化为「状态判据」。

---

## 3. 数据模型

```sql
-- iot_device（三列，与设计逐字一致）
credential_version    INT      NULL  -- 每次签发/轮换 +1；null=从未签发
credential_issued_at  DATETIME NULL  -- 当前凭据签发时刻
credential_revoked_at DATETIME NULL  -- 吊销时刻；非空 ⇒ 不得再认证成功

-- iot_device_credential（秘密侧；每设备一行）
id, tenant_id, device_id, credential_version, username,
password_algo,   -- 'sha256-suffix'
password_salt,   -- hex；吊销后为空串
password_hash,   -- hex = sha256(password + salt)；吊销后为空串
+ 基类列（create_*/update_*/status/is_deleted）
UNIQUE KEY (tenant_id, device_id)
```

- 本表是**租户表**：**不要**加进 `ypbin.tenant.ignore-tables`（`fail-on-missing-tenant: true`）。
- **不使用删除语义**：轮换 = 原地更新；吊销 = 清空秘密列。因此本表不依赖逻辑删除，
  也不会出现「逻辑删除行占着唯一键、重签插不进去」的坑。
- SQL 双写：`deploy/sql/007-iot-data.sql` 末尾 + `deploy/sql/migration/2026-09-30-iot-device-credential.sql`
  （跑 `tools/check-iot-sql-equivalence.sh` 退出码 0）。
- 回滚：`deploy/sql/rollback/2026-09-30-iot-device-credential-rollback.sql`（**不可逆**：哈希与吊销审计一并消失）。

---

## 4. 状态机（校验判据）

`POST /internal/device-credential/verify` 按**固定顺序**判定，先命中先返回：

| 序 | 条件 | 结果 | 原因码 |
|---|---|---|---|
| 1 | username 不匹配 `^[0-9]+\.[0-9]+$` | deny | `malformed-username` |
| 2 | 该租户下设备不存在（**跨租户即此**） | deny | `device-not-found` |
| 3 | `credential_revoked_at` 非空 | deny | `revoked` |
| 4 | 从未签发（版本或引用为空） | deny | `not-issued` |
| 5 | 无凭据行 / 秘密列为空 | deny | `secret-missing` |
| 6 | 凭据行版本 ≠ 设备当前版本 | deny | `version-stale` |
| 7 | 哈希不匹配 | deny | `bad-password` |
| 8 | 以上都过 | **allow** | — |

- 「重置」的判据就是第 6/7 条：**版本 +1 且哈希换代** ⇒ 旧口令必落在 `bad-password`。
- 「吊销」的判据是第 3 条：短路在哈希之前，即使秘密列没清干净也不会放行。
- 校验响应**只有** `allowed/result/reason/deviceId/credentialVersion`——没有任何秘密字段。

---

## 5. 安全不变量（都有门禁钉住）

| 不变量 | 钉它的东西 |
|---|---|
| 查看/接入信息响应**结构上**无秘密字段 | `DeviceCredentialSecretLeakTest`（字段名 + JSON 键集合，多一个字段即红） |
| 设备实体上不存在秘密字段 | 同上（并有正向对照：凭据实体必须真的持有 `passwordHash`） |
| 明文只落响应、库里只有哈希 | `DeviceCredentialServiceImplTest#issueMustReturnOneTimePlaintextAndPersistOnlyHash`（逐字段断言不等于口令） |
| 日志里不得出现口令/哈希/盐 | `DeviceCredentialSecretLeakTest#serviceLogsMustNotMentionSecrets`（源码级扫 `log.` 行）+ 端点 `@Log` 排除体 |
| 重新签发必须把吊销时刻清成 NULL | `deviceStateUpdateMustNotUseEntityUpdate`（`updateById(entity)` 会静默跳过 null 字段 ⇒ 设备永远吊销而接口 200） |
| 权限码逐一对应、写操作幂等 | `DeviceCredentialControllerGateTest` + `IotPermissionCodeGateTest`（代码↔SQL 双向） |
| 菜单必须被授权 | `IotMaintenanceAdminGateTest`（platform_only=0 ⇒ 同时进 `sys_role_menu` 与 `sys_template_menu`） |
| 哈希口径与 EMQX 一致 | `DevicePasswordHasherTest#hashMustMatchEmqxSuffixSaltContract`（已知答案：`sha256("pw"+"abcd")`） |

> 以上门禁都做过**变异验证**（把哈希暴露到查看响应 / 把哈希写进日志 / 改用 `updateById` /
> 去掉 `@Log` 排除项 ⇒ 相应用例转红）。

---

## 6. 运维手册

### 6.1 签发（**口令只在此刻可见**）

```bash
# 经网关（需登录态 + iot:credential:issue 权限）
curl -s -X POST -H "Authorization: <登录态>" \
  http://127.0.0.1:18080/iot/devices/<deviceId>/credential
# 响应里的 password 就是设备要配置的那一份；关掉/刷新即不可再取，丢了就再签一次（旧口令随即失效）
```

### 6.2 查看元信息 / 吊销 / 接入信息

```bash
curl -s -H "Authorization: <登录态>" http://127.0.0.1:18080/iot/devices/<deviceId>/credential
curl -s -X DELETE -H "Authorization: <登录态>" http://127.0.0.1:18080/iot/devices/<deviceId>/credential
curl -s -H "Authorization: <登录态>" http://127.0.0.1:18080/iot/devices/<deviceId>/connection
```

### 6.3 平台侧校验（内部，供 broker / 排障）

```bash
# 冒号左边的值只从 deploy/.env 读入，不进 argv
docker exec -e TOKEN="$INTERNAL_TOKEN" ypbin-iot sh -c '
  curl -s -X POST http://127.0.0.1:18084/internal/device-credential/verify \
    -H "X-Internal-Token: $TOKEN" -H "Content-Type: application/json" \
    -d "{\"username\":\"<tenantId>.<deviceId>\",\"password\":\"<一次性口令>\"}"'
# 期望：{\"code\":0,...,\"data\":{\"allowed\":true,\"result\":\"allow\",...}}
# 吊销后再跑同一命令：allowed=false，reason=revoked
```

### 6.4 直接看库（**不含口令**）

```bash
docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t ypbin_admin -e "
  SELECT id, credential_version, credential_issued_at, credential_revoked_at,
         CHAR_LENGTH(credential_ref) AS ref_len FROM iot_device WHERE id=<deviceId>;
  SELECT device_id, credential_version, username, password_algo,
         CHAR_LENGTH(password_salt) AS salt_len, CHAR_LENGTH(password_hash) AS hash_len
    FROM iot_device_credential WHERE device_id=<deviceId>;"'
```

> `iot_device_credential` 的 `password_hash`/`password_salt` **按凭据对待**：导出前先想清楚用途，
> 不要整表 dump 到工单/聊天/文档里（只给长度与指纹）。

### 6.5 回滚

```bash
# ① 数据层（不可逆：哈希与吊销审计消失）
docker exec -i ypbin-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ypbin_admin' \
  < deploy/sql/rollback/2026-09-30-iot-device-credential-rollback.sql
# ② 服务层：把 jar 换成上一版（tag/jar 备份见 docs/DEPLOY-BACKEND.md §2）
# ③ 若已接入 broker：平台回滚后 EMQX 侧用户仍在 ⇒ 需一并清理（当前环境无 broker）
```

---

## 7. 未验证项（如实登记）

| # | 未验证 | 为什么 | 强度 |
|---|---|---|---|
| **U1** | **broker 侧**的「吊销后连不上」（EMQX 拒绝 CONNECT） | 本环境**未部署 EMQX**；按设计 D3 的降级形态，broker 接入属 P0-B | 平台侧判据（真实哈希比对）**已实测**；broker 侧**未验证** |
| **U2** | 吊销/重置对**在线连接**的影响（踢会话） | 同上（无 broker、无在线设备会话） | **未验证** |
| **U3** | `password_hash`/`password_salt` 直接 import 进 EMQX 内置库的**实测** | 同上 | 口径由**已知答案单测**钉住，端到端**未验证** |
| **U4** | 端到端越权用例（A 租户 token 访问 B 租户设备 → 拒绝） | 需要真实登录态 + 双租户数据；本机纯单测跑不了（与 M-1 同一未关闭项） | 单测覆盖「跨租户 username ⇒ device-not-found」；**HTTP 层未验证** |
| **U5** | `@Idempotent` 的重复提交窗口在**签发**语义下是否够用 | 未实测窗口与用户重试节奏 | **未验证**（重复提交会被幂等拦截；绕过幂等连点两次会连发两次轮换） |
