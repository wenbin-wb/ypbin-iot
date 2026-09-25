# 反哺 starter 的需求清单（由 ypbin-iot 侧汇总）

> **用途**：本文件是**交给 `ypbin-starter` 会话的正式交接材料**——在那边新开会话时，把本文档（或对应 issue）
> 直接给它即可动手，不需要它先读 ypbin-iot 的上下文。
>
> 维护约定：每条包含 **现象 / 证据 / 影响 / 期望能力 / 验收标准 / 会被替换掉的临时实现**；
> 关闭本条时，请同时在 ypbin-iot 的 ROADMAP 对应条目上注明「starter 已支持（版本/PR）」。
> 最后更新：2026-09-25 —— **SF-1~SF-3 已关闭**：starter **3.5.0**（2026-09-24 发布；PR #51 / 合并 `f3ab2f9`，
> 四轮外委复核后 PASS）；本仓已同步升级 starter 版本至 3.5.0 并删除 SF-1 的临时防线 `IotPermissionGuard`。
> **SF-4 为新增未关闭项（2026-09-25，高｜可用性）：3.5.0 引入的 `IdentityStpLogic` 让 identity 模式下的 auth 登录结构性失败。**
> starter 侧修复的**目标版本是 3.5.1**（starter 工作树当前 `revision` = `3.5.1-SNAPSHOT`；本仓当前用 3.5.0）。

---

## SF-1（高｜安全）微服务下游的 `@SaCheckPermission` 实际不生效：注解鉴权与登录拦截被同一个开关绑死

> **状态：✅ 已支持（starter 3.5.0，PR #51）** —— `annotation-check` 与 `interceptor` 已拆成两个独立开关，
> 并新增 `IdentityStpLogic` 身份头账号体系桥（`identity.enabled=true` 时把身份头接进 Sa-Token 账号解析）；
> 本仓已删除临时防线 `IotPermissionGuard`，单测 162 项全绿。

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
含「不屏蔽超管通配」的用例）——它存在的唯一理由就是本条缺陷。**已于 2026-09-24 随 starter 3.5.0 升级删除**：
三个端点上的 `@SaCheckPermission` 现由 starter 注解鉴权真正执行；配套源码门禁也已从
「必须调用临时防线」迁移为「逐方法校验注解权限码正确 + 禁止回退到临时防线」。

**关联文档**：ypbin-iot `docs/IOT-ROADMAP.md` 四点十六（含完整证据、危害与两条候选）。

---

## SF-2（中）`@Idempotent` 对「没有 equals/hashCode 的 Req DTO」形同虚设

> **状态：✅ 已支持（starter 3.5.0，PR #51）** —— 默认幂等键改为按字段值展开的 SHA-256 摘要
> （`ArgumentFingerprint`），同内容必得同键，且键里不含入参明文。

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

> **状态：✅ 已支持（starter 3.5.0，PR #51）** —— `LoginUser` 新增 `getUserId()/setUserId()` 等价别名，
> 并与 `IdentityContext` 的 Javadoc 互相指明职责差异。

**现象**：`cn.ypbin.starter.security.core.LoginUser` 的字段是 `id`（`getId()`），而业务侧直觉会写
`setUserId(...)`；本仓在写测试时踩到（编译失败后改为 `new LoginUser(userId, username)`）。
另有 `cn.ypbin.starter.security.identity.IdentityContext` 与 `core.LoginUser` **同名类的两个包**，
容易 import 错（实测一次）。

**期望能力（可选）**：`LoginUser` 增加 `getUserId()`/`setUserId()` 别名（或文档里显著说明使用 `id`）；
`IdentityContext` 与 `LoginUser` 的包归属在 javadoc 里互指，减少误用。

**验收标准**：javadoc 明确；不引入破坏性变更（别名即可）。

---

## SF-4（高｜可用性）identity 模式下 auth 登录结构性失败：token 生成 12 次重试后抛异常

> **状态：⬜ 未关闭（2026-09-25 提出；starter 侧修复目标版本 3.5.1）** —— starter 仓 issue：
> **https://github.com/wenbin-wb/ypbin-starter/issues/52**（标题与本节同）。
> 前置条目：issue #50 / PR #51（SF-1~SF-3，随 starter 3.5.0 交付；**本缺陷正是 3.5.0 引入的**）。

**现象**：`ypbin.security.identity.enabled=true` 的服务**登录功能结构性不可用**。
以 `ypbin-auth` 为例，`POST /api/auth/login` **恒返回** `{"code":403,"message":"当前鉴权模式下该操作不可用"}`；
`ypbin-auth` 日志里的真实异常是
`SaTokenException: token 生成失败，已尝试12次，生成算法过于简单或资源池已耗尽`。
**任何账号、任何密码、任何时刻都登录不上**——是结构性失败，不是"密码错/用户不存在"这类业务失败。

**根因链（逐环给出可复现的一手证据）**

**环 1｜sa-token-core 1.46.0 的"该 token 可用"判据是"严格 `== null`"**

`StpLogic.distUsableToken(Object, SaLoginParameter)`（建 token 的实际入口）把
`getLoginIdNotHandle(token) == null` 作为**唯一性判据**传给生成策略。字节码实证：

```bash
cd /tmp && mkdir -p satoken && cd satoken \
  && jar xf ~/m2repo/cn/dev33/sa-token-core/1.46.0/sa-token-core-1.46.0.jar \
       cn/dev33/satoken/stp/StpLogic.class cn/dev33/satoken/strategy/SaStrategy.class \
  && javap -p -c -constants cn/dev33/satoken/stp/StpLogic.class | grep -A8 'lambda\$distUsableToken\$2'
```
```
private java.lang.Boolean lambda$distUsableToken$2(java.lang.String);
   2: invokevirtual #829  // Method getLoginIdNotHandle:(Ljava/lang/String;)Ljava/lang/String;
   5: ifnonnull     12
   8: iconst_1            // true = 可用  ⇒ 只有"返回 null"才算这个候选 token 可用
```

> **与任务单原文的差异（更精确，不改变结论）**：任务单把该判据记在 `SaStrategy.generateUniqueToken` 名下。
> 字节码显示**判据函数定义在 `StpLogic` 的 lambda 里**（上），再作为第 4 个参数传进 SaStrategy 的默认策略（下）。

**环 2｜重试 12 次后抛异常，发生在 `SaStrategy` 的默认策略里**

```bash
javap -p -c -constants cn/dev33/satoken/strategy/SaStrategy.class | grep -A45 'lambda\$new\$3'
# 43: putfield  generateUniqueToken  ← SaStrategy.<init> 用 lambda$new$3 作为默认策略
```
```
 48: new  cn/dev33/satoken/exception/SaTokenException
 63: ldc  " 生成失败，已尝试"
 73: ldc  "次，生成算法过于简单或资源池已耗尽"
 84: athrow
```
循环上界来自 `maxTryTimes`：`SaTokenConfig.<init>` 里 `bipush 12 → putfield maxTryTimes`
（`javap -p -c -constants cn/dev33/satoken/config/SaTokenConfig.class`）⇒ **默认 12 次**，与生产日志"已尝试12次"吻合。

**环 3｜starter 3.5.0 的 `IdentityStpLogic#getLoginIdNotHandle` 在"无当前身份"时返回空串，而不是 `null`**

源码（`ypbin-starter-security/src/main/java/cn/ypbin/starter/security/identity/IdentityStpLogic.java`）：
- `:63` `private static final String NO_IDENTITY_TOKEN = "";`
- `:90-96` `getLoginIdNotHandle`：`return token.equals(tokenValue) ? token : NO_IDENTITY_TOKEN;`（`token` 即 `currentToken()`）
- `:109-111` `currentToken()`：`return IdentityContext.getUserId().map(String::valueOf).orElse(NO_IDENTITY_TOKEN);`
- `:53` 类级 `@since 2026-09-24`；`:60-61` 的 Javadoc 明确写着"用空串而不是 `null` 表达无身份"——**这正是缺陷的来源**。

**制品级复核（不只看工作树）**：3.5.0 的 jar 里就是这份实现：
```bash
mkdir -p /tmp/sf4jar && cd /tmp/sf4jar \
  && jar xf ~/m2repo/cn/ypbin/ypbin-starter-security/3.5.0/ypbin-starter-security-3.5.0.jar \
       cn/ypbin/starter/security/identity/IdentityStpLogic.class \
  && javap -p -c -constants cn/ypbin/starter/security/identity/IdentityStpLogic.class
```
```
  private static final java.lang.String NO_IDENTITY_TOKEN = "";
  public java.lang.String getLoginIdNotHandle(java.lang.String);
       6: invokevirtual #17  // Method java/lang/String.equals:(Ljava/lang/Object;)Z
       9: ifeq          16
      16: ldc           #23  // String        ← 不相等分支返回「空串」
  private static java.lang.String currentToken();
      11: ldc           #23  // String        ← orElse 也是「空串」
```

**环 4｜⇒ 判据恒不成立（结构性）**

- **无身份时**（auth 登录时的真实处境）：`currentToken()` 恒为 `""`，于是 `getLoginIdNotHandle(候选token)`
  要么返回 `""`（不相等分支）、要么返回候选值本身（相等分支）——**任何输入都不可能得到 `null`**；
- **有身份时**同理：返回 `""` 或身份值，同样**绝不为 `null`**。

⇒ 12 次重试必然全部失败 ⇒ **建 token 永远抛异常**（登录/短信登录/社交登录所有入口一起失效）。

**环 5｜本地实证复现（真实 3.5.0 制品 + 真实 sa-token-core 1.46.0，不是推断）**

在 `/tmp/sf4repro/Sf4Repro.java` 里**复刻** `lambda$distUsableToken$2` 的判据（`getLoginIdNotHandle(t) == null`），
并调用**真实的** `SaStrategy.instance.generateUniqueToken.execute(...)`：

```java
import cn.dev33.satoken.strategy.SaStrategy;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import cn.ypbin.starter.security.identity.IdentityStpLogic;
import java.util.concurrent.atomic.AtomicInteger;

public class Sf4Repro {
    public static void main(String[] args) {
        IdentityStpLogic logic = new IdentityStpLogic();

        IdentityContext.clear();                                   // CASE-1：无身份（auth 登录时）
        System.out.println("[CASE-1] getLoginIdNotHandle(\"cand-1\") = "
            + repr(logic.getLoginIdNotHandle("cand-1")));
        AtomicInteger calls = new AtomicInteger();
        try {
            String token = SaStrategy.instance.generateUniqueToken.execute(
                "token", 12,
                () -> "cand-" + calls.incrementAndGet(),
                t -> logic.getLoginIdNotHandle(t) == null);        // = StpLogic 的判据
            System.out.println("[CASE-1] UNEXPECTED: token generated = " + token);
        } catch (Exception ex) {
            System.out.println("[CASE-1] " + ex.getClass().getName() + ": " + ex.getMessage());
        }
        System.out.println("[CASE-1] supplier invoked times = " + calls.get());

        IdentityContext.setLoginUser(new LoginUser(1001L, "tester")); // CASE-2：有身份
        System.out.println("[CASE-2] getLoginIdNotHandle(\"1001\") = " + repr(logic.getLoginIdNotHandle("1001")));
        System.out.println("[CASE-2] getLoginIdNotHandle(\"9999\") = " + repr(logic.getLoginIdNotHandle("9999")));
        IdentityContext.clear();
    }
    private static String repr(String s) { return s == null ? "null" : "\"" + s + "\""; }
}
```
```bash
cd /tmp/sf4repro && CP="$HOME/m2repo/cn/ypbin/ypbin-starter-security/3.5.0/ypbin-starter-security-3.5.0.jar:$HOME/m2repo/cn/dev33/sa-token-core/1.46.0/sa-token-core-1.46.0.jar" \
  && javac -cp "$CP" -d out Sf4Repro.java && java -cp "out:$CP" Sf4Repro
```
```
[CASE-1] no identity -> getLoginIdNotHandle("cand-1") = ""
[CASE-1] predicate (getLoginIdNotHandle(token) == null) = false
[CASE-1] cn.dev33.satoken.exception.SaTokenException: token 生成失败，已尝试12次，生成算法过于简单或资源池已耗尽
[CASE-1] supplier invoked times = 12
[CASE-2] with identity -> getLoginIdNotHandle("1001") = "1001"
[CASE-2] with identity -> getLoginIdNotHandle("9999") = ""
[CASE-2] predicate on matched token = false
```
**与生产日志逐字一致**（异常类型、文案、次数）。

**环 6｜403 响应体的来源（同样是 starter 侧）**

`ypbin-starter-security/.../handler/SaTokenExceptionHandler.java:108-112`：`@ExceptionHandler(SaTokenException.class)`
直接 `return R.fail(GlobalErrorCode.FORBIDDEN.getCode(), "当前鉴权模式下该操作不可用");`（并 `log.warn("[鉴权异常] {}: {}", ...)`）
⇒ 生产上看到的 `code:403` + 该文案，就是"建 token 抛出的 `SaTokenException` 被这个 handler 兜住"，**不是**权限不足。

**生产一手证据**

`ypbin-auth` 容器日志（2026-09-25 部署实例实测，只读复核）：
```
2026-09-25T09:21:30.064+08:00  WARN 7 --- [ypbin-auth] [omcat-handler-3] c.y.s.s.handler.SaTokenExceptionHandler  : [鉴权异常] SaTokenException: token 生成失败，已尝试12次，生成算法过于简单或资源池已耗尽
```

**为什么会被误触发（开关来自共享配置，与设计相互矛盾）**

- `deploy/nacos/ypbin-common.yaml:62-69`：`ypbin.security.identity.enabled: true`，注释写明"**保持 auth/system/ai
  各 Servlet 服务既有行为不变**"——即这个开关是**共享**的，auth 也被它带上；
- `deploy/nacos/ypbin-gateway.yaml:56`：网关的设计注释是"**统一鉴权：校验 token 后清洗外部头、签发内部身份头**"
  ⇒ **建 token 的 auth 必须走经典会话模式**，不能同时启用 identity 模式（identity 模式的语义前提是"身份由上游网关给"）；
- 于是"给下游开 identity"与"auth 自己能登录"被**同一个共享开关绑死**、互斥，而矛盾只在**运行期**以 403 暴露。

**影响（可用性，不是"少个校验"）**

1. auth 的**全部登录入口**不可用（账号密码 / 短信 / 社交）⇒ 前端拿不到 token ⇒ **整个平台无法登录**，后续所有请求 401/403；
2. 故障**表现为"登录接口 403"**，排查者会先怀疑密码、用户状态、权限码，真实原因是"配置结构性矛盾"——
   错误信息与根因相距很远，平均定位成本高（本次即为生产实测暴露）；
3. 影响面 = **任何** `ypbin.security.identity.enabled=true` 且自身承担建 token 职责的服务（当前是 auth；
   后续若有第二个"既是下游、又要签发 token"的服务会同样中招）。

**期望能力（候选，推荐 A）**

- **A（推荐）修正语义：`IdentityStpLogic#getLoginIdNotHandle` 在"没有当前身份"时返回 `null`。**
  无身份就是无——这同时是基类语义（`StpLogic#getLoginIdNotHandle` 直接返回 `SaTokenDao.get(...)`，无键即 `null`）。
  **注意边界**：`getTokenValue()` / `getTokenValueNotCut()` **仍应返回空串**（Sa-Token 的"未登录"判据是
  `SaFoxUtil.isEmpty(...)`，依赖它），**只有 `getLoginIdNotHandle` 这一个方法需要改成 `null`**。
  改前请一并复看它在 1.46.0 的全部调用点（实测：`getLoginId()`、`getLoginIdDefaultNull()`、
  `getLoginIdByTokenNotThinkFreeze()`、`getTerminalInfoByToken()`、`isSafe()`、以及本判据 `lambda$distUsableToken$2`）：
  其中 `getLoginIdByTokenNotThinkFreeze` 会用 `isValidLoginId` 把空串归一成 `null`，**所以只有"直接与 `null` 比"的判据会踩到**——
  这也解释了为什么现有测试全绿而生产登录不通（见验收标准 2）。
  *实现提示（未核实）*：方法签名可能需要 `@Nullable`（父类 `StpLogic` 是未注解的第三方类型，本仓有 NullAway 门禁），
  具体以 starter 侧门禁实测为准。
- **B 让 auth 服务不装配 `IdentityStpLogic`（按服务名/新增互斥开关条件装配），并在装配层做启动期 fail-fast**：
  "启用 identity 模式的服务不得承担建 token 职责"。starter 现有 `@ConditionalOnMissingBean(StpLogic.class)`
  允许宿主自建经典 `StpLogic` 让位（**未实测**），但 ypbin-iot 侧 `ypbin-auth` 是 admin 所有的既有模块
  （`SYNC.md` 第二节「明确不动」清单），**改宿主代码不可行** ⇒ 需要 starter 出开关，而不是让下游自己绕。
- **C 其它更优方案**（例如 identity 模式下整体短路/改走不查 token 表的登录路径）。

> **无论选哪个，都必须满足验收标准 1**：`identity.enabled=true` 的服务**不因此丧失**自身登录能力；
> 若设计上就该禁止，则必须**启动期 fail-fast + 文档写明**，**不得**是运行期的 403。

**验收标准**

1. `identity.enabled=true` 的服务**不因此丧失自身登录能力**（若设计上就该禁止，则必须**启动期 fail-fast + 文档写明**，
   而不是运行期 403）；
2. starter 级单测：
   - **正例**：无身份时 `getLoginIdNotHandle("任意非空token")` 返回 **`null`**；
   - **反例**：有身份（`IdentityContext.setLoginUser(...)`）时，`getLoginIdNotHandle(当前身份token)` 返回身份值；
   - **端到端正向**：无身份时 `StpUtil.login(<userId>)` **能成功建 token**、不再抛 `SaTokenException`
     ——这是本次缺失的那一层：现有 `IdentityStpLogicTest` 只断言 `StpUtil.isLogin()`/`StpUtil.getLoginId()`/
     `getLoginIdByToken(...)` 这类**经 `isEmpty` 归一**的语义，**没有任何一条断言 `getLoginIdNotHandle` 的返回值**，
     于是"空串"满足了"未登录"却违反了"token 可用"判据 ⇒ 测试全绿、生产登录不通；
   - **变异验证**：把 `getLoginIdNotHandle` 改回返回空串 ⇒ 上述用例必须**转红**（否则用例是恒真的装饰）；
3. `ypbin-iot` 侧撤掉临时规避（auth 不再需要 `identity.enabled: false` 覆盖）后，`POST /api/auth/login` 实测 **200**，
   且 system / iot 等下游的注解鉴权仍正常（SF-1 的成果不被回退）。

**会被替换掉的临时实现**：**部署侧临时规避（不是代码）**——生产 Nacos 上对 `ypbin-auth.yaml` 做**服务级覆盖**
`ypbin.security.identity.enabled: false`（**不动**共享的 `ypbin-common.yaml`，system/ai/iot 保持 `true`），
覆盖后登录**立刻恢复 200**；覆盖前的原始内容备份在服务器 `/opt/ypbin/nacos-ypbin-auth.yaml.bak`（本次只读复核：文件存在，2060 字节）。
**这条规避是临时的，starter 修好后必须删除**：它顺手关掉了 auth 自己的身份头信任，属于"关掉一个能力换可用性"。
> **本仓约束（重要）**：`deploy/nacos/ypbin-auth.yaml` 是 **admin 所有的既有文件**，**不在** ypbin-iot 的 SYNC 白名单
> （当前 8 个文件，见 `SYNC.md` 第二节与 `.github/workflows/sync-whitelist.yml`）内 ⇒ 在仓里改它会让 `Sync Whitelist` 门禁转红。
> 因此该覆盖目前**只存在于部署实例的 Nacos 配置**，仓内文件**未同步**（可复现校验：`grep -n identity deploy/nacos/ypbin-auth.yaml`
> 当前**无输出**）。要把它固化进仓，必须先扩白名单（新增一个长期冲突点），这也是本条**必须由 starter 修**而非本仓自行解决的
> 直接原因。

**关联文档**：ypbin-iot `docs/IOT-ROADMAP.md` 四点十八（索引表 SF-4 行）；
starter 侧前序：issue #50 / PR #51（SF-1~SF-3，3.5.0）。

---

## 附：跨仓（非 starter）但同样需要上游配合的一条

**UP-1（中）admin 仓的 Sync Whitelist 不覆盖 `.github/**`**：`ypbin-iot`（admin 的 fork）想加一条 CI 门禁时，
**不能**改继承来的 `.github/workflows/ci.yml`（白名单只有 7 个文件），只能**新增**一个 workflow 文件。
若上游希望下游能统一加固 CI，建议把 `.github/workflows/**`（或至少「新增文件」）纳入白名单语义并写进 `SYNC.md`。
