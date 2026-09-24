# 反哺 starter 的需求清单（由 ypbin-iot 侧汇总）

> **用途**：本文件是**交给 `ypbin-starter` 会话的正式交接材料**——在那边新开会话时，把本文档（或对应 issue）
> 直接给它即可动手，不需要它先读 ypbin-iot 的上下文。
>
> 维护约定：每条包含 **现象 / 证据 / 影响 / 期望能力 / 验收标准 / 会被替换掉的临时实现**；
> 关闭本条时，请同时在 ypbin-iot 的 ROADMAP 对应条目上注明「starter 已支持（版本/PR）」。
> 最后更新：2026-09-24（ypbin-iot `aa021b1`）。

---

## SF-1（高｜安全）微服务下游的 `@SaCheckPermission` 实际不生效：注解鉴权与登录拦截被同一个开关绑死

**现象**：微服务下游服务（`ypbin-system` / `ypbin-iot` / `ypbin-ai` / `ypbin-access` …）在 Nacos 里配置
`ypbin.security.interceptor: false`，而 Sa-Token 的**注解鉴权（`@SaCheckPermission` / `@SaCheckRole` 等）
正是由该开关装配的 `SaInterceptor` 执行** ⇒ 这些服务的权限码**全部是装饰性的**。

**证据（外委独立复核，机制层实证 + 类路径穷举）**
1. `SecurityAutoConfiguration.saTokenWebConfigurer` 带
   `@ConditionalOnProperty(prefix = "ypbin.security", name = "interceptor", havingValue = "true", matchIfMissing = true)`；
   探针实测：`interceptor=false` ⇒ `saTokenWebConfigurer` bean 数 **0**；`true` ⇒ **1**（非恒真对照）。
2. 类路径**没有** `sa-token-spring-aop`（AOP 版注解鉴权）；解包 `~/m2repo/cn/ypbin` 全部 jar，
   引用 `SaInterceptor` 的 ypbin 类只有 `SecurityAutoConfiguration` 与 `SaTokenWebConfigurer$1`。
3. Sa-Token 官方文档明确「注解鉴权由全局拦截器完成，你必须手动注册」（一手来源：dromara/Sa-Token
   官方文档仓 `sa-token-doc/use/at-check.md`，访问 2026-07-30 前后一致）。
4. 因此 `PermissionProvider`（`IotPermissionProvider` 之类）这条数据链是**空转**的：它的类注释写着
   「不实现它会让本服务的所有 `@SaCheckPermission` 端点必然 403」——**只在拦截器开启时成立**。

**为什么会被关掉（不是随手写的）**：微服务下游**没有 Sa-Token 会话**（身份来自网关注入的身份头 →
`IdentityContext`），`SaInterceptor` 默认的 `StpUtil.checkLogin()` 在下游必然失败 ⇒ 早先只能把整个拦截器关掉。
starter 源码注释也明确「微服务下游走 `IdentityContext`、单体走 `LoginHelper`」——问题在于**关拦截器同时关掉了注解鉴权**。

**影响（安全语义，不是"少个校验"）**：任何**已登录**的用户都能调用带权限码的写端点。
以 ypbin-iot 为例：维护窗口会把断档从可用率的**分子与分母同时**剔除 ⇒ 无权限者可声明窗口「洗掉」断档、
把可用率抬到 100%。同构风险存在于所有下游业务服务。

**期望能力（二选一，推荐 A）**
- **A（推荐）给下游服务一个以 `IdentityContext` 为基准的注解鉴权拦截器**：保留 `SaInterceptor`
  （从而保留注解鉴权），但把**登录判据**换成 `IdentityContext.isLogin()`，并让 `StpUtil.getLoginId()` /
  `StpInterface` 从身份头解析（即提供**身份头 → Sa-Token 上下文**的桥）。业务代码零改。
- **B 把「登录拦截」与「权限注解校验」拆成两个独立开关**（例如
  `ypbin.security.interceptor` 只管登录、新增 `ypbin.security.permission-check` 管注解校验），
  下游只关前者。语义需在文档里写清（哪些注解在哪种模式下生效）。

**验收标准**
1. starter 侧有测试证明：**无 Sa-Token 会话、仅设置 `IdentityContext`** 时，`@SaCheckPermission` 能按
   `PermissionProvider` 的返回正确放行/拒绝（正向 + 负向各一例，负向要能复现"缺码被拒"）；
2. 平台超管的 `*:*:*` 通配语义**必须保留**（这是本仓踩过的真实回归：只做 `contains` 会把平台管理员挡在门外）；
3. `ypbin-iot` 侧删掉临时防线 `IotPermissionGuard` 后，其单测/IT 仍全绿（并在 ROADMAP 四点十六 标注关闭）。

**会被替换掉的临时实现**：`ypbin-iot` 的 `IotPermissionGuard`（显式、fail-closed 校验三个维护窗口端点，
含「不屏蔽超管通配」的用例）——它存在的唯一理由就是本条缺陷；starter 支持后应删除。

**关联文档**：ypbin-iot `docs/IOT-ROADMAP.md` 四点十六（含完整证据、危害与两条候选）。

---

## SF-2（中）`@Idempotent` 对「没有 equals/hashCode 的 Req DTO」形同虚设

**现象**：`IdempotentAspect` 的默认幂等键是
`目标类名#方法名 + Arrays.deepHashCode(point.getArgs())`；而本仓规范**不允许**给 DTO 加 `@Data`
（Req/Resp 一律 `@Getter @Setter`，避免污染 equals/hashCode）⇒ 两次**内容完全相同**的请求会得到**不同**的键，
幂等注解不生效。

**证据（外委探针实测）**
```
PROBE_IDEMPOTENT_REQ_EQUALS >>> first.equals(second)=false
PROBE_IDEMPOTENT_OPEN_KEYS  >>> [...#open:1167792281, ...#open:1668004826]   # 同内容不同键
PROBE_IDEMPOTENT_CLOSE_KEYS >>> [...#close:73, ...#close:73]                # Long 参数按值，正常
```
影响面：**本仓全部带 Req 的写端点**（`IotDeviceController`、`IotProductController`、
`IotMaintenanceWindowController` 等），实际防重只能靠业务自己的唯一性/重叠校验。

**期望能力（任一）**
- 默认键改为**按字段值**生成（例如对参数做 JSON/反射指纹，或对 `record`/POJO 逐字段 hash），
  并保留 `key = SpEL` 覆盖通道；
- 或提供 `@IdempotentKey`（标在参数/字段上）与「幂等键默认取哪些字段」的文档化策略；
- 无论哪种，都要在文档里写明**是否需要用户维度**（当前 javadoc 提示「由宿主在 SpEL 里带上 `#userId`」）。

**验收标准**：两次内容相同、**不同用户**/相同用户的请求，幂等行为与文档一致（命中同一键），并有 starter 测试。

**会被替换掉的临时实现**：无（本仓只在 ROADMAP 四点十六 登记为「系统性限制」，未自造 workaround）。

---

## SF-3（低｜DX）`LoginUser` 的字段名与常见用法不一致

**现象**：`cn.ypbin.starter.security.core.LoginUser` 的字段是 `id`（`getId()`），而业务侧直觉会写
`setUserId(...)`；本仓在写测试时踩到（编译失败后改为 `new LoginUser(userId, username)`）。
另有 `cn.ypbin.starter.security.identity.IdentityContext` 与 `core.LoginUser` **同名类的两个包**，
容易 import 错（实测一次）。

**期望能力（可选）**：`LoginUser` 增加 `getUserId()`/`setUserId()` 别名（或文档里显著说明使用 `id`）；
`IdentityContext` 与 `LoginUser` 的包归属在 javadoc 里互指，减少误用。

**验收标准**：javadoc 明确；不引入破坏性变更（别名即可）。

---

## 附：跨仓（非 starter）但同样需要上游配合的一条

**UP-1（中）admin 仓的 Sync Whitelist 不覆盖 `.github/**`**：`ypbin-iot`（admin 的 fork）想加一条 CI 门禁时，
**不能**改继承来的 `.github/workflows/ci.yml`（白名单只有 7 个文件），只能**新增**一个 workflow 文件。
若上游希望下游能统一加固 CI，建议把 `.github/workflows/**`（或至少「新增文件」）纳入白名单语义并写进 `SYNC.md`。
