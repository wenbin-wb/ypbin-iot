# 反哺 starter 的需求清单（由 ypbin-iot 侧汇总）

> **用途**：本文件是**交给 `ypbin-starter` 会话的正式交接材料**——在那边新开会话时，把本文档（或对应 issue）
> 直接给它即可动手，不需要它先读 ypbin-iot 的上下文。
>
> 维护约定：每条包含 **现象 / 证据 / 影响 / 期望能力 / 验收标准 / 会被替换掉的临时实现**；
> 关闭本条时，请同时在 ypbin-iot 的 ROADMAP 对应条目上注明「starter 已支持（版本/PR）」。
> 最后更新：2026-09-25 —— **SF-1~SF-3 已关闭**：starter **3.5.0**（2026-09-24 发布；PR #51 / 合并 `f3ab2f9`，
> 四轮外委复核后 PASS）；本仓已同步升级 starter 版本至 3.5.0 并删除 SF-1 的临时防线 `IotPermissionGuard`。
> **SF-4 为新增未关闭项（2026-09-25，高｜可用性）：3.5.0 引入的 `IdentityStpLogic` 让 identity 模式下的 auth 登录结构性失败。**
> **SF-5 为新增未关闭项（2026-09-25，高｜安全）：下游 `IdentityHeaderFilter` 不校验网关身份头签名（`X-Gateway-Signed`）⇒ 直连下游端口即可伪造身份、越过网关鉴权。**
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

/**
 * SF-4 本地实证：用真实 ypbin-starter-security 3.5.0 制品 + sa-token-core 1.46.0
 * 复刻 StpLogic.lambda$distUsableToken$2 的判据，走真实 SaStrategy.generateUniqueToken 策略。
 */
public class Sf4Repro {

    public static void main(String[] args) {
        IdentityStpLogic logic = new IdentityStpLogic();

        // ---- CASE-1：当前无身份（auth 服务登录时的真实处境） ----
        IdentityContext.clear();
        String noIdentity = logic.getLoginIdNotHandle("cand-1");
        System.out.println("[CASE-1] no identity -> getLoginIdNotHandle(\"cand-1\") = " + repr(noIdentity));
        System.out.println("[CASE-1] predicate (getLoginIdNotHandle(token) == null) = "
            + (noIdentity == null));

        AtomicInteger calls = new AtomicInteger();
        try {
            String token = SaStrategy.instance.generateUniqueToken.execute(
                "token", 12,
                () -> "cand-" + calls.incrementAndGet(),
                t -> logic.getLoginIdNotHandle(t) == null);
            System.out.println("[CASE-1] UNEXPECTED: token generated = " + token);
        } catch (Exception ex) {
            System.out.println("[CASE-1] " + ex.getClass().getName() + ": " + ex.getMessage());
        }
        System.out.println("[CASE-1] supplier invoked times = " + calls.get());

        // ---- CASE-2：当前有身份（网关下发身份头后下游服务的处境） ----
        IdentityContext.setLoginUser(new LoginUser(1001L, "tester"));
        String matched = logic.getLoginIdNotHandle("1001");
        String other = logic.getLoginIdNotHandle("9999");
        System.out.println("[CASE-2] with identity -> getLoginIdNotHandle(\"1001\") = " + repr(matched));
        System.out.println("[CASE-2] with identity -> getLoginIdNotHandle(\"9999\") = " + repr(other));
        System.out.println("[CASE-2] predicate on matched token = " + (matched == null));
        IdentityContext.clear();
    }

    private static String repr(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
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
> 上述输出是**上方程序原样编译运行的真实输出**（命令即上一代码块；2026-09-25 复核时逐字复现），
> **与生产日志逐字一致**（异常类型、文案、次数）。

> **一致性纪律（本条曾被独立复核判 FAIL 并整改）**：文档里"程序"与"输出"必须来自同一次运行。
> 整改前贴的程序是被精简过的版本（少了打印判据的两行 `println`），而输出来自完整版本 ⇒ 属**不实引用**。
> 复现校验：把上方 java 代码块原样存为 `Sf4Repro.java`，执行上一代码块，输出必须与本代码块**逐行一致**。

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

## SF-5（高｜安全）微服务下游的 `IdentityHeaderFilter` 不校验网关身份头签名：直连下游端口即可伪造身份（越过网关鉴权）

> **状态：⬜ 未关闭（2026-09-25 提出；starter 侧修复目标版本 3.5.1）** —— starter 仓 issue：
> **https://github.com/wenbin-wb/ypbin-starter/issues/53**（标题与本节同）。

**现象**：微服务 Servlet 下游服务（`ypbin-system` / `ypbin-iot` / `ypbin-ai` 等；**`ypbin-access` 不在其列**，
见环 5 的口径说明）在 `ypbin.security.identity.enabled=true` 下装配
`cn.ypbin.starter.security.identity.IdentityHeaderFilter`，它**只解析** `X-User-Id` / `X-User-Name` /
`X-Tenant-Id` / `X-Dept-Id` / `X-Roles` 五个头并写入 `IdentityContext`，**完全不校验**网关
（`GatewayAuthGlobalFilter`）在签发这些头的同时写出的来源标记 `X-Gateway-Signed`。
⇒ **只要能建立到下游服务端口的 TCP 连接**（完全绕开网关），构造这五个头即可获得任意用户 / 任意租户的完整身份，
网关的 token 校验被整体跳过。starter **已经实现了** trusted-source 机制，但它**只作用于 Feign 出站透传**
（拦截"不可信来源的身份头被二次转发"），**不在身份头被消费的那个入口上**——缺口正在这里。

**证据（逐环一手依据；下列"命令 + 输出"均为 2026-09-25 本机同一次运行）**

**环 1｜`IdentityHeaderFilter` 里没有任何签名 / 来源校验（3.5.0 制品级）**

```bash
cd /tmp && rm -rf sf5jar && mkdir -p sf5jar && cd sf5jar \
  && jar xf ~/m2repo/cn/ypbin/ypbin-starter-security/3.5.0/ypbin-starter-security-3.5.0.jar \
       cn/ypbin/starter/security/identity/IdentityHeaderFilter.class \
       cn/ypbin/starter/security/identity/IdentityHeaders.class \
  && javap -p -constants cn/ypbin/starter/security/identity/IdentityHeaderFilter.class \
  && echo -n "字节码 trusted|signature 命中数: " \
  && { javap -p -c -constants cn/ypbin/starter/security/identity/IdentityHeaderFilter.class | grep -ci "trusted\|signature" || true; } \
  && javap -p -constants cn/ypbin/starter/security/identity/IdentityHeaders.class
```
```
Compiled from "IdentityHeaderFilter.java"
public class cn.ypbin.starter.security.identity.IdentityHeaderFilter extends org.springframework.web.filter.OncePerRequestFilter {
  private static final org.slf4j.Logger log;
  public cn.ypbin.starter.security.identity.IdentityHeaderFilter();
  protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest, jakarta.servlet.http.HttpServletResponse, jakarta.servlet.FilterChain) throws jakarta.servlet.ServletException, java.io.IOException;
  private static java.lang.Long parseLongHeader(java.lang.String, java.lang.String);
  static {};
}
字节码 trusted|signature 命中数: 0
Compiled from "IdentityHeaders.java"
public final class cn.ypbin.starter.security.identity.IdentityHeaders {
  public static final java.lang.String USER_ID = "X-User-Id";
  public static final java.lang.String USER_NAME = "X-User-Name";
  public static final java.lang.String TENANT_ID = "X-Tenant-Id";
  public static final java.lang.String DEPT_ID = "X-Dept-Id";
  public static final java.lang.String ROLES = "X-Roles";
  private cn.ypbin.starter.security.identity.IdentityHeaders();
}
```

源码同版（`v3.5.0` tag 与 starter 工作树 HEAD **已 `diff` 确认逐字一致**）：`IdentityHeaderFilter` 类在 `:53`，
`doFilterInternal` 从 `request.getHeader(IdentityHeaders.USER_ID)`（`:60`）直接构造 `LoginUser` 并
`IdentityContext.setLoginUser(loginUser)`（`:92`）；唯一分支是 `Long` 解析失败的**容错**（`:63-66`、`:112-116`），
**没有任何来源 / 签名判断**。`IdentityHeaders` 也只定义这 5 个身份头常量，**没有**标记头常量。

**环 2｜starter 的 trusted-source 机制只挡"Feign 二次透传"，不挡"本服务的身份建立"**

`FeignProperties`（`ypbin-starter-cloud-core`）：
- `:51-56` `propagateHeaders` 默认含这 5 个身份头**和 `X-Gateway-Signed`**（注释明写"必须透传，否则二次 RPC
  （如 auth→system）会因缺少标记被下游丢弃身份头"）；
- `:63-64` `identityHeaders` = 5 个身份头；`:58-62` 的 javadoc 明确写着**"仅当入站请求携带匹配的
  `X-Gateway-Signed` 才会二次透传，避免直连服务伪造身份后经 Feign 调用放大越权"**——作者已知道直连场景；
- `:67` `trustedSourceHeader = "X-Gateway-Signed"`；`:73` `trustedSourceToken = ""`（**默认空**）；
  `:81` `requireTrustedSource = false`（`:75-80` javadoc：默认只**启动告警**）。

`FeignHeaderInterceptor`：
- `:128-134` `isIdentitySourceTrusted(request)`：**标记为空即恒返回 `true`**（fail-open）；
  否则 `trustedSourceToken.equals(actual.trim())`；
- `:100` + `:110-113`：只有 `identityTrusted` 为 true，才把这些身份头放进**出站** Feign 请求。

⇒ 这套机制回答的是"**要不要把上游进来的身份头继续往下传**"；它**既不拒绝也不校验**本服务自己用这些头
建立的 `IdentityContext`——而 `IdentityHeaderFilter` 在 Filter 链更早的位置就已**无条件**写入。

**环 3｜网关只是"签发侧"，且标记实为静态共享串**

`GatewayAuthGlobalFilter#withTrustedHeaders`（`:126-137`）：签发身份头的同时
`headers.set(trustedSourceHeader, trustedSourceToken)`（`:131-133`，未配置则不签发）；
配置键 `ypbin.gateway.auth.trusted-source-token`（`GatewayProperties$Auth:218-226`）。
网关是 WebFlux（`ypbin-common.yaml:65` 注释："网关为 WebFlux，不装配该过滤器，本键对其无效"），
其制品内**不含任何 Feign 类**（`jar tf ypbin-starter-cloud-gateway-3.5.0.jar | grep -ci feign` = **0**，
对照 `ypbin-starter-cloud-core-3.5.0.jar` = **13**）⇒ **网关不做校验，只签发**。

另需指出：这个"签名标记"实为**静态共享串比对**（`FeignHeaderInterceptor:133` 的 `equals`），
不是对身份头内容的密码学签名——它只证明"请求方知道该串"，**不绑定身份头内容、无时效、不可按服务区分、不可轮换追踪**。

**环 4｜排除性核查：全制品只有"签发"与"Feign 出站"两处引用该标记，身份头消费侧零引用**

```bash
for j in ~/m2repo/cn/ypbin/*/3.5.0/*.jar; do d=$(mktemp -d); (cd "$d" && jar xf "$j" 2>/dev/null && grep -rl "X-Gateway-Signed" . 2>/dev/null | sed "s#^\./#$(basename "$j") #"); rm -rf "$d"; done
```
```
ypbin-starter-cloud-core-3.5.0.jar META-INF/spring-configuration-metadata.json
ypbin-starter-cloud-core-3.5.0.jar cn/ypbin/starter/cloud/autoconfigure/FeignProperties.class
ypbin-starter-cloud-gateway-3.5.0.jar META-INF/spring-configuration-metadata.json
ypbin-starter-cloud-gateway-3.5.0.jar cn/ypbin/starter/gateway/autoconfigure/GatewayProperties$Auth.class
```
⇒ 命中的只有**网关配置类**（签发）与 **Feign 配置类**（出站开关）；`ypbin-starter-security`
（身份头消费侧、`IdentityHeaderFilter` 所在模块）**零命中**。ypbin-iot 仓自建代码同样零命中
（`grep -rn "X-Gateway-Signed" --include=*.java ypbin-*/src` 无输出）。

**环 5｜配置面的对称性缺陷：签发侧有，`ypbin-auth` / `ypbin-common` / `ypbin-access` 三处没有**
（注意：缺键的是"要经 Feign 透传身份头的服务"，与"装配身份头过滤器的服务"**不是同一个集合**，见下方口径说明）

| `deploy/nacos/` 文件 | `ypbin.cloud.feign.require-trusted-source` | `trusted-source-token` | 本机实测计数 |
|---|---|---|---|
| `ypbin-iot.yaml:22-24` | `true` | 有（环境变量占位符） | 1 / 1 |
| `ypbin-system.yaml:42-44` | `true` | 有（环境变量占位符） | 1 / 1 |
| `ypbin-ai.yaml:14-16` | `true` | 有（环境变量占位符） | 1 / 1 |
| `ypbin-auth.yaml` | **无** | **无** | 0 / 0 |
| `ypbin-common.yaml`（**共享**） | **无** | **无** | 0 / 0 |
| `ypbin-access.yaml` | **无** | **无** | 0 / 0 |

复核命令与输出：

```bash
$ for f in deploy/nacos/ypbin-iot.yaml deploy/nacos/ypbin-system.yaml deploy/nacos/ypbin-ai.yaml deploy/nacos/ypbin-auth.yaml deploy/nacos/ypbin-common.yaml deploy/nacos/ypbin-access.yaml; do
    printf "%-32s require=%s token=%s\n" "$f" "$(grep -c require-trusted-source "$f")" "$(grep -c trusted-source-token "$f")"; done
deploy/nacos/ypbin-iot.yaml      require=1 token=1
deploy/nacos/ypbin-system.yaml   require=1 token=1
deploy/nacos/ypbin-ai.yaml       require=1 token=1
deploy/nacos/ypbin-auth.yaml     require=0 token=0
deploy/nacos/ypbin-common.yaml   require=0 token=0
deploy/nacos/ypbin-access.yaml   require=0 token=0
```

签发侧只有一处：`ypbin-gateway.yaml:58-64` 的 `ypbin.gateway.auth.trusted-source-token`
（**键路径不同**于下游的 `ypbin.cloud.feign.*`）。而装配该过滤器的前置开关在**共享**配置 `ypbin-common.yaml`：
`ypbin.security.identity.enabled: true`（`:66-69`，注释写"保持 auth/system/ai 各 Servlet 服务既有行为不变"）。
该文件由 **auth / gateway / system / iot / ai** 五者导入（逐个核对各自 `src/main/resources/application.yml` 的
`optional:nacos:ypbin-common.yaml`），其中 gateway 是 WebFlux、不装配该 Servlet 过滤器。

> **口径说明（避免误计"消费侧"）**：`ypbin-access` **不导入** `ypbin-common.yaml`（其 `application.yml`
> 只导入 `ypbin-access.yaml`，该文件头部也写明理由），它的 Data ID 里同样没有 `ypbin.security.identity.enabled`；
> 而 starter 的 `IdentityAutoConfiguration` 是 `matchIfMissing = false`（`v3.5.0` 源码 `:42-45`）
> ⇒ **access 不装配 `IdentityHeaderFilter`，不是身份头消费侧**。它缺的是 `ypbin.cloud.feign.*`（配置面缺口），
> 与本条不是同一个问题，已在本仓单独登记（PR #38）。

**生产环境一手证据（2026-09-25 部署实例只读复核）**

① 收窄**前**的暴露面 = 仓内默认值即"绑所有网卡"（可复核）：`deploy/docker-compose.yml` 里**下游与多数中间件
端口**写成 `${INTERNAL_BIND_ADDR:-0.0.0.0}`（`:215` auth 18081、`:235` system 18082、`:256` ai 18083、
`:281` access 18086、`:301` iot 18084、`:71` mysql 3306、`:28`/`:29` nacos 8848/9848 …），而
`deploy/.env.example:75` 把 `INTERNAL_BIND_ADDR` 设为 `0.0.0.0` ⇒ **按仓内默认部署即把这些端口发布到所有网卡**。
**例外（本仓自己就写了反例，因此不能写成"一律"）**：`:50` redis 6379 默认 `127.0.0.1`；
`:118` iotdb 6667 用**独立**变量 `${IOTDB_BIND_ADDR:-127.0.0.1}`（`:115-117` 注释写明"共用会把 6667
连同默认口令一起暴露到全网"）；`:197` gateway 18080 是**硬编码** `"18080:18080"`、不参与该变量的收窄。

② 收窄**后**（本次实测）：

```bash
$ ssh -i ~/.ssh/id_ed25519_iot_test -p 47048 root@113.142.217.42 \
    'ss -lnt | awk "NR==1 || \$4 ~ /:(1808[0-9]|3306|8848|9848|6379|6667)\$/"'
State  Recv-Q Send-Q Local Address:Port  Peer Address:PortProcess
LISTEN 0      4096       127.0.0.1:8848       0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:9848       0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:18082      0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:18081      0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:18084      0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:6379       0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:6667       0.0.0.0:*          
LISTEN 0      4096         0.0.0.0:18080      0.0.0.0:*          
LISTEN 0      4096       127.0.0.1:3306       0.0.0.0:*          
LISTEN 0      4096            [::]:18080         [::]:*          
```
（`18080` = **网关 `ypbin-gateway`**（`docker-compose.yml:197` 硬编码；`.env.example:73` 亦写"只对外暴露前端（19000）
与网关（18080）"）；`19000` = IoT 前端 `ypbin-iot-ui`。**口径限定**：在本次端口白名单
（`1808x`/`3306`/`8848`/`9848`/`6379`/`6667`）**范围内**，除 `18080` 外均为回环——**不是**"全机只有这两个端口对外"。
配置面：生产 `/opt/ypbin/ypbin-iot/deploy/.env:18` 为 `INTERNAL_BIND_ADDR=127.0.0.1`。）

> **全量 `ss -lnt` 的非回环行另有**（补测，避免"滤过视图当全量"）：`0.0.0.0:80` / `0.0.0.0:443` / `[::]:80` /
> `[::]:443`（1Panel openresty）、`0.0.0.0:20232`（1panel-core）、`0.0.0.0:22` / `[::]:22`（sshd）。
> 这些属 1Panel 面板 / 主机系统，不在本条缺陷的处置范围内，但**不能**用"只暴露两个端口"概括。

③ **框架自己知道这个缺口（源码级依据，非生产观测）**：3.5.0 制品里 `FeignHeaderInterceptor` 构造函数（`:82-90`）
的告警原文是——
「未配置 ypbin.cloud.feign.trusted-source-token，身份头将不做来源校验直接透传；若服务可被外部直连，
请配置该值并在网关签发 X-Gateway-Signed 标记，防止伪造身份经 Feign 放大越权」。
`IdentityHeaderFilter` 的类级 Javadoc（`:41-44`）同样写着"若服务可被外部直接访问，
**严禁开启（否则外部请求可伪造身份头冒充已认证用户）**" ⇒ **"不校验"是已知语义，只是被降级成了文档约定。**

> **口径校正**：上述告警**本次未在生产日志中观测到**（对生产全部容器 grep 该文案，提及数 = 0）——因为加固后
> `ypbin-auth` 容器已于 `01:51` 重建，**没有 pre-fix 日志基线**。该引文来自**制品字节码**，不是生产实测，
> 请勿据此断言"生产启动时打出过"。

④ 本轮加固后的 auth 侧实测：容器 `Up`，其当前日志中该告警计数 = **0**
（由构造函数语义 ⇒ auth 进程的 `trusted-source-token` 已非空）。**口径**：该容器于 `01:51` 重建、pre-fix 日志已
不存在，所以这是"**当前计数为 0**"，**不是**"观测到从非零降到零"的转变；
改动前的 Nacos 配置备份证实"原来没有"：
`/opt/ypbin/nacos-ypbin-auth.yaml.bak-20260925-013857`（2305 B）与
`/opt/ypbin/nacos-ypbin-common.yaml.bak-20260925-013857`（4271 B）两键计数均为 **0**，
同批备份中 ai / iot / system 均为 1 / 1。

**影响（认证与租户隔离，不是"少个校验"）**

1. **任意身份冒充（认证绕过）**：`IdentityContext` 被业务与框架当作"当前登录用户"。伪造 `X-User-Id`
   即可以该用户（含平台超管）身份执行；网关的 token 校验、SF-1 修好的注解鉴权（按身份解析出的用户查权限）
   都会被"以正确用户的身份"合法通过。
   （**口径**：本条是**机制层已证**——过滤器确实不校验、身份确实由该头无条件建立、租户值确实直接进 SQL
   过滤条件；但**端到端的"伪造请求被成功执行"本轮未做攻击性实测**，见"未核实 / 边界"。）
2. **跨租户数据读写（多租户隔离失效）**：`MicroserviceTenantProvider#getCurrentTenantId`
   （`ypbin-iot/ypbin-common/.../MicroserviceTenantProvider.java:29-30`）`return IdentityContext.getTenantId()`；
   该值经 `DefaultTenantLineHandler#getTenantId`（`ypbin-starter-extension-tenant`，`:57-62`）**直接作为
   MyBatis-Plus 的租户过滤条件值** ⇒ 伪造 `X-Tenant-Id` 即改写**每一条**租户表 SQL 的过滤条件，
   实现跨租户读写（本仓 `iot_*` 业务表全在租户插件管辖内）。
3. **爆炸半径 = 网络可达性**：收窄前，同内网 / 同宿主机可达者直连 `18081`/`18082`/`18084`（及 3306/8848 等）即可利用；
   收窄后降到"宿主机内进程 / 同宿主机容器 / 能把请求打到 `127.0.0.1` 的 SSRF"。**这只是缓解，不是修复**——
   端口收窄是可被一次部署回退的运维动作，而缺陷在代码里。**具体到本机**：宿主机上另有 1Panel / openresty
   监听 `80`/`443`/`20232`（见上）⇒ 面板或反代一旦被拿下（或任何宿主内进程被控制），
   **回环收窄对这条路径不提供任何保护**。
4. **放大链 + 横向扩散**：若被直连的**服务**自身 `trusted-source-token` 为空（`FeignProperties:73` 默认即空；
   本仓 `ypbin-auth` / `ypbin-access` 的 Data ID 原状如此——注意 `ypbin-common.yaml` 是**共享配置文件**、不是服务），
   其 Feign 出站会把伪造身份头**继续传给再下游**（`FeignHeaderInterceptor:129-131` fail-open）
   ⇒ 一次伪造可扩散整条调用链；配了 token 的 `iot` / `system` / `ai` 只是"局部攻陷"。
   另一层横向风险：该标记是**静态共享串**（各下游配同一个值）⇒ **任何一处下游被拿下，就等价于拿到全链的身份头
   伪造能力**，"被攻陷的服务数量"不构成纵深。
5. **对照：本仓已有正确范式**：`/internal/**` 的内部调用守卫 `InternalTokenGuardInterceptor`
   （`ypbin-iot/ypbin-service/ypbin-system/.../InternalTokenGuardInterceptor.java:51-64`）做的是 **fail-closed**
   （凭证未配置即整体拒绝，`:51-55`）+ **常量时间比较**（`MessageDigest.isEqual`，`:59`）。
   身份头这一路缺的正是同一套机制。

**期望能力（候选，推荐 A；请 starter 侧择一或给更优解）**

- **A（推荐）让 `IdentityHeaderFilter` 校验 trusted-source 标记，缺失 / 不匹配即拒绝**：
  复用既有 `ypbin.cloud.feign.trusted-source-header/token`（默认 `X-Gateway-Signed`），把网关的签发标记
  **真正接进"身份建立"这一环**——缺失或不匹配时返回 **401/403 并告警**（带来源 IP + 路径），
  **不得**静默按"无身份"放行。语义要点：**与 `identity.enabled=true` 同生共死**，
  不允许"配了 token 才校验"的 fail-open。
- **B 统一的下游身份头校验自动配置 + 装配层强制 fail-fast**：`identity.enabled=true` 且未配置
  `trusted-source-token` 时**启动失败**（即把 `require-trusted-source` 在 identity 模式下的默认值从
  `false`（仅告警）改为 **true**），消除"忘记配置 = 长期静默不校验"。
- **C 其它更优方案（供参考，不预设结论）**
  1. 把**静态共享串**升级为**与内容绑定的签名**（如 HMAC(secret, 身份头内容 + 时间戳 + nonce)）：
     可防重放、可按服务区分密钥、可轮换；现实现 `equals(...)` 只证明"知道串"。
  2. **签发 / 校验 / 透传三处收口到同一个 trusted-source 组件**，避免语义漂移
     （当前三处各自实现，已出现"签发在网关、校验只在 Feign 出站"的错位）。
  3. 下游服务注册到**仅网关可达**的网络（服务网格 / mTLS），把"网络可达性"从约定变成机制。

**验收标准**

1. **负向用例（必须有，且必须真能拒）**：向 `identity.enabled=true` 的下游端点发两个请求（均带伪造的
   `X-User-Id` / `X-Tenant-Id`）——① 不带 `X-Gateway-Signed`；② 带**错误值**的 `X-Gateway-Signed`。
   两者都必须被拒（401/403）、**不得**写入 `IdentityContext`、且产生告警；
2. **正向用例**：`X-Gateway-Signed` 正确时请求正常通过，且 `IdentityContext` 中的用户 / 租户与头一致；
3. **缺 token 时的装配语义**：`identity.enabled=true` 且未配置 `trusted-source-token` ⇒ **启动期 fail-fast**
   （或按 A 的语义直接拒绝所有携带身份头的请求）；二选一，但必须**显式且被测试覆盖**，
   **不得**是"运行期静默透传"；
4. **starter 级测试**：至少一条 `IdentityHeaderFilter` 层的正 / 负用例（`MockHttpServletRequest` 即可，
   不需要容器），并**经变异验证**——把校验那一行删掉 ⇒ 负向用例必须**转红**（否则是恒真的装饰性用例）；
5. **不回归**：`FeignHeaderInterceptorTest` 既有行为不变（不可信来源仍不透传身份头）；
   `require-trusted-source` 默认值若变更，须同步 `spring-configuration-metadata` 与文档；
6. `ypbin-iot` 侧撤掉部署实例的配置侧缓解后，**直连下游端口**伪造身份实测**被拒**
   （本仓负责复验并在 ROADMAP 四点十八 标注关闭）。

**会被替换掉的临时实现**：**部署实例的配置侧缓解（不是代码修复）**，两项，都只降低暴露面 / 补齐对称性，
**不改变"身份头被无条件信任"这一事实**：

1. **收窄绑定地址**：生产 `/opt/ypbin/ypbin-iot/deploy/.env:18` 置 `INTERNAL_BIND_ADDR=127.0.0.1`，
   收窄后在**端口白名单内**仅 `18080`（**网关 `ypbin-gateway`**；`docker-compose.yml:197` 硬编码）与
   `19000`（IoT 前端）对外——**全量** `ss -lnt` 另有 `80`/`443`（1Panel openresty）、`20232`（1panel-core）、
   `22`（sshd）绑 `0.0.0.0`，不在本条处置范围；本次只读复核 `ss -lnt` 已确认
   `18081`/`18082`/`18084`/`3306`/`8848`/`9848`/`6379`/`6667` 全部为回环。
2. **补齐配置对称性**：在生产 **live Nacos** 的 `ypbin-auth.yaml` 与 `ypbin-common.yaml` 补上
   `ypbin.cloud.feign.trusted-source-token` 与 `ypbin.cloud.feign.require-trusted-source: true`
   （值取自 `.env` 的 `GATEWAY_SIGN_TOKEN` = 64 个十六进制字符，**本文档不记录该值**）；复核后 `ypbin-auth` 的
   "身份头将不做来源校验直接透传"告警**当前计数为 0**（容器 `01:51` 重建，无 pre-fix 基线）。

> **本仓约束（重要，与 SF-4 同因）**：`deploy/nacos/ypbin-auth.yaml`、`deploy/nacos/ypbin-common.yaml`
> 都是 **admin 所有的既有文件**，**不在** ypbin-iot 的 SYNC 白名单（当前 8 个文件，见 `SYNC.md` 第二节与
> `.github/workflows/sync-whitelist.yml`）内 ⇒ **在仓里改它们会让 `Sync Whitelist` 门禁转红**。
> 所以上述两项缓解**只存在于部署实例**（`.env` 与 live Nacos），仓内文件**未同步**
> （可复现校验：`grep -c trusted-source-token deploy/nacos/ypbin-auth.yaml deploy/nacos/ypbin-common.yaml`
> ⇒ 均为 `0`）。这也正是本条**必须由 starter 在代码里修**、而不能由下游自行绕过的直接原因。
> （附带发现：`deploy/nacos/ypbin-access.yaml` 是本仓**新增**文件、不受白名单限制，同样缺这两个键；
> 本条**不做改动**，仅登记，留待与本条一起收口。）

**未核实 / 边界（如实标注；本清单经独立子代理复核后补全，见下"独立复核整改"）**

**已核实、原先误标为"未核实"的项（口径升级）**

- **部署实例 token 的长度：已核实为 64 个十六进制字符**：`deploy/install.sh:1094` 用
  `GATEWAY_SIGN_TOKEN="${GATEWAY_SIGN_TOKEN:-$(rand_hex 32)}"` 生成，而 `rand_hex`（`:1067-1073`）是
  `openssl rand -hex "$1"` ⇒ 32 字节 = **64 个十六进制字符**；对 live `/opt/ypbin/ypbin-iot/deploy/.env` 的同名键
  **只读测量长度 = 64**（只取长度，不读取、不回显值）。仓内该键一律写成占位符 `${GATEWAY_SIGN_TOKEN}`
  （21 字符，变量名 18 字符 = 16 个大写字母 + 2 个下划线），由 `install.sh:1290-1298` 在导入 Nacos 前用 `.env`
  实测值 `sed` 替换 ⇒ **真实 token 的「值」不入库**（长度由生成器固定为 64，不是实例机密；
  本文档记录该长度是刻意的口径说明）。
- **全量端口暴露面已补测**（原先只跑了带白名单过滤的 `ss`，属"滤过视图当全量"）：见上文生产证据 ② 的
  "全量 `ss -lnt` 的非回环行另有"；`18080` 的归属也已核实为**网关**（原先误记为 admin-ui）。

**仍然未核实**

- **"收窄前绑所有网卡"无法回看现场**：缓解已生效，历史 `ss` 输出不可复现。上文该结论的依据是**仓内默认值**
  （`docker-compose.yml` 的 `${INTERNAL_BIND_ADDR:-0.0.0.0}` 与 `.env.example:75`），**不是**当时的 `ss` 抓取。
- **live Nacos 的配置内容未读取**（Nacos 3 内嵌存储——该 MySQL 实例的库里没有 Nacos 配置表；管理 API 实测 **403**）：
  ① "两个键已补到 `ypbin-auth.yaml` / `ypbin-common.yaml`"是**由效果演绎**（auth 进程 `Up` 且该告警当前计数为 0），
  未逐 dataId 读到内容；② **live `ypbin-common.yaml` 里 `ypbin.security.identity.enabled` 的实际值也未读取**——
  文中"这个入口在生产在线"的依据是**仓内** `common.yaml` + `ypbin-system` / `ypbin-iot` 容器 `Up`，
  而 SF-4 已证明 **live 与仓内可以不一致**（auth 就是被服务级覆盖的例子）。就当前状态，**按仓内配置推定**
  在线的消费侧是 **`ypbin-system` / `ypbin-iot`**；`ypbin-auth` 已被 SF-4 的临时处置覆盖为 `identity.enabled=false`。
- **端到端的"伪造头即可越权"未实证**：本条为**机制层已证**（过滤器不校验 + 身份由该头无条件建立 + 租户值直接
  进 SQL 过滤条件 + 从无签名直连路径上没有任何拒绝点），但**利用链未做攻击性实测**（本轮只做只读复核与
  源码/制品核查）。
- **生产上 `ypbin-ai` / `ypbin-access` / `xxl-job-admin` 容器未运行**（`docker ps` 无这三个容器，`ss` 也无
  `18083`/`18085`/`18086` 监听）⇒ 配置对称性缺陷对这二者当前**不构成在线暴露**；对 `ypbin-system` / `ypbin-iot` 构成。
- **网关未把 `X-Gateway-Signed` 纳入外部头清洗名单**（`GatewayProperties$HeaderSanitize:181` 只清洗 5 个身份头）。
  经推理**不可经网关利用**（白名单路径无 token 时网关虽不签发标记，但同链的 `HeaderSanitizeGlobalFilter`
  已剥掉外部身份头，下游拿不到可伪的身份）——**但本次未做实测复现**，仅作为防御纵深建议记录。
- 本次**未**对生产实例做任何写操作与攻击性验证（全部只读：`ss` / 读 `.env` 单行 / 读容器日志计数 /
  读配置备份文件 / 只读查询库表结构）。

**顺带登记（与本条同源，建议一并更正，本轮未改）**

- `deploy/.env.example:43-45` 的注释写着"网关签发 `X-Gateway-Signed`，**下游校验后才信任** `X-User-Id` 等
  身份头"、"留空则下游不校验来源"——这与本条结论**直接矛盾**：下游（`IdentityHeaderFilter`）**从来不校验**
  该标记，`GATEWAY_SIGN_TOKEN` 非空也**不改变**"身份头被无条件信任"这一事实。该误述会让人按"配了 token
  就安全"行事。该文件在 SYNC 白名单内（可改），但本条是纯文档，**未改**，仅登记。

**关联文档**：ypbin-iot `docs/IOT-ROADMAP.md` 四点十八（索引表 SF-5 行）；starter 侧相关：SF-1
（身份头 → Sa-Token 桥，3.5.0 已支持）、`FeignHeaderInterceptor`（既有 trusted-source 机制）、
`IdentityHeaderFilter`。

---

## 附：跨仓（非 starter）但同样需要上游配合的一条

**UP-1（中）admin 仓的 Sync Whitelist 不覆盖 `.github/**`**：`ypbin-iot`（admin 的 fork）想加一条 CI 门禁时，
**不能**改继承来的 `.github/workflows/ci.yml`（白名单只有 7 个文件），只能**新增**一个 workflow 文件。
若上游希望下游能统一加固 CI，建议把 `.github/workflows/**`（或至少「新增文件」）纳入白名单语义并写进 `SYNC.md`。
