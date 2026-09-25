# ypbin 平台级信息架构方案：顶部大模块 + 模块内二级导航

> **与 [`IOT-UX-PROPOSAL.md`](IOT-UX-PROPOSAL.md) 的分工（两文互链，不重复劳动）**
>
> | 文档 | 回答的问题 | 范围 |
> |---|---|---|
> | **本文 `PLATFORM-IA-PROPOSAL.md`** | 整个平台该拆成几个大模块？用什么表现形式？顶级菜单怎么迁？ | **模块之间**（平台级） |
> | [`IOT-UX-PROPOSAL.md`](IOT-UX-PROPOSAL.md) | IoT 模块内部每个页面长什么样、缺哪些数据模型、F1–F6 只改前端清单 | **IoT 模块之内** |
>
> 本文涉及 IoT 模块内部时一律**引用旧文**（§4.3、§6、§9），不复述。旧文的 7 平台一手研究（§2）与 12 条数据模型缺口（§0.2）仍然有效，本文不推翻任何一条。
>
> 可点原型：本文对应 `ypbin-iot-ui/docs/ux-mock/platform-nav.html`（**平台级**导航）；旧文对应 `ypbin-iot/docs/ux-mock/index.html`（**IoT 模块内页**）。

---

## 0. 结论先行

### 0.1 五条核心结论

1. **表现形式：用 vben **原生** `mixed-nav` 布局**（顶部一级大模块 + 左侧二级菜单）。不自研顶栏、不做纯顶部导航、不做多域名/微前端。理由：`mixed-nav` 是仓内**已有并在类型系统里声明**的 7 种布局之一（`packages/@core/base/typings/src/app.d.ts:1-8`），改装成本 ≈ 改一行偏好 + 重建产物；纯顶部导航在 13 个顶级菜单下会立刻溢出，深度菜单撑不住；多域名要动部署与登录态，收益为负。
   > **注（R4）：** "顶部大模块是主流通式"这个前提**不成立**——本次一手核实发现腾讯云官方明文写"顶部导航 + 左侧菜单"，但阿里云把产品清单放在**左侧**导航栏、企业微信官方记载把导航**从顶部改到了左侧**、Azure 的 portal menu 官方支持 flyout/docked 两种模式。选 `mixed-nav` 的理由是**与 vben 原生能力匹配度最高 + 我方只有 4-5 个模块不属"超长清单"场景**，不是"随主流"。详见 §3.4。
2. **大模块划分（主推）：4 个模块 + 1 个工作台首页**
   `工作台`（首页，保留 id=1）│ `基础管理` │ `知识与 AI` │ `物联网` │ `运维与监控`。
3. **迁移只新增 2 个顶级 `catalog`**：`3310 基础管理`、`3320 运维与监控`；其余靠 **reparent**。`知识与 AI`(5000) 与 `物联网`(3204) **本来就是顶级 catalog，不需要新增任何 id**。
4. **明确不设的模块（R4/R8）**：**不设「开放平台/开发者」模块**——全平台只有 1 个既有页面（`/system/app` 开放应用）与 1 个内嵌接口文档页，拆成模块会得到"点开只有 2 项"的空壳模块；Webhook **当前无此功能**。**不设「个人」模块**——个人中心与消息放右上角（vben 原生已有用户下拉与 `#notification` 插槽）。
5. **最小落地一步（只改前端/配置，零菜单变更）**：在 `apps/web-antd/src/preferences.ts` 的 `defineOverridesPreferences({ app: { layout: 'mixed-nav' } })` 加一行，重建产物 → 立即看到"顶部大模块条 + 左侧二级菜单"的形态。此时顶栏是**现有 13 个顶级菜单**，会溢出折叠成「更多」，所以**这一步只用来看效果，不是终态**；也可以先零构建验证：右上角「偏好设置 → 布局 → 混合垂直」当场可切。

### 0.2 三个必须先知道的坑（本文档的主要价值之一）

| # | 坑 | 后果 | 见 |
|---|---|---|---|
| **K1** | **缓存偏好会覆盖代码默认值** | 改 `preferences.ts` 里的 `layout` 对**已经访问过**的用户**不生效**（localStorage 里的旧值优先） | §2.4、§7.4 |
| **K2** | **reparent 会产生"孤儿菜单"，整棵被后端丢弃** | 把子菜单挂到新模块目录下、却没把新目录授给"已拥有该子菜单的角色/模板" ⇒ 这些用户**看不到该子菜单**（不是灰掉，是消失）。**边界：只对「非超管」用户成立**（无论其是否平台用户）—— 超管走 `list(enabledMenusOrdered())` 全量菜单路径、父一定在集合里、不受影响；非超管一律走 `selectByUserId`（**父不在角色授权里就整棵丢**），其中**非平台**用户还要多过一道**租户模板**过滤（`applyTenantMenuFilter`），而 `platform_only=1` 的过滤对**平台**用户不生效 ⇒ 二者都不能免于 K2 | §2.5、§7.1 |
| **K3** | **授权门禁有两重缺陷**：① 只覆盖 `32xx` 开头的 id；② 更要命的是它对 `sys_template_menu` 的检查是**假绿**（`sql.split("INSERT INTO "+table)` 的 block 跨语句串味，两表 `granted` 实测完全相同）| 新模块目录用 `33xx` 时不会被检查；**即使扩到 `33xx` 也不咬人**（实测删掉模板授权不转红）⇒ K2 的盲区重新打开。修正版门禁见 §7.1.4（已实测基线通过 + 三个变异转红）| §7.1.4、附录 C |

---

## 1. 研究方法与信源声明（R1 / R2 / R3）

### 1.1 本次实际做过的一手核实

| 类别 | 方法 | 产物位置 |
|---|---|---|
| **vben 布局能力** | 直接读 `ypbin-iot-ui` 仓内类型定义与实现（`LayoutType`、`usePreferences`、`useMixedMenu`、`BasicLayout`、`VbenAdminLayout`、`menu-ui`），全部给**文件:行号** | §2、附录 A.1 |
| **当前生效布局** | 读 `packages/@core/preferences/src/config.ts` 默认值 + `apps/web-antd/src/preferences.ts` 覆盖值 + 初始化/缓存合并逻辑 | §2.3、附录 A.2 |
| **顶级菜单来源** | 读后端 `SysMenuServiceImpl#buildRoutes`（根 = `pid=0`）+ 前端 `getAllMenusApi`/`generateAccessible`/`generateMenus` | §2.5、附录 A.3 |
| **现有顶级菜单（原料）** | **只读查询生产活库** `ypbin_admin.sys_menu`（`ssh ypbin-prod` + `docker exec ypbin-mysql`，只跑 `SELECT`） | §4.2、附录 A.4 |
| **id 占用情况** | 同上，只读 `SELECT id FROM sys_menu WHERE id BETWEEN 3300 AND 3399`（结果为空 = 全段空闲） | §7.2、附录 A.5 |
| **门禁现状** | 读 `IotMaintenanceAdminGateTest`、`IotPermissionCodeGateTest`、`tools/check-iot-sql-equivalence.sh` | §7.1.4、附录 A.6 |
| **原型离线自证** | `grep -c 'http'` / `grep -nE '<(script\|link\|img\|iframe)'` | §8 复核、原型仓 README |

**未改动生产库、未改动业务代码、未合并任何 PR**；所有数据库访问均为 `SELECT`；本文件不含任何口令/token。

### 1.2 信源性质说明（影响结论强度）

- **§2、§4、§7 的断言全部是仓内一手证据或活库实测**，强度最高。
- **§3 的外部对照**分两种：① 引用旧文 `IOT-UX-PROPOSAL.md` §2 已核实并给出访问日期的**一手官方链接**（不重复抓取）；② 本次为"平台级导航表现形式"新做的核实，见 §3.4。**未能从官方文档核实的部分逐条标注为"未核实"**，不冒充。
- 旧文 §1.3 / §10.1 已登记的未核实项（U11/U12 等）本文不重复登记。

---

## 2. vben 布局能力核实（仓内一手，给文件:行号）

### 2.1 原生支持的布局：**7 种**，不是 5 种也不是 8 种

**唯一权威来源**是类型定义 `packages/@core/base/typings/src/app.d.ts:1-8`：

```ts
type LayoutType =
  | 'full-content'        // 内容全屏，不显示任何菜单
  | 'header-mixed-nav'    // 混合双列：顶部一级 + 侧边双列
  | 'header-nav'          // 纯水平：菜单全部在顶部
  | 'header-sidebar-nav'  // 顶部通栏 + 侧边导航（侧边仍是完整菜单，顶部不放一级导航）
  | 'mixed-nav'           // 混合垂直：顶部一级 + 左侧二级 ← 本方案要的
  | 'sidebar-mixed-nav'   // 垂直双列
  | 'sidebar-nav';        // 垂直单列（当前生效）
```

对照证据：

| 证据 | 文件:行号 | 说明 |
|---|---|---|
| 类型联合声明 | `packages/@core/base/typings/src/app.d.ts:1-8` | 7 个字面量，就是这 7 种 |
| 偏好项渲染成 7 个可点图标 | `packages/effects/layouts/src/widgets/preferences/blocks/layout/layout.vue:35-43`、`:45-81` | `Record<LayoutType, Component>` 7 项一一对应，`PRESET` 也正好 7 项 |
| 中文/英文名 | `packages/locales/src/langs/zh-CN/preferences.json:16-29`、`en-US/preferences.json:16-29` | 垂直 / 双列菜单 / 水平 / 侧边导航 / 混合双列 / **混合垂直** / 内容全屏 |
| 布局谓词 | `packages/@core/preferences/src/use-preferences.ts:71-128` | `isFullContent`/`isSideNav`/`isSideMixedNav`/`isHeaderNav`/`isHeaderMixedNav`/`isHeaderSidebarNav`/`isMixedNav` 各自只比较一个等值 |

> ⚠️ **不要臆造第 8 种布局**。仓内没有 `top-nav`、`mega-menu`、`header-top-nav` 之类的类型；任何方案都必须落在这 7 种之内，或用 `full-content` + 自研页面（本方案不推荐，见 §3）。

### 2.2 哪一种 = 「顶部一级大模块 + 左侧二级菜单」？→ **`mixed-nav`**

**调用链证据（完整）：**

1. `mixed-nav` 被识别为 `isMixedNav`：`packages/@core/preferences/src/use-preferences.ts:113-115`
2. 顶部与左侧**分工**只在 `needSplit` 为真时发生：`packages/effects/layouts/src/basic/menu/use-mixed-menu.ts:24-29`
   ```ts
   const needSplit = computed(() =>
     !isMobile.value &&
     ((preferences.navigation.split && isMixedNav.value) || isHeaderMixedNav.value));
   ```
   ⇒ `mixed-nav` 需要 `navigation.split === true`，而该默认值就是 `true`（`packages/@core/preferences/src/config.ts:76`），本应用**没有覆盖它**（`apps/web-antd/src/preferences.ts` 只覆盖了 `app/copyright/logo`）。
3. **顶栏只拿一级**（子菜单被清空）：`use-mixed-menu.ts:43-53`
   ```ts
   const headerMenus = computed(() => {
     if (!needSplit.value) return menus.value;
     return menus.value.map((item) => ({ ...item, children: [] }));  // ← 一级模块
   });
   ```
4. **左侧拿当前一级的子菜单**：`use-mixed-menu.ts:58-60`（`sidebarMenus = needSplit ? splitSideMenus : menus`），`splitSideMenus` 在 `use-mixed-menu.ts:129-139` 由 `findRootMenuByPath(menus, path)` 算出。
5. **顶栏以水平模式渲染 `headerMenus`**：`packages/effects/layouts/src/basic/layout.vue:131-136`（`showHeaderNav` 包含 `isMixedNav`）+ `:367-377`（`<LayoutMenu mode="horizontal" :menus="wrapperMenus(headerMenus)">`）
6. **左侧以垂直模式渲染 `sidebarMenus`**：`layout.vue:390-403`（`<LayoutMenu mode="vertical" :menus="wrapperMenus(sidebarMenus)">`）
7. **点一级模块的行为**：`use-mixed-menu.ts:88-110`——把该模块的 `children` 灌进左侧；模块自身有子菜单时**不跳转**（除非开了 `sidebar.autoActivateChild`，其默认值是 `false`，见 `packages/@core/preferences/src/config.ts:88`）。

**同时确认 `mixed-nav` 不会多出第三列**：双列专属插槽有硬条件 `packages/@core/ui-kit/layout-ui/src/vben-layout.vue:597`（`v-if="isSidebarMixedNav || isHeaderMixedNav"`）⇒ `mixed-nav` 只渲染单列侧边栏 + `#side-extra` 不出现。

**顺带排除两个容易误认的候选：**

- `header-nav`（纯顶部）：`showHeaderNav` 为真，但此时 `needSplit` 为假，**而且侧边栏整体不渲染** —— `vben-layout.vue:165-168` 的 `sidebarEnableState = !isHeaderNav && sidebarEnable`，`:565` 的 `<LayoutSidebar v-if="sidebarEnableState">` ⇒ `header-nav` 下**没有左侧栏**，顶栏里放的是**完整菜单树**。它既不是"顶部一级 / 左侧二级"，也不是"顶部一份 + 左侧一份"。
  > 另：`isSideMode`（`:220-227`）也不含 `header-nav`/`full-content`。
- `header-sidebar-nav`：**不在** `showHeaderNav` 里（`layout.vue:131-136` 只含 `isHeaderNav || isMixedNav || isHeaderMixedNav`）⇒ 顶部**不放**一级导航，是一个通栏顶栏 + 完整侧边菜单，与需求无关。

### 2.3 当前生效的是哪种布局？→ **`sidebar-nav`（垂直单列）**

| 环节 | 文件:行号 | 值 |
|---|---|---|
| 核心默认 | `packages/@core/preferences/src/config.ts:28` | `layout: 'sidebar-nav'` |
| 应用覆盖 | `apps/web-antd/src/preferences.ts:18-41` | **没有** `app.layout`，**没有** `navigation.*` ⇒ 默认值生效 |
| 构建期变量 | `apps/web-antd/.env*`（`.env` / `.env.development` / `.env.production`） | **没有任何布局相关 `VITE_*`**（只有 `VITE_BASE`/`VITE_GLOB_API_URL`/`VITE_ROUTER_HISTORY` 等） |
| 初始化合并 | `packages/@core/preferences/src/preferences.ts:132`、`:139-150` | `initialPreferences = merge({}, overrides, defaultPreferences)`；再 `mergeWithArrayOverride({}, cachedPreferences, initialPreferences)` —— **缓存优先、初始值只补缺** |
| 持久化 | 同上 `preferences.ts:356`（`loadFromCache`）+ `:185`（`resetPreferences`） | 偏好存 localStorage（命名空间 `VITE_APP_NAMESPACE=ypbin-web-antd`，见 `apps/web-antd/.env:5`） |

**结论：本部署当前 = `sidebar-nav`（左侧一条长菜单），一级导航不存在。**

### 2.4 改成"顶部大模块"要改什么？要不要重建？

**要改的地方（最小）：**

```ts
// apps/web-antd/src/preferences.ts  —— 在 defineOverridesPreferences 的 app 段加一行
app: {
  name: import.meta.env.VITE_APP_TITLE,
  accessMode: 'backend',
  locale: 'zh-CN',
  enableRefreshToken: false,
  layout: 'mixed-nav',        // ← 唯一必须加的一行（AppPreferences.layout，见 types.ts:149）
},
```

- **是否需要重建前端产物：需要。** 偏好默认值编译进 bundle；本仓构建链路是现成的（上一轮已跑通）：
  `pnpm -F @vben/web-antd build` → 产物拷到 `../iot-ui-dist` → `docker compose up -d --no-deps ypbin-iot-ui`（一条命令 `deploy/ui-up.sh`，见 `docs/DEPLOY-UI.md` §1）；CI 侧 `.github/workflows/ci.yml` 的"构建（web-antd）"步骤也会跑同一构建 ⇒ **改错了 CI 会红**。
  > 生产实测端口（本次 SSH 实测，**不含任何凭据**）：`docker ps` 显示 `ypbin-iot-ui` 映射 `0.0.0.0:19000->80/tcp`；服务器 `deploy/.env` 里是 `IOT_UI_PORT=19000`。而 `docs/DEPLOY-UI.md` 的示例写的是 `IOT_UI_PORT=19001`（还写着"与 `ypbin-admin-ui` 的 19000 并存"）⇒ 这是**文档漂移**，与本次改动无关，但建议顺手校正（否则照文档部署会得到第二个 UI 端口）。
  > 另：原型 `index.html` 目前被放在 `ypbin-iot-ui` 容器的 `/usr/share/nginx/html/ux-mock/` 下，因此可经 `:19000/ux-mock/` 访问（本次实测 200）。`platform-nav.html` 上线到同一位置即可（`docker cp` 或 `deploy/ui-up.sh` 的产物目录，见该原型 README）。
- **零构建的验证路径（推荐先做）：** 右上角「偏好设置 → 布局 → 混合垂直」当场可切（渲染入口 `.../preferences-drawer.vue:429-431`，`Layout v-model="appLayout"`），值写进 localStorage。

**⚠️ K1（必须记住）：** 因为缓存优先（`preferences.ts:139-150`），把默认值改成 `mixed-nav` 后，**已访问过的用户仍是旧布局**。三个可选处置：

| 方案 | 做法 | 代价 |
|---|---|---|
| a. 接受渐进 | 新用户/清缓存用户看到新布局 | 老用户长期两套形态并存（**不推荐**，支持成本高） |
| b. **一次性强制归一（推荐）** | 在偏好初始化后加一段"布局版本号"逻辑：`overrides.app.layoutVersion` 与缓存里的版本不同 ⇒ 用 overrides 的 `layout` 覆盖缓存 | 需改 `packages/@core/preferences`（约 5-10 行）+ 自测；**收益是所有用户形态一致** |
| c. 抬命名空间 | 改 `VITE_APP_NAMESPACE` | 会把主题/语言/字号等**全部**用户偏好一起重置，副作用过大 |

本文 §7.5 把 b 放在 **P0**。

### 2.5 顶级菜单是不是按 `sys_menu.pid=0` 自动渲染？→ **是，前端不需要额外配置**

> **⚠️ 先纠正一个前提（R4）**：顶级导航**不是**"按 `type=catalog` 的顶级节点"渲染的，而是**按 `pid=0`**。后端唯一的类型过滤是**剔除 `type=button`**（`SysMenuServiceImpl.java:73`——**第二轮复核更正：早前写的 `:72` 是 `List<SysMenu> routable = menus.stream()`，过滤在下一行**），其余类型（`catalog` / `menu` / `embedded` / `link`）只要 `pid=0` **都会成为顶栏的一项**。
> **活库实证**：现有 13 个顶级菜单里，`2600 SystemFile` 的 `type` 是 **`menu`**、`4001 ApiDoc` 的 `type` 是 **`embedded`** —— 它们**不是** `catalog`，但同样出现在顶栏。
> ⇒ 对本方案有直接影响：**新增的模块目录必须是 `type=catalog` + `component=BasicLayout`**（否则它自己会变成一个可点开的空页面），但"会不会出现在顶栏"只由 `pid` 决定。

**后端：**

| 证据 | 文件:行号 | 内容 |
|---|---|---|
| 路由树入口 | `ypbin-auth/.../AuthController.java:86-89` | `GET /auth/menu/all` → `authService.currentRoutes()` |
| 树根 = `pid=0` | `ypbin-service/.../SysMenuServiceImpl.java:67-79` | `return buildRouteTree(visible, AdminConstants.ROOT_PARENT_ID);` |
| 根常量 | `ypbin-common/.../AdminConstants.java:38-39` | `ROOT_PARENT_ID = 0L` |
| 递归只按 `pid` 挂 | `SysMenuServiceImpl.java:340-354` | `if (pid.equals(menu.getPid())) { ... children = buildRouteTree(menus, menu.getId()) }`（**注意：pid 指向的父节点不在入参集合里 ⇒ 该子节点永远是孤儿、不会出现在结果中**，这是 K2 的机制） |
| **谁受 K2 影响** | `SysMenuServiceImpl.java:69-70`、`:74`、`:78`、`:82-100`；`SysRoleServiceImpl.java:236-252` | **只影响"非超管"用户**（超管走 `list(enabledMenusOrdered())` 全量菜单，父一定在集合里）。非超管有两道：<br>① `selectByUserId` 只回 `sys_role_menu` 里授过的菜单 ⇒ **父不在角色授权里就整棵丢**；<br>② 非平台用户再过 `applyTenantMenuFilter`（`:78`、`:82-100`）：`allowedIds` 来自 `sys_template_menu`（经 `resolveTenantMenuIds`），函数会从"被允许的菜单"沿 `pid` 上溯把祖先并进 `keptIds`，**但只并"已在该用户菜单集合里"的祖先**（`byId.get(currentId)` 为空即跳出）⇒ 它救不回角色侧缺失的父。<br>③ **更硬的一条**：租户管理员给角色配菜单走 `SysRoleServiceImpl#validateMenus`（`:236-252`，由 `:132`/`:152` 在新建/改角色时调用），`!allowedIds.containsAll(requestedIds)` 直接抛 **"角色授权包含租户权限模板之外的菜单"** ⇒ **模块目录不进 `sys_template_menu`，租户侧连角色都保存不了**。<br>⇒ 若模块目录 `platform_only=0`，**两张表都必须补授**（§7.1.1 第 3 步） |
| 排序 | `SysMenuServiceImpl.java:75-78`、`:333-338` | 先 `sort` 再 `id` |
| 按钮类型被剔除 | `SysMenuServiceImpl.java:73` | `filter(menu -> !TYPE_BUTTON.equals(menu.getType()))` |
| 平台专属过滤 | `SysMenuServiceImpl.java:74`、`:78` | `filter(menu -> platformUser \|\| !TRUE.equals(menu.getPlatformOnly()))` |
| 非超管菜单来源 | `SysMenuMapper.java:32-45`（`@Select` 在 `:32-44`、方法在 `:45`） | `sys_menu ⨝ sys_role_menu ⨝ sys_role ⨝ sys_user_role ⨝ sys_user` —— **只拿被授予的菜单** |
| title 即 i18n key | `SysMenuServiceImpl.java:386-388`（`meta.setTitle(menu.getTitle())`） | 前端 `generateMenus` 用 `title` 当菜单名：`packages/utils/src/helpers/generate-menus.ts:50`（`const name = (title \|\| routeName \|\| '')`），再由 `wrapperMenus` 走 `$t(...)`（`packages/effects/layouts/src/basic/layout.vue:183-191`） |

**前端：** `apps/web-antd/src/router/access.ts:17-33`（`fetchMenuListAsync: getAllMenusApi()`）→ `packages/effects/access/src/accessible.ts:38-60`、`:70`（`generateMenus`）。顶级节点带子路由时会被删掉自身 `component`（`accessible.ts:42-43`），避免嵌套两层 `BasicLayout`。

**溢出行为（问题 3 的后半）：** 水平菜单有"更多"折叠机制——`packages/@core/ui-kit/menu-ui/src/components/menu.vue:166`（`moreItemWidth = 46`）、`:177`（`calcWidth <= menuWidth - moreItemWidth` 决定切片点）、`:353-364`（`mode === 'horizontal' && getSlot.showSlotMore` 时渲染 `<SubMenu is-sub-menu-more path="sub-menu-more">`，标题是 `Ellipsis` 图标）。
> **强度声明：代码层面确定存在该机制；但"13 个顶级菜单在 1440px 下是否触发折叠、折叠几个"我没有渲染实测**（本机不跑前端全量构建），记为未核实 U1。**无论如何，把 13 项压到 4-5 项都是更稳的选择**——这正是 §4 的动机之一。

### 2.6 移动端：`mixed-nav` 在移动端**不会**生效

`packages/@core/ui-kit/layout-ui/src/hooks/use-layout.ts:8-10`：

```ts
const currentLayout = computed(() =>
  props.isMobile ? 'sidebar-nav' : (props.layout as LayoutType));
```

⇒ 移动端**强制** `sidebar-nav`（抽屉式侧边栏，见 `vben-layout.vue` 的 `maskVisible`/`isMobile` 分支）。**这既是好消息也是坏消息**：好消息是移动端不会被顶部大模块挤爆；坏消息是"顶部大模块"在移动端**完全不出现** ⇒ 移动端用户仍然只有一条长菜单。**本方案不为移动端另做设计**（IoT 场景以桌面为主），但这一点必须写进验收预期，否则会被当成 bug。

---

## 3. 表现形式对比与结论

### 3.1 五种候选对比

| 维度 | ① `mixed-nav`：顶部大模块 + 左侧二级 | ② 纯顶部导航（`header-nav`） | ③ 保持左侧单一长菜单（现状 `sidebar-nav`） | ④ 工作台门户页 + 顶部大模块 | ⑤ 多应用 / 多域名（微前端） |
|---|---|---|---|---|---|
| vben 原生支持 | ✅ 原生 7 种之一（`app.d.ts:6`） | ✅ 原生（`app.d.ts:4`） | ✅ 当前即是（`config.ts:28`） | ✅ `mixed-nav` + 一个首页页面（`defaultHomePath: '/dashboard'`，`config.ts:20`） | ❌ 需自建多应用宿主/路由分发 |
| 改装成本 | **改 1 行偏好 + 重建**（§2.4） | 改 1 行偏好 + 重建；但**还需要把顶级菜单压到 4-5 个**才不溢出 | 0 | ① 的成本 + 1 个首页页面（**页面已存在** `/dashboard/workspace`，见 §6.0） | 部署 + 登录态 + 免登 + nginx/域名 + 每个模块独立发版，**周级** |
| 13 个顶级菜单撑得住吗 | 需先归并到 4-5 个（本方案要做的） | ❌ 立刻溢出成「更多」（`menu.vue:353`），一级入口被藏 | ✅ 撑得住（垂直方向可滚动） | 同 ① | ✅ 但整包复杂度上一个量级 |
| 深度菜单（3 层：模块→分组→页） | ✅ 左侧垂直菜单原生支持嵌套 | ❌ 水平菜单放不下 3 层 | ✅ 支持 | 同 ① | 各自独立，互不影响 |
| 普通用户能否上手 | ✅ 顶部只有 4-5 个中文大词，左边是当前模块的菜单 → 认知负担最低 | ⚠️ 一级全平铺，找东西靠记忆/搜索 | ⚠️ 20+ 条长列表，靠滚动与搜索 | ✅ 首页卡片 = 显式入口，对新手最友好 | ⚠️ 用户要理解"在哪个站" |
| 与 vben 原生能力匹配度 | **最高**（顶部一级/左侧二级是 `mixed-nav` 的定义行为） | 高（但用途不同） | 高 | 最高 | 低（vben 5 是单应用架构） |
| 主要风险 | 见 §7.4（缓存偏好 / 孤儿菜单 / 门禁） | 一级入口被折叠隐藏，**且侧边栏整体不渲染**（`vben-layout.vue:165-168`、`:565`）⇒ 没有纵向承载二级/三级菜单的地方 | 不解决"整体拆分"诉求 | 首页维护成本（卡片要跟着模块变） | 登录态与权限跨站一致性；运维成本 |

### 3.2 推荐：**① + ④ 的组合**，即 `mixed-nav` + 一个真正的"工作台"首页

**为什么不是单纯的 ①：** 顶部大模块解决"分得清"，但**不解决"从哪下手"**——IoT 侧旧文 §3.4 已经诊断出"普通用户不知道从哪下手"是核心痛点之一（`IOT-UX-PROPOSAL.md` §3.4）。工作台首页用"模块卡片 + 我的设备概览 + 待处理告警 + 最近维护窗口"把入口显式化，与 ① 互补且几乎零额外成本（首页页面**已存在**，见 §6.0）。

**为什么不是 ②（纯顶部）：** 唯一优势是"看起来最像大平台"，代价是一级入口会溢出折叠（`menu.vue:166-177`、`:353-364`），而且左侧被浪费。**明确不推荐。**

**为什么不是 ③（保持现状）：** 完全没有回应"整体拆分成大模块"的需求；且 13 个顶级 + 30+ 二级的长列表正是用户提这个需求的原因。

**为什么不是 ⑤（多域名/微前端）：** vben 5 是**单应用**架构（一个 `apps/web-antd`、一套 `router`、一套 `accessStore`）。拆多应用意味着：跨域名免登（Sa-Token 会话共享）、两套菜单权限、两套 CI/部署、跨站跳转体验断裂。当前团队规模与模块数量（4-5 个）**完全不匹配**。**明确不推荐**，除非将来出现"必须由不同团队独立发版"的硬约束。

### 3.3 什么时候"不该拆"（R4，敢说）

- **如果模块数只有 2-3 个、且不会有更多**：不该引入顶部大模块。此时 `sidebar-nav` 下加一层目录就够，多一层顶部导航反而增加一处要维护的"布局开关"。**本平台的判断依据是：现有 13 个顶级菜单 + 已知的 AI/IoT 两条还在长的业务线**，所以拆是对的。
- **如果某个"模块"只有 1-2 个页面**：不该拆成模块。**这就是为什么不设「开放平台/开发者」模块**（全平台只有 `/system/app` 一个页面 + 一个内嵌接口文档），也正是**不把「授权管理」单列**的原因。硬拆的结果是顶栏多一个空壳、点开只有 2 项，比不拆更乱。
- **如果不做 K2 的补授 SQL**：**不要动 reparent**。半途而废的 reparent 会让一部分用户直接看不到菜单（不是变丑，是功能消失），比保持现状更糟。

### 3.4 外部对照（R2/R3）——含一条**对需求前提的纠正**

**本次直接复用**旧文 [`IOT-UX-PROPOSAL.md`](IOT-UX-PROPOSAL.md) §2 已完成的一手研究（7 个平台、官方链接、访问日期 2026-09-25，全文遵守"未采用二手来源作为结论依据"），**不重复抓取、不重复列举**。（该文 §2 是 **IoT 内页**视角，**不能**直接当作平台级导航的证据。）

本次另做了一轮"**平台级多模块导航表现形式**"的一手核实（全部 URL 实际抓取成功，访问日期 **2026-09-25**）：

| 平台 | 官方原文要点 | 信源 |
|---|---|---|
| **腾讯云** | 「您可以通过控制台总览页或**顶部导航**访问每个业务的控制台，查看**左侧菜单**及直观的操作界面」——**唯一明确写出"顶部导航 + 左侧菜单"双层结构的官方原文** | [一手 \| https://cloud.tencent.com/document/product/567/14454 \| 2026-09-25] [一手 \| .../567/14459 \| 2026-09-25] |
| **阿里云** | 「在管理控制台所有页面的**顶部，有固定的导航条**，提供以下主要功能：全局筛选功能…基础云服务入口…账号管理入口」；而**产品清单入口在左上角图标 →「产品与服务」→ 左侧导航栏**，含分类导航/搜索/最近访问/收藏 | [一手 \| https://help.aliyun.com/zh/management-console/top-navigation-bar \| 2026-09-25] [一手 \| .../product-and-service-navigation \| 2026-09-25] [一手 \| .../public-navigation-overview \| 2026-09-25] |
| **AWS** | Services 菜单在搜索框旁，含 Recently visited / Favorites / All applications / **All services（字母序全表）**，并**可按服务类型分组**（如 Analytics / Application Integration）；整条导航栏官方称 **Unified Navigation** | [一手 \| https://docs.aws.amazon.com/awsconsolehelpdocs/latest/gsg/service-menu.html \| 2026-09-25] [一手 \| .../unified-navigation.html \| 2026-09-25] |
| **Azure** | 官方术语是 **portal menu**（在服务间导航，设置里可选 **flyout 悬浮** 或 **docked 常驻左侧**）+ **page header** + **service menu**；全部服务入口：「To view all available services, select **All services** from the sidebar.」 | [一手 \| https://learn.microsoft.com/en-us/azure/azure-portal/azure-portal-overview \| 2026-09-25] |
| **钉钉管理后台** | 「**左侧是功能导航栏**，右侧展示组织的基本数据概览与快速入口」 | [一手 \| https://help.dingtalk.io/zh/oa/how-to-log-in-to-admin-console \| 2026-09-25] |
| **企业微信管理后台** | 官方 FAQ：「为什么管理后台**顶部的导航栏变成在左侧了**……将比较常用的几个管理功能放了出来，**导航改到了左侧边栏**」 | [一手 \| https://open.work.weixin.qq.com/help2/pc/17309 \| 2026-09-25] |
| **飞书管理后台** | **未核实**：官方文章标题存在，但 `feishu.cn` / `larksuite.com` 帮助中心正文由前端渲染，抓回的 HTML 内无正文；已探测 `/hc/api/*` 多路径仍无正文。**按 R1 记为未核实，不用二手来源补齐** | — |

#### ★ 对需求前提的纠正（R4，必须说）

用户原话是"整体拆分成大模块，**顶部增加一个大菜单（或其它表现形式）**"。核实结论是：**"顶部一级大模块"不是主流通式，只是其中一种**。三条反向证据都来自官方原文：

1. **企业微信**把导航**从顶部改到了左侧边栏**（官方 FAQ 明写，理由是"界面更加清爽"）——方向与"顶部加大菜单"相反。
2. **阿里云**的产品/服务清单入口在**左上角图标展开的左侧导航栏**里，不是顶栏横向排列的一级菜单；顶栏承担的是**全局能力**（地域筛选、费用、工单、消息、账号）。
3. **Azure** 的 portal menu 官方支持 **flyout（悬浮）与 docked（常驻左侧）两种模式**⇒"顶部还是左侧"在同一产品内都不是固定答案。

**四家一致的真实通式是**（顶栏承载全局能力 + 一个"全部服务/产品与服务/云产品目录"入口把超长清单收进二级面板 + 分类分组 + 全平台搜索；最近访问/收藏作兜底捷径）。
> 强度声明：上面这句"通式"的**逐条机制**都有官方原文；但**"模块超出顶栏宽度时该怎么处理"这一点，四家官方文档都没有写**，把它写成"主流原则"属**我方推断**，不是引用。另：**没有任何官方文档使用「More」这一术语**，故本文不把"More"列为已核实要素。
>
> ⚠️ **顺带否定一个常见的伪依据**：**"顶部大模块是主流做法"这个说法不成立**。本方案选它，理由在 §3.2（vben 原生支持 + 成本最低 + 5 个模块不触发溢出），**不是**因为"大家都这么做"。

#### 这条纠正对本方案的三点影响

1. **仍然选 `mixed-nav`**，但理由从"随主流"改为"**与 vben 原生能力匹配度最高 + 我方只有 4-5 个模块、不属"超长清单"场景**"（§3.2）。
2. **必须配一个"全部模块"兜底面**：主流做法在清单变长时会收进"全部服务"面板，而 **vben 5 没有原生的"全部服务/全部模块"页面**——一旦模块数超过 ~7 个、顶栏开始折叠，就必须自己补一个"全部模块"入口。当前 4-5 个模块**不需要**，但要在"何时该回头改"的判据里写明阈值（§7.5 P2-7）。
3. **全局搜索是官方通式的核心要素，本平台已经有了**：vben 右上角全局搜索遍历整棵 `accessMenus`（`user-dropdown.vue:347-352`）⇒ 层级变深后这条捷径的价值上升，**不改动**。


---

## 4. 大模块划分方案

### 4.1 主推方案：4 个模块 + 1 个工作台首页

| # | 顶级项 | `sys_menu.id` | `path` | `type` | `platform_only` | 目标用户 / 一句话场景 |
|---|---|---|---|---|---|---|
| 0 | **工作台** | `1`（不动） | `/dashboard` | catalog | 1 | 所有登录用户；"我今天该看什么、从哪进任一模块" |
| 1 | **基础管理** | **`3310`（新增）** | `/admin` | catalog | **0** | 管理员；组织/权限/租户/字典参数/消息/文件/授权 —— 平台"行政与配置" |
| 2 | **知识与 AI** | `5000`（**升格，pid 已是 0**） | `/ai` | catalog | 0 | 业务与知识运营；对话/知识库/Wiki/提示词/AI 角色/模型配置/用量 |
| 3 | **物联网** | `3204`（**已是顶级 catalog**） | `/iot` | catalog | 0 | 设备运维与集成；沿用旧文 §4.1 的 5 组 |
| 4 | **运维与监控** | **`3320`（新增）** | `/ops` | catalog | 1 | 平台运维；日志/在线用户/埋点分析/接口文档 |

**排序（`sort`，同级比较）**：`1 → -1`、`3310 → 2`、`3204 → 6`（不变）、`5000 → 9`（不变）、`3320 → 12` ⇒ 顶栏顺序 = 工作台 / 基础管理 / 物联网 / 知识与 AI / 运维与监控。

> **为什么 AI 排在 IoT 后面**：保持两模块现有 `sort`（6/9）不变 = **零额外变更**、且不改变任何人的既有肌肉记忆。若产品坚持"知识与 AI 在物联网前面"，只需把 `5000.sort` 改成 4（1 条 UPDATE），见 §7.5 P2。

#### 模块内二级分组怎么排

- **基础管理**（8 个一级子目录，按"人事 → 配置 → 内容 → 对外"排列）：
  1. **组织与权限**：`3001 组织管理`（用户/部门/岗位）、`3002 权限管理`（角色/菜单/客户端）
  2. **平台配置**：`3003 系统管理`（字典/参数）、`3005 租户管理`（租户列表/权限模板）
  3. **消息与文件**：`3007 消息中心`（通知公告/我的消息）、`2600 文件管理`
  4. **授权与开发**：`3008 授权管理`（授权列表/开放应用）、`4001 接口文档`
- **知识与 AI**（7 个平铺页面，顺序即现有顺序）：AI 对话 → 知识库 → 模型配置 → 提示词 → 用量统计 → Wiki 文档 → AI 角色。**建议**在 `5000` 下再插一层分组（对话与知识 / 模型与提示词 / 治理），但那是**新增数据**，放 P2（§7.5），P0/P1 先平铺。
- **物联网**：**沿用旧文 §4.1 已定的 5 组，顺序也照旧文**：`① 接入配置 → ② 设备管理 → ③ 运维中心 → ④ 数据与调试 → ⑤ 系统`（建议 group id `3210/3220/3230/3240/3250`，**均已实测空闲**）。不重复论证——见 [`IOT-UX-PROPOSAL.md`](IOT-UX-PROPOSAL.md) §4.1、§4.2。
  > ⚠️ **一处必须说明的不一致**：旧文 §4.1 的树是"运维中心(③) → 数据与调试(④)"，而旧文 `docs/ux-mock/README.md` 开头那句概括写的是"配置 → 设备 → **数据** → **运维** → 系统"。**本文与 `platform-nav.html` 一律以 §4.1 的编号顺序为准**（运维中心在数据与调试之前）；旧 README 那句概括属**表述漂移**，建议后续顺手改正，但它不影响任何实现。
  > ⚠️ **不要把"点位映射"当成独立菜单**：旧文把「属性与点位」放在**设备详情页的一个区块**里（§8 第 2 行），并且 §5 明确"参考 → 关系图与口径**不占菜单**"。本文照办。
- **运维与监控**（2 组）：
  1. **系统监控**：`3004 监控管理`（操作日志/在线用户）
  2. **埋点分析**：`3009 埋点管理`（事件列表/事件目录/分析/漏斗/留存）
  > `4001 接口文档` **只归「基础管理 → 授权与开发」**（理由见 §4.2 的 (A)：它 `platform_only=0`，放进 `platform_only=1` 的 3320 会在租户侧消失）。**本方案早前版本在 §4.1（运维与监控列了 4001）与 §4.2/§7.1.1 SQL（4001 归 3310）之间自相矛盾，已按独立复核意见统一为"基础管理"——全文与原型现在只有这一个归属。**
  > **命名取舍（R8）**："埋点分析"更偏**产品运营分析**而非运维。之所以暂放这里：它只有 5 个页面、且 `platform_only=1`（平台专用），单独成模块会得到"平台专用 + 5 项"的第二个小模块。**若将来埋点长成独立分析体系（归因/看板/实验），再拆出「数据分析」模块**——现在不拆。

### 4.2 现有顶级菜单归属表（**活库实测**，`pid=0` 共 13 条）

| 现 id | name | type | platform_only | path | 迁往 | 处理方式 |
|---|---|---|---|---|---|---|
| 1 | Dashboard | catalog | 1 | /dashboard | （不迁） | **保持顶级** = 工作台首页 |
| 3001 | OrgManage | catalog | 0 | /system/org | 基础管理 3310 | reparent |
| 3002 | AuthManage | catalog | 0 | /system/auth | 基础管理 3310 | reparent |
| 3003 | SysManage | catalog | 1 | /system/sys | 基础管理 3310 | reparent |
| 3005 | TenantManage | catalog | 1 | /system/tenant | 基础管理 3310 | reparent |
| 3007 | MessageManage | catalog | 0 | /message-center | 基础管理 3310 | reparent |
| 2600 | SystemFile | **menu** | 1 | /system/file | 基础管理 3310 | reparent |
| 3008 | LicenseManage | catalog | 1 | /system/license-manage | 基础管理 3310 | reparent |
| 4001 | ApiDoc | **embedded** | **0** | /api-doc | 基础管理 3310 | reparent（见下方 ⚠️） |
| 5000 | AiManage | catalog | 0 | /ai | （不迁） | **已是顶级 catalog** = 知识与 AI 模块 |
| 3204 | IotPlatform | catalog | 0 | /iot | （不迁） | **已是顶级 catalog** = 物联网模块 |
| 3004 | MonitorManage | catalog | 1 | /system/monitor | 运维与监控 3320 | reparent |
| 3009 | TrackingManage | catalog | 1 | /tracking | 运维与监控 3320 | reparent |

**⚠️ `4001 ApiDoc` 的三个选项（R8 主动提示）：**
- **(A) 推荐：放进「基础管理 → 授权与开发」，`platform_only` 保持 0** ⇒ 租户可见性**零变化**、零回归风险。
- (B) 放进「运维与监控」（`platform_only=1`）：**会在租户侧消失**（因 `SysMenuServiceImpl.java:74` 对非平台用户直接过滤掉 `platform_only=1` 的行）⇒ 属**有意的行为变更**，需要产品确认。
- (C) 保持顶级：顶栏变成 6 项，且它是一个 `embedded` 内嵌页，作为"大模块"没有意义。
本文取 (A)。

**模块目录自身的 `platform_only` 怎么定（关键规则，容易错）：**

> **一个模块目录的 `platform_only` 必须是 `0`，只要它下面有任何一个 `platform_only=0` 的子菜单；否则该子菜单对所有非平台用户变成孤儿（K2）。**
> 依据：`SysMenuServiceImpl.java:74`（非平台用户直接丢 `platform_only=1` 的行）+ `:340-354`（只按 `pid` 挂树）⇒ 父被丢、子永远接不上。
> 因此：`3310 基础管理` = **0**（下有 3001/3002/3007/4001 等租户可见项）；`3320 运维与监控` = **1**（子项 3004/3009 均 `platform_only=1`，4001 已按 (A) 移走）。

### 4.3 备选方案与取舍

**备选 1（更粗，3 模块）：** 基础管理 / 知识与 AI / 物联网 —— 把 `3004 监控`、`3009 埋点`、`3008 授权`、`4001 接口文档` 全塞进基础管理。
- 优点：顶栏最简（4 项），迁移面最小（只新增 1 个 id `3310`）。
- 缺点：基础管理变成"杂物筐"（11 个一级子目录）——**这正是用户体验上最糟的一种"拆大模块"**：拆了顶部、左侧反而更长了。
- **不推荐**，但如果要"先把效果做出来、两周内上线"，它是合理的中间态（= §7.5 的 P0'）。

**备选 2（更细，5 模块）：** 从基础管理里再拆出「**租户与授权**」（3005 + 3008）。
- 优点：`platform_only=1` 的东西集中，租户用户完全看不到该模块；语义干净。
- 缺点：该模块只有 4 个页面（租户列表/权限模板/授权列表/开放应用），**接近空壳**；顶栏 5 项 + 首页 = 6 项，接近溢出边缘。
- **暂不推荐**，条件是：**当租户/授权相关页面超过 8 个时再拆**。

**备选 3（把「开放平台/开发者」独立成模块）：明确否决。** 全平台只有 `/system/app`（开放应用，含 `access_key/secret_key` 与 4 个权限码 `system:app:add|edit|delete|reset-secret`）+ `/api-doc`（内嵌 swagger）两个页面；**Webhook 在 `ypbin-iot` 仓内全库检索（`--include=*.java,*.sql,*.ts`）结果为空 ⇒ 当前无此功能**。按 R1，**不硬造**。

### 4.4 i18n key 命名规范

**规范：** `page.<module>.<group>.<page>.<field>`，其中 `<module>` 用**模块短名**（`admin`/`ai`/`iot`/`ops`），`<page>` 用业务名，叶子用 `title` 作页面标题键。

**本次必须新增的键（只有 2 个，避免大范围改名）：**

| key | zh-CN | en-US | 落在哪个文件 |
|---|---|---|---|
| `page.admin.title` | 基础管理 | Administration | `apps/web-antd/src/locales/langs/zh-CN/page.json` + `en-US/page.json` |
| `page.ops.title` | 运维与监控 | Operations | 同上 |

**已存在、本次不改键的键（实测 `origin/main`，`apps/web-antd/src/locales/langs/*/page.json`）：**

| key | zh-CN 现值 | en-US 现值 | 用途 | 本次动作 |
|---|---|---|---|---|
| `page.dashboard.title` | **概览** | Dashboard | 顶级菜单 `1 Dashboard` | 键不动。**建议改值**为「工作台 / Workspace」（要"工作台"这个名字就必须改，否则顶栏显示"概览"） |
| `page.iot.title` | IoT 平台 | IoT Platform | 顶级菜单 `3204 IotPlatform` | **不动**（已在 `origin/main`，由 PR #17 / `b067f41` 补齐）。用户原话也是"IoT 平台"，故模块名沿用 |
| `page.ai.title` | **AI 助手** | AI Assistant | 顶级菜单 `5000 AiManage` | 键不动。**建议改值**为「知识与 AI / Knowledge & AI」（否则顶栏显示"AI 助手"，与"知识与 AI 模块"口径不一致） |
| `system.*` / `tracking.*` 下的全部既有键（`system.org.title`、`system.auth.title`、`system.sys.title`、`system.tenant.title`、`system.messageCenter.title`、`system.file.title`、`system.license.title`、`system.apiDoc.title`、`system.monitor.title`、`tracking.title` 等） | 各自现值 | 各自现值 | 被 reparent 的 10 个顶级菜单 | **一律不动**（键与值都不动） |

> **⚠️ 铁律一：只增键、不改键名。** `sys_menu.title` 直接就是 i18n key（`SysMenuServiceImpl.java:388` → `generate-menus.ts:50` → `$t()`），重命名 key 意味着**同时**改 DB 数据与两份语言包，任何一处漏改都会让菜单显示原始 key（旧仓已经踩过一次"95 处文案渲染成原始 key"的事故，见 `scripts/check-iot-i18n-keys.mjs` 头部注释）。
> **⚠️ 铁律二：改"值"是安全的，但仍要两份语言包同时改。** 上面两处"建议改值"（`page.dashboard.title`、`page.ai.title`）只改字符串、不动 key，风险与改一条文案相同；但如果只改 zh-CN 不改 en-US，切到英文时会看到中文（或反之），必须成对改。
> **⚠️ 铁律三：`page.ai.title = "AI 助手"` 这个现值意味着一件事——模块名必须与产品确认。** 本文 §4.1 用的是「知识与 AI」，与现值不一致；**这是需要产品拍板的一处口径**（见 §9 U7）。若产品接受沿用「AI 助手」，则 §4.1 的模块名应同步改回，`page.ai.title` 零改动。

**新模块里的新页面的命名示例**（供后续沿用）：`page.iot.devices.title`、`page.iot.onboarding.title`、`page.admin.tenant.list`、`page.ops.tracking.funnel`。

### 4.5 明确"不设"的模块（结论表）

| 候选模块 | 现有功能盘点 | 结论 |
|---|---|---|
| 开放平台 / 开发者 | `/system/app` 开放应用（有 access_key/secret_key + 4 权限码）、`/api-doc` 接口文档 | **不设模块**。归入基础管理「授权与开发」分组。只有 2 项，独立成模块即空壳 |
| Webhook / 事件订阅 | `ypbin-iot` 全库检索 `webhook` **0 命中** | **当前无此功能，先不设**（不硬造） |
| 任务与调度（xxl-job） | `deploy/sql/005-xxl-job.sql` 只在**独立的 `xxl_job` 库**建表（`xxl_job_info`/`xxl_job_log`/`xxl_job_user` 等），`sys_menu` 里**没有任何**任务菜单 | **不设模块**。定时任务目前只能去 xxl-job **自带控制台**（独立登录）操作；若要纳入本平台导航，需先做**集成**（iframe/免登/接口代理），属独立需求，**不在本方案范围**。本方案仅把"运维与监控"的**埋点**留在 P2 备选位置 |
| 个人中心 / 我的消息 | `/message`（`MyMessage`，在 3007 下） | **不设模块**，保留在「基础管理 → 消息与文件」。**建议**后续把 `/message` 移到右上角铃铛（vben 原生 `#notification` 插槽已存在，见 `packages/effects/layouts/src/basic/layout.vue:378-383`），但这**需要前端改动**，放 P2 |

---

## 5. 导航信息架构图（mermaid）

```mermaid
flowchart TD
  P["ypbin 平台<br/>（vben mixed-nav：顶部一级 + 左侧二级）"]

  P --> DASH["工作台 /dashboard<br/>catalog id=1（复用）"]
  P --> ADMIN["基础管理 /admin<br/>catalog id=3310（新增）"]
  P --> AI["知识与 AI /ai<br/>catalog id=5000（复用·已顶级）"]
  P --> IOT["物联网 /iot<br/>catalog id=3204（复用·已顶级）"]
  P --> OPS["运维与监控 /ops<br/>catalog id=3320（新增）"]

  DASH --> DASH1["分析页 /dashboard/analytics（复用）"]
  DASH --> DASH2["工作台 /dashboard/workspace（复用 → 升级为模块门户，见 §6）"]

  ADMIN --> ORG["组织与权限<br/>3001 组织管理（复用）"]
  ADMIN --> AUTH["3002 权限管理（复用）"]
  ADMIN --> SYS["平台配置<br/>3003 系统管理（复用）"]
  ADMIN --> TEN["3005 租户管理（复用）"]
  ADMIN --> MSG["消息与文件<br/>3007 消息中心（复用）"]
  ADMIN --> FILE["2600 文件管理（复用）"]
  ADMIN --> LIC["授权与开发<br/>3008 授权管理（复用）"]
  ADMIN --> DOC["4001 接口文档（复用）"]
  ORG --> ORG1["用户 / 部门 / 岗位（复用）"]
  AUTH --> AUTH1["角色 / 菜单 / 客户端（复用）"]
  SYS --> SYS1["字典 / 参数（复用）"]
  TEN --> TEN1["租户列表 / 权限模板（复用）"]
  MSG --> MSG1["通知公告 / 我的消息（复用）"]
  LIC --> LIC1["授权列表 / 开放应用（复用）"]

  AI --> AI1["AI 对话（复用）"]
  AI --> AI2["知识库（复用）"]
  AI --> AI3["Wiki 文档（复用）"]
  AI --> AI4["提示词（复用）"]
  AI --> AI5["AI 角色（复用）"]
  AI --> AI6["模型配置（复用·平台专用）"]
  AI --> AI7["用量统计（复用·平台专用）"]

  IOT --> IOT1["① 接入配置 (3210)"]
  IOT --> IOT2["② 设备管理 (3220)"]
  IOT --> IOT3["③ 运维中心 (3230)"]
  IOT --> IOT4["④ 数据与调试 (3240)"]
  IOT --> IOT5["⑤ 系统 (3250)"]
  IOT1 --> IOT1a["接入向导 /iot/onboarding（新增页）"]
  IOT1 --> IOT1b["产品模板中心 /iot/templates（缺接口）"]
  IOT1 --> IOT1c["产品与物模型 /iot/products（复用）"]
  IOT1c --> IOT1c1["产品详情 /iot/products/:id（新增页）"]
  IOT2 --> IOT2a["设备台账 /iot/devices（复用）"]
  IOT2 --> IOT2b["设备分组 /iot/groups（复用）"]
  IOT2a --> IOT2a1["设备详情 /iot/devices/:id（新增页）<br/>内区块：概览 / 属性与点位（复用 iot:point:*） / 历史曲线 / 事件与断档 / 在线调试 / 设备影子 / 日志"]
  IOT3 --> IOT3a["健康总览 /iot/overview（缺接口·聚合统计）"]
  IOT3 --> IOT3b["断档与可用率 /iot/availability（缺接口·批量端点）"]
  IOT3 --> IOT3c["维护窗口 /iot/maintenance（复用 3203）"]
  IOT3 --> IOT3d["事件与告警 /iot/alerts（缺接口·G6）"]
  IOT4 --> IOT4a["时序查询 /iot/series（复用 iot:series:get）"]
  IOT4 --> IOT4b["在线调试 /iot/debug（缺接口·下行通道 G3）"]
  IOT4 --> IOT4c["设备影子 /iot/shadow（复用 iot:shadow:*，reported 恒空 G2）"]
  IOT5 --> IOT5a["数据保留与清理 /iot/retention（缺接口·G7）"]
  IOT5 --> IOT5b["租户接入台账 /iot/tenant-ledger（复用 iot:ledger:*·平台级）"]
  IOT -.->|不占菜单·参考页| IOT6["关系图与口径（新增页·旧文 §5.1）"]

  OPS --> MON["系统监控<br/>3004 监控管理（复用）"]
  OPS --> TRK["埋点分析<br/>3009 埋点管理（复用）"]
  MON --> MON1["操作日志 / 在线用户（复用）"]
  TRK --> TRK1["事件列表 / 事件目录 / 分析 / 漏斗 / 留存（复用）"]

  classDef reuse fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
  classDef add fill:#e3f2fd,stroke:#1565c0,color:#0d47a1
  classDef gap fill:#ffebee,stroke:#c62828,color:#b71c1c
  class DASH,DASH1,DASH2,ORG,AUTH,SYS,TEN,MSG,FILE,LIC,DOC,ORG1,AUTH1,SYS1,TEN1,MSG1,LIC1,AI,AI1,AI2,AI3,AI4,AI5,AI6,AI7,IOT1c,IOT2a,IOT2b,IOT4a,IOT4c,IOT5b,MON,TRK,MON1,TRK1 reuse
  class ADMIN,OPS,IOT1a,IOT1c1,IOT2a1,IOT6 add
  class IOT1b,IOT3a,IOT3b,IOT3d,IOT4b,IOT5a gap
```

> **图例**：绿 = **复用**（后端接口/权限码已存在）；蓝 = **新增**（需新建页面，接口已就绪 = 旧文的 `[新增页]`）；红 = **缺接口**（后端需补能力 = 旧文的 `[新增接口]`）；**未着色 = 分组节点**（本身不是页面，不标来源）。
> **IoT 模块内部的全部细节**（每个页面长什么样、缺哪 12 条数据模型）**一律以 [`IOT-UX-PROPOSAL.md`](IOT-UX-PROPOSAL.md) §4.1/§4.2/§5/§6/§8 为准**；上图 IoT 分支的**分组、顺序、来源标记逐条照抄旧文 §4.1**，未做二次判断。
> ⚠️ 「点位映射」在旧文里是**设备详情页的一个区块**（§8 第 2 行，`iot:point:*` 四个权限码均已存在），**不是独立菜单**；「关系图与口径」旧文明确**不占菜单**（§5 第 5 条），上图用虚线标出仅作参考。
> 图中"新增/缺接口"只表达**后端能力是否存在**，与本文 §7 的 P0/P1/P2 排期**不是一回事**（例如"接入向导"是新增页，但旧文已把它列在只改前端即可先做的一批里）。

---

## 6. 首页 / 工作台设计

### 6.0 一个重要事实：工作台页面**已经存在**

`apps/web-antd/src/views/dashboard/workspace/index.vue` 已在仓内，且 `sys_menu` 里已有菜单 `102 Workspace`（`/dashboard/workspace`，挂在 `1 Dashboard` 下）；`defaultHomePath` 也是 `/dashboard`（`packages/@core/preferences/src/config.ts:20`）。

⇒ **不需要新建"首页"页面**。P0 的做法是**把现有的 `102 Workspace` 升级成模块门户**（旧文 §9.5 的"只改前端"清单里已有同类条目），并把 `/dashboard` 的重定向指到它。**这是一项"改内容"而非"加架构"的工作**，风险低。

### 6.1 线框（ASCII）

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  ◈ Ypbin 平台        工作台   基础管理   知识与 AI   物联网   运维与监控    🔍   🔔³   ▾平台管理员 │  ← 顶部大模块条（mixed-nav 的 headerMenus）
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ 工作台 / 概览                                                                             │  ← 面包屑（route.matched，见 §7.4）
│                                                                                          │
│ ┌── 模块入口 ─────────────────────────────────────────────────────────────────────────┐ │
│ │ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                 │ │
│ │ │ 🧩 基础管理   │ │ 🤖 知识与 AI  │ │ 📡 物联网     │ │ 🛠 运维与监控  │                 │ │  ← 模块卡片（点击 = 切到该模块）
│ │ │ 组织·权限·配置 │ │ 知识库·模型   │ │ 设备·产品·数据 │ │ 日志·埋点·文档 │                 │ │
│ │ │ 用户管理 →    │ │ 知识库 →      │ │ 设备台账 →    │ │ 操作日志 →     │                 │ │  ← 卡片内 2-3 个常用入口（深链）
│ │ │ 角色管理 →    │ │ 用量统计 →    │ │ 维护窗口 →    │ │ 漏斗分析 →     │                 │ │
│ │ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘                 │ │
│ └──────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                          │
│ ┌── 我的设备概览 ────────────────┐ ┌── 待处理告警 ──────────────────────────────────┐  │
│ │ 设备总数 128   在线 117        │ │ ⚠ 高  demo-plc-03 通信中断           09:12      │  │
│ │ 离线   9       从未上报 2      │ │ ⚠ 中  demo-meter-11 上报间隔超阈值    08:40      │  │
│ │ （点任一数字 → 设备台账带筛选） │ │ ℹ 低  demo-gw-02 固件版本落后         昨天      │  │
│ └────────────────────────────────┘ └───────────────────────────────────────────────┘  │
│                                                                                          │
│ ┌── 最近维护窗口 ─────────────────────────────────────────────────────────────────────┐ │
│ │ demo-plc-03    2026-09-26 02:00 → 04:00   已结束（断档已剔除）                       │ │
│ │ 分组：A 车间   2026-09-27 01:00 → 03:00   计划中                                     │ │
│ │                                        → 去维护窗口页（复用 /iot/maintenance）        │ │
│ └──────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 三块内容各自回答的问题

| 区块 | 回答的问题 | 数据来源（现状） |
|---|---|---|
| **模块卡片** | "这个平台有哪些大块、我该去哪" | **静态**（4 张卡片写死在前端，模块增减时改一处）—— 不引入新接口 |
| **我的设备概览** | "我的设备现在有没有事" | 旧文 §6.5「健康总览」的同一份数据；P0 可先做**静态壳 + 复用已有设备列表接口**（旧文 §9.5 已标"只改前端"） |
| **待处理告警 / 最近维护窗口** | "有什么在等我处理" | 维护窗口接口**已有**（`iot:maintenance:list`）；告警链路**后端不存在**（旧文 §0.2 缺口），P0 先展示"暂无告警（能力未上线）"而**不伪造数据** |

### 6.3 普通用户从首页怎么进入任一模块（三条路径，都要能走通）

1. **点模块卡片** → 切到该模块（= 顶部模块高亮 + 左侧换成该模块菜单 + 停在卡片里选定的页面，如"设备台账"）。
2. **点顶部模块条** → 只切左侧菜单，不跳页面（vben 默认行为：`use-mixed-menu.ts:88-110`；因为 `sidebar.autoActivateChild` 默认 `false`）。**建议在 P1 打开 `sidebar.autoActivateChild`**，让"点模块即进第一页"，减少一次点击。
3. **面包屑 / 全局搜索** → 面包屑可回上级；全局搜索（右上角）遍历整棵 `accessMenus`（`packages/effects/layouts/src/widgets/user-dropdown/user-dropdown.vue:347-352` 把 `accessStore.accessMenus` 传给 `GlobalSearch`），**跨模块直达任意页面**——这条在菜单层级变深后价值更高。

---

## 7. 落地与迁移计划

### 7.1 SQL 迁移方案（**只给方案，本文不执行**）

> **纪律：不改生产库。** 下面的 SQL 是给后续实施者的方案；`migration/*.sql` 与 `007` 的**双写**必须同时做，否则 `tools/check-iot-sql-equivalence.sh` 会红（见 §7.1.3）。

#### 7.1.1 新增与 reparent（**P1**，最终形态）

```sql
-- =============================================================
-- 平台模块菜单（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 内容：新增 2 个顶级模块目录（3310 基础管理 / 3320 运维与监控），
--       把 10 个既有顶级菜单 reparent 到模块下（8 个进 3310、2 个进 3320）；其余 3 个顶级（1/3204/5000）保持不变。
-- id 规划：见 §7.2（3310/3320 经活库 3300-3399 全段查询确认未占用）
-- 回滚：见同批次回滚脚本（pid 回 0 + 删除 3310/3320 的三处行 + 撤销补授）
-- =============================================================

-- 1) 新增两个模块目录（catalog + BasicLayout，层级形态与 3204/3009 一致）
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3310, 0, 'BasicAdmin', 'catalog', 0, '/admin', 'BasicLayout', NULL, 'page.admin.title', 'carbon:settings-adjust', 2, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3320, 0, 'PlatformOps', 'catalog', 1, '/ops', 'BasicLayout', NULL, 'page.ops.title', 'carbon:activity', 12, NOW(), 1, 0);

-- 2) 模块目录自身的授权（形态与既有 007 段一致；门禁 IotMaintenanceAdminGateTest 可识别）
--    ⚠️ 3310 必须同时授给 sys_template_menu —— 不是"保险"而是**必需**：
--       3310 的 platform_only=0，会被 resolveAvailableMenuIds 收进"租户可授菜单"（SysAuthTemplateServiceImpl.java:199-209）；
--       若不进模板，租户管理员新建/修改角色时会被 SysRoleServiceImpl#validateMenus
--       （:236-252，由 :132/:152 调用）抛「角色授权包含租户权限模板之外的菜单」——租户侧连角色都保存不了。
--       （3310 自身也仍有"平台管理员看不见模块"的风险，故 sys_role_menu 同样必需。）
--       3320 只授 sys_role_menu：其下全是 platform_only=1 的平台专用菜单，按既有约定不进 sys_template_menu
--       （先例：007-iot-data.sql:91-97 的 M2 台账菜单同样"只授角色 1，绝不进 sys_template_menu"）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3310, 3320);
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3310);

-- 3) ★关键★ 防孤儿（K2）：把模块目录补授给「已经拥有其任一子菜单」的**所有**角色/模板。
--    只授 role 1 是不够的：任何自定义角色/租户模板只要拥有子菜单而缺父目录，
--    该子菜单就会被 buildRouteTree 整棵丢弃（SysMenuServiceImpl.java:340-354）。
--    用 INSERT IGNORE 兜住 PK(role_id,menu_id) / PK(template_id,menu_id) 的重复（001-schema.sql:147-152、:477-482）。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 3310 FROM sys_role_menu rm
WHERE rm.menu_id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001) AND rm.role_id <> 1;
INSERT IGNORE INTO sys_template_menu (template_id, menu_id)
SELECT DISTINCT tm.template_id, 3310 FROM sys_template_menu tm
WHERE tm.menu_id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001) AND tm.template_id <> 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 3320 FROM sys_role_menu rm
WHERE rm.menu_id IN (3004, 3009) AND rm.role_id <> 1;
-- 3320 的模板侧同款补授（**第二轮复核新增**）：生产数据下不可能命中——`resolveAvailableMenuIds`
-- （SysAuthTemplateServiceImpl.java:199-209，`:207` 过滤 `platform_only=false`）不允许 platform_only=1
-- 的菜单进模板。但「模板侧孤儿恒为 0 行」（§7.1.2）是本文要维持的不变量，补这一条把 latent gap
-- 变成结构性保证：将来若 3004/3009 或 3320 被改成 platform_only=0，不会有整棵丢失的窗口。
INSERT IGNORE INTO sys_template_menu (template_id, menu_id)
SELECT DISTINCT tm.template_id, 3320 FROM sys_template_menu tm
WHERE tm.menu_id IN (3004, 3009) AND tm.template_id <> 1;

-- 4) reparent：子菜单的 path / component / auth_code 一律不动（⇒ 书签 URL 与权限码不变）
UPDATE sys_menu SET pid = 3310 WHERE id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001);
UPDATE sys_menu SET pid = 3320 WHERE id IN (3004, 3009);
```

**为什么 `4001 ApiDoc` 走 3310**：见 §4.2 的 (A) —— 它是 `platform_only=0`，放进 `platform_only=1` 的 3320 会让它在租户侧消失。

#### 7.1.2 迁移后必须跑的**孤儿自检**

> **⚠️ 角色侧那条查询在迁移前**就不是** 0 行（实测），别把"迁移后仍有行"误判成迁移回归。**

**实测（迁移前的活库现状，命令见附录 C）：**

| 查询 | 迁移前实际结果 |
|---|---|
| 角色侧孤儿（子被授予、父未授予同角色） | **5 行**：`role 1` 的 `230 SystemMenu→3002`、`290 SystemClient→3002`、`5003 AiConfig→5000`、`5050 AiUsage→5000`、`270004 SystemPushTest→2700` |
| 模板侧孤儿 | **0 行** |

这 5 行是 `002-data.sql` 的种子方式造成的**既有现象**：它给 role 1 授的是 `platform_only=1` 的菜单，而 `3002 AuthManage` / `5000 AiManage` / `2700 SystemNotice` 自身是 `platform_only=0` ⇒ 父没进 role 1 的授权。**role 1 是超管**（`SysMenuServiceImpl.java:69-70` 走 `enabledMenusOrdered()` 全量菜单），所以功能上**没有可见影响**；受影响的是**非超管角色**——这也正是 K2 的适用边界（见 §2.5）。

**因此正确的自检口径是：**

```sql
-- 角色侧：迁移后应与「迁移前基线」一致（期望仍是同样那 5 行，且不新增）
SELECT rm.role_id, m.id AS child_id, m.name AS child_name, m.pid AS missing_parent
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0 AND m.status = 1
LEFT JOIN sys_role_menu pr ON pr.role_id = rm.role_id AND pr.menu_id = m.pid
WHERE m.pid <> 0 AND pr.menu_id IS NULL
ORDER BY rm.role_id, m.id;

-- 模板侧：期望 0 行（当前实测就是 0 行；迁移后若出现行 = 真回归）
SELECT tm.template_id, m.id AS child_id, m.name AS child_name, m.pid AS missing_parent
FROM sys_template_menu tm
JOIN sys_menu m ON m.id = tm.menu_id AND m.is_deleted = 0 AND m.status = 1
LEFT JOIN sys_template_menu pt ON pt.template_id = tm.template_id AND pt.menu_id = m.pid
WHERE m.pid <> 0 AND pt.menu_id IS NULL;

-- 期望 0 行：父目录 platform_only=1 但下面挂着 platform_only=0 的子菜单（§4.2 的规则）
SELECT p.id AS parent_id, p.name AS parent, c.id AS child_id, c.name AS child
FROM sys_menu p JOIN sys_menu c ON c.pid = p.id AND c.is_deleted = 0
WHERE p.pid = 0 AND p.platform_only = 1 AND c.platform_only = 0;
```

> **更好的做法（建议顺手做）**：迁移**之前**先把角色侧那条查询的输出存成基线文件，迁移后用 `diff` 比对 —— "不新增孤儿"才是正确且可验证的口径，"绝对 0 行"不是。
> **本条是独立复核发现的**：本文早前版本写"两条都期望 0 行"，实测迁移前角色侧就有 5 行 ⇒ 已按复核意见更正（见 §8）。

#### 7.1.3 双写与等价性门禁

`tools/check-iot-sql-equivalence.sh` 的规则（本次**逐行读过**）：

- 比较对象 = `deploy/sql/006-iot-schema.sql` + `deploy/sql/007-iot-data.sql` **拼接** ↔ `deploy/sql/migration/*-iot-*.sql` 按文件名排序**拼接**。
- 归一化只做：去 `--` 行注释、压缩连续空白、去空行；**不做分号级归一化** ⇒ 语句必须**逐字相同**。
- 只收**文件名含 `-iot-`** 的迁移文件；没有匹配文件就直接报错退出（防"空跑假绿"）。

⇒ **本次新迁移文件必须命名为 `<日期>-iot-platform-module-menu.sql`**（例如 `2026-09-26-iot-platform-module-menu.sql`），并把**同一批语句原样追加到 `007-iot-data.sql` 末尾**。
> ⚠️ **命名不含 `-iot-` 的后果，分两种情况（第二轮复核更正，早前"一律恒绿"的说法不准确）：**
> ① **只**写迁移文件、**不**追加到 `007`：该文件被 `ls deploy/sql/migration/*-iot-*.sql` 静默排除 ⇒ 等价性检查**恒绿（exit 0）但没检查它**，而 fresh 安装永远缺 `3310/3320`。**这才是真正的危险形态**（复核者实测 E5：`grep -c 3310` 在 `007` 为 0、在被排除的迁移文件里为 13）。
> ② **同时**追加到 `007`：比较双方都少了这几句，但 fresh 侧（006+007）有、migration 侧没有 ⇒ 脚本**转红**（复核实测 E4：`exit 1`）。
> ⇒ 两种都必须避免：**文件名含 `-iot-` 且同步追加 007**，缺一不可。
> ⚠️ 追加顺序必须与文件名排序一致（新文件排在 `2026-09-25-iot-menu-group.sql` 之后，所以追加在 007 末尾即可）。
> ⚠️ 注意 `007-iot-data.sql:4-5` 的既有约定：`002-data.sql` 的批量授权在本文件**之前**执行，所以本文件必须自己再授一次权——上面的第 2 步就是照这个约定做的。

#### 7.1.4 菜单授权门禁怎么保过（**K3，最容易踩；本节已按独立复核的实测结论整段重写**）

`IotMaintenanceAdminGateTest#everyMenuIdMustBeGranted`（`ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/config/IotMaintenanceAdminGateTest.java:110-146`；**行号已按落地代码更新**，实施前的旧行号是 `:62-101`）的核心是：

```java
for (String table : List.of("sys_role_menu", "sys_template_menu")) {
    Set<String> granted = new LinkedHashSet<>();
    String[] blocks = sql.split("INSERT INTO " + table);     // ← 问题在这一行
    for (int i = 1; i < blocks.length; i++) {
        Matcher matcher = GRANT_IN.matcher(blocks[i]);       // GRANT_IN = "id IN \\(([^)]*)\\)"
        while (matcher.find()) { /* granted.add(...) */ }
    }
}
if (id.startsWith("32") && !granted.contains(id)) { missing.add(id); }
```

##### （1）先说一个**已存在的严重缺陷**：这张门禁的 `sys_template_menu` 覆盖检查是**假绿**

`sql.split("INSERT INTO " + table)` 切出来的 block **不只覆盖该表自己的语句**——它一直延伸到**下一次**出现同一张表的 INSERT 为止，中间夹着的**其它表的 INSERT、乃至 `UPDATE ... WHERE id IN (...)`**，其 `id IN (...)` 也被算进了本表的 `granted`。

**实测（复刻门禁逻辑跑真实 `007-iot-data.sql`，命令与输出见附录 C）：**

| 实测项 | 结果 |
|---|---|
| 从 `sys_role_menu` 块收集到的 `granted` | **33 条** |
| 从 `sys_template_menu` 块收集到的 `granted` | **33 条**（**与上完全相同**） |
| `320014` 是否出现在任何 `INSERT INTO sys_template_menu` 语句里 | **否**（`007-iot-data.sql:90-97` 明文写"绝不进 sys_template_menu"） |
| 门禁是否认为 `320014` 已被 `sys_template_menu` 授权 | **是**（被 `:96-97` 的 role 授权语句污染） |
| **变异**：删掉 `3310` 的 `sys_template_menu` 授权 | **不转红**（`MISSING=[]`） |

⇒ **结论：这条门禁对 `sys_template_menu` 的检查根本不生效**；它实际等价于"该 id 在文件里任意一处 `id IN (...)` 里出现过"。两份 SQL 的授权**同时**被删时它确实会红（那是它唯一挡得住的情形），但**只删模板侧授权它抓不住**。

> **⚠️ 同时撤回本文更早版本的一个错误判断**：本文曾断言"天真扩到 `startsWith("33")` 会产生假红"。**实测不成立** —— 因为模板侧的 `granted` 早已被污染，`3320` 不在 `sys_template_menu` 也会被判为已授权（`MISSING=[]`）。**问题不是"会假红"，而是"根本不咬人"。** 该错误已按独立复核意见更正（见 §8）。

##### （2）还要知道：**即使修好作用域，"两张表都必须覆盖"这条规则本身也是错的**

把收集方式改成**语句级归属**（先剥 `--` 注释、按 `;` 切语句、只在该语句确实是 `INSERT [IGNORE] INTO <本表>` 时取语句内的 `id IN (...)`）后，在**真实 007** 上跑朴素规则（32xx 一律要求两张表）：

```
sys_template_menu 缺失 = ['320014', '320015']     ← 二者均 platform_only = 1
```

`320014/320015` 是**故意**不进 `sys_template_menu` 的 M2 台账菜单（`007-iot-data.sql:90-97`）。⇒ 朴素规则会在**完全正确的数据**上报红。**所以"修作用域"与"改规则"必须同时做，只做一件都会得到错误结论。**

##### （3）修正版门禁（**已实测：基线通过 + 三个变异都能咬人**）

```java
/** 菜单 INSERT 的值元组 (id, pid, name, type, platform_only)，含多行 INSERT 的续行。 */
private static final Pattern MENU_INSERT = Pattern.compile(
    "\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*([01])\\s*,");

/** 授权语句里的 id 清单。**必须锚定词边界**（第二轮复核新增）： */
/** 旧写法 `id IN \(([^)]*)\)` 会命中 `menu_id IN (...)`，把防孤儿 SQL 里的**子菜单 id** 也当成授权。 */
private static final Pattern GRANT_IN = Pattern.compile("(?<![A-Za-z0-9_])id\\s+IN\\s*\\(([^)]*)\\)");

// 收集授权必须**按语句归属**：先剥 -- 注释，再按 ; 切语句，
// 只在该语句确实是 INSERT [IGNORE] INTO <本表> 时，才取本语句内的 `id IN (...)`。
// 切勿再沿用 sql.split("INSERT INTO " + table) —— 它的 block 会跨语句串味（实测两表 granted 完全相同），
// 也切勿沿用逐行 `VALUES (` / `^(` 扫描 —— 它会把注释掉的 INSERT 当真实菜单、漏掉同行第二个元组。
```

> **第二轮复核补充的两条细节**（都已实测）：
> 1. **`GRANT_IN` 必须锚定词边界**（`(?<![A-Za-z0-9_])id`）。修正版里新增的防孤儿语句写的是 `WHERE tm.menu_id IN (3001, …)`，未锚定时 `menu_id` 里的 `id` 会被命中、把**子菜单 id** 灌进 `granted`。当前被检集合只含 `32xx/33xx` 而泄漏的是 `30xx/40xx`，结论不受影响，但一旦哪个防孤儿清单里出现 `32xx/33xx` 就会重新假绿。
> 2. **`MENU_INSERT` 全局 `finditer` 顺带修掉了原门禁的两个 latent 缺陷**：① 原 `MENU_ID` 要求行内有 `VALUES (`、`MENU_ID_CONT` 要求行首 `(` ⇒ 同一行多行 VALUES 的第二个 id 会**漏扫**；② 原扫描不剥注释 ⇒ **注释掉的 INSERT 会被当成真实菜单**（两者当前数据尚未触发，但属真实缺口）。

期望（对每个 `32xx` / `33xx` 菜单 id）：

1. **必须**出现在 `sys_role_menu` 的授权里；
2. **仅当**该菜单自身 `platform_only = 0` 时，才**必须**同时出现在 `sys_template_menu`（理由不止"防孤儿"：`sys_template_menu` 还是**租户管理员配角色时的白名单** —— `SysRoleServiceImpl#validateMenus:236-252` 用 `allowedIds.containsAll(requestedIds)` 卡住不在模板里的菜单）。

**实测结果（完整命令与输出见附录 C）：**

| 场景 | `sys_role_menu` 缺失 | `sys_template_menu` 缺失 | 期望 | 实际 |
|---|---|---|---|---|
| **基线**：真实 007 + 本方案 §7.1.1 的追加 SQL | `[]` | `[]` | 全绿 | ✅ 全绿 |
| 变异 1：删 `3310` 的模板授权（它是 `platform_only=0`） | `[]` | `['3310']` | 转红 | ✅ 转红 |
| 变异 2：删 `3310/3320` 的角色授权 | `['3310','3320']` | `[]` | 转红 | ✅ 转红 |
| 变异 3：把 `3320` 改成 `platform_only=0` 却不补模板授权 | `[]` | `['3320']` | 转红 | ✅ 转红 |

> 三个变异覆盖了规则的三个分支（模板缺 / 角色缺 / 由 `platform_only` 决定的期望切换）⇒ 这条门禁**是"咬过人"的**，不是装饰。
> 另必须保留原有的**自检** `assertThat(menuIds).isNotEmpty()`（落地后在 `IotMaintenanceAdminGateTest.java:120-123`，实施前是 `:77-78`），否则正则一改错就退化成"0 违规 = 没跑到"（旧仓教训八）。

**（3′）补充断言：K2「防孤儿补授」本身也要门禁（第二轮实施复核新增）**

上面那条断言只证明「模块目录被授给了**某些人**」，**不证明授给了所有拥有其子菜单的角色/模板** ——
实施复核实测：把 §7.1.1 第 3 步那 4 条 `INSERT IGNORE` 全部删掉，真实 JUnit **仍然全绿**（记为变异 M5）⇒ K2 没有门禁覆盖。
故新增 `IotMaintenanceAdminGateTest#orphanGuardMustBeGrantedForEveryModule`：
用 `ORPHAN_GUARD` 正则解析出每条「防孤儿」语句的 `(表, 父 id, 子清单)`，
断言 `{sys_role_menu, sys_template_menu} × {3310, 3320}` 四条**都存在且子清单与 §4.2 归属表一致**，并保留 `isNotEmpty()` 自检。

| 场景 | 期望 | 实测 |
|---|---|---|
| 基线 | 全绿 | ✅ `Tests run: 3, Failures: 0` |
| 变异 M5：删掉全部 4 条防孤儿补授 | 转红 | ✅ 转红（自检 `isNotEmpty` 触发） |
| 变异 M6：只删 `sys_template_menu` 的 `3320` 那条 | 转红 | ✅ 转红：`["sys_template_menu:3320（期望子清单 3004,3009，实际 null）"]` |


##### （4）五种处理办法对比（**已按实测更正**）

| 办法 | 做法 | 评价 |
|---|---|---|
| **(a) 推荐：修作用域 + `platform_only` 分流** | （3）的 `MENU_INSERT` 正则 + 语句级收集 + 两条期望 | 唯一能真正咬人的做法；基线通过、三个变异全转红（**已实测**） |
| (b) 只把 `startsWith("32")` 改成 `\|\| startsWith("33")` | 1 行 | ❌ **不咬人**（实测 `MISSING=[]`，因为模板侧 `granted` 被污染）。**本文早前说它会"假红"，那是错的** |
| (c) 只修作用域、不改规则 | 语句级收集 + 仍要求两张表 | ❌ 会在**正确数据**上报红（`320014/320015`），把门禁变成噪声 |
| (d) 把新目录放 `32xx`（3210/3220） | 靠"现有规则自动覆盖" | ❌ 前提本身不成立（现有规则失效）；且会**污染"32xx = 物联网"的约定**（`007-iot-data.sql:154-155` 明文写了 32xx 是 IoT 段） |
| (e) 不扩门禁 | 什么都不做 | ❌ K2 的盲区继续没有门禁覆盖 |

> **顺带提醒**：`IotPermissionCodeGateTest` 只校验 `auth_code`，`check-iot-sql-equivalence.sh` 只保证两份脚本一致 ⇒ **"菜单建出来但没人看得见"这个盲区，只能靠**（a）**修好的门禁 + §7.1.2 的孤儿自检配合**。
> **修复范围提示（R8）**：`IotMaintenanceAdminGateTest` 位于 `ypbin-service/ypbin-iot`，而 `3310/3320` 是**平台级**菜单。放在这里虽然能用，但语义上更合适的做法是把"菜单授权表覆盖"这条检查**提到 `ypbin-system` 或独立成一个 SQL 级检查脚本**，让 IoT 模块的测试不必为平台菜单负责。本方案只提示，不在范围里。

**本次改动会碰到的门禁，逐个过一遍（其余不受影响）：**

| 门禁 | 是否受影响 | 处置 |
|---|---|---|
| `tools/check-iot-sql-equivalence.sh` | **受影响** | 双写（§7.1.3）；文件名必须含 `-iot-` |
| `IotMaintenanceAdminGateTest#everyMenuIdMustBeGranted` | **受影响** | 按 (a) 修正（§7.1.4） |
| CI 的「starter 版本 == 最新 Release」步骤 | **不受本次改动影响，但实测会无端变红** | 该步骤用**未鉴权**的 `curl` 打 `api.github.com/.../releases/latest`（`REQ=$(grep -oP '(?<=<ypbin-starter.version>)[^<]+' pom.xml)` 与之比较）。**本次实测**：同一步骤在上一提交上 `success`，在只有文档改动的下一提交上因 `curl: (22) ... error: 403`（GitHub 对共享 runner IP 的限流）而**失败**，报错信息却是"依赖版本落后，请升级至最新 Release" ⇒ **假红 + 误导性报错**。处置建议：把该 `curl` 改成带 `Authorization` 头（`${{ secrets.GITHUB_TOKEN }}`）+ 失败时重试一次、并把"取不到 LATEST"与"版本真的落后"区分开报错。**与本文方案无关，但会干扰本方案的实施与验收，故记下** |
| `IotPermissionCodeGateTest` | 不受影响 | 本次**不新增任何 `auth_code`**（两个模块目录的 `auth_code` 为 `NULL`），它扫描的 `iot:*` 权限码集合不变 |
| `IotTenantIsolationIT` / `ItSchema` | **不受影响** | `ItSchema.ensure` 只执行 `deploy/sql/006-iot-schema.sql`（`it/ItSchema.java:22`、`:47-53`），**不加载 `007` 与任何迁移** ⇒ 菜单数据变更进不了真库 IT |
| `NacosTenantIgnoreConfigTest` / `DbDictProviderTest` | 不受影响 | 不涉及 `sys_menu` |
| 前端 CI `check-iot-i18n-keys.mjs` | **不受影响（但也没保护）** | 它只扫 `views/iot` 与 `api/iot` 的 `$t()`，**不扫 `sys_menu.title`** ⇒ 新模块 title 漏加语言包时它不会报，只能靠人工/§7.5 P2-6 |

### 7.2 id 规划规则

**现有 id 段（活库实测，`GROUP BY FLOOR(id/1000)`）：**

| 段 | 条数 | 实际范围 | 用途（观察所得） |
|---|---|---|---|
| 0-999 | 13 | 1, 101, 102, 103, 210, 220, 230, 240, 250, 260, 270, 280, 290 | 仪表盘 + 系统管理叶子页 |
| 2000-2999 | 6 | 2500, 2600, 2700, 2900, 2950, 2952 | 二级目录页 |
| 3000-3999 | 20 | **3001-3015, 3100, 3200-3204** | 顶级目录 + 埋点 + 授权 + **IoT（32xx）** |
| 4000-4999 | 2 | 4000, 4001 | 我的消息 / 接口文档 |
| 5000-5999 | 32 | 5000-5071 | **AI 段** |
| 各 `x0000+` | ... | 21001-295203, 320001-320302, 310001-310008 | 按钮（页面 id × 100 + 序号） |

**已确立的两条既有约定：**

1. **`32xx` = 物联网段**（`deploy/sql/007-iot-data.sql:154-155` 明文写"新父 id 3204 …… 当时在用的 32xx 只有 3200-3203 与 320001-320017"），且被 `IotMaintenanceAdminGateTest` 的 `startsWith("32")` **固化成门禁**。
2. **`50xx` = AI 段**（`deploy/sql/002-data.sql:321-324` 明文写"AI 菜单树（5000-5099 段）"）。

**本次新 id 的规划规则（建议固化）：**

> **`33xx` = 平台模块目录段（platform module catalogs）**，只放 `type=catalog` 且 `pid=0` 的模块目录；**不与 32xx（IoT）混用**。
> 分配：`3310 基础管理`、`3320 运维与监控`；保留 `3330/3340/3350…` 给未来模块（步长 10，给每个模块留 9 个兄弟位）。
> **注意 `33xx` 与 admin 上游未来菜单的冲突**：`ypbin-iot` 是 `ypbin-admin` 的 fork，上游若新增 `33xx` 会撞。
> 缓解：① 本方案把 `33xx` 的**约定写进 `007-iot-data.sql` 的注释**（该文件是 IoT 自己的、上游不会碰）；② 每次 `merge upstream/main` 后跑一次 §7.2 的占用查询；③ 顺带把 `32xx` 也纳入同一查询。

**活库 + 仓内双查结果（本次实测）：**

```sql
-- 活库：期望 0 行
SELECT id, pid, name FROM sys_menu WHERE id IN (3310, 3320);
SELECT id FROM sys_menu WHERE id BETWEEN 3300 AND 3399;
```
均为 **0 行**（详见附录 A.5）。仓内 SQL 侧：`grep -rn "3310\|3320" deploy/sql/` **0 命中**。

> **⚠️ 不要重编号任何既有菜单**：`sys_role_menu` / `sys_template_menu` 以 `menu_id` 为外键，改 id 会同时打断两张授权表并且**不会**被现有门禁发现（它们只按 id 断言，不校验 id 稳定性）。**只新增，不改号。**

### 7.3 前端改动

| # | 改动 | 文件 | 是否需重建产物 | 备注 |
|---|---|---|---|---|
| F-A | 布局切 `mixed-nav` | `apps/web-antd/src/preferences.ts`（`app.layout`） | **是** | §2.4；一行 |
| F-B | 偏好"布局版本号"强制归一（破 K1） | `packages/@core/preferences/src/preferences.ts` + `types.ts` | **是** | P0，§7.5；约 5-10 行 |
| F-C | 新增 2 个 i18n 键 | `apps/web-antd/src/locales/langs/{zh-CN,en-US}/page.json` | **是** | `page.admin.title` / `page.ops.title`；**不新增就会在顶栏显示原始 key** |
| F-C2 | （仅在产品确认时）改 2 个**值**：`page.ai.title` → 知识与 AI / Knowledge & AI；`page.dashboard.title` → 工作台 / Workspace | 同上两份 `page.json` | **是** | **只改值不改键**；两份语言包必须成对改。若产品接受沿用「AI 助手」「概览」，则本项不做（§4.4 铁律三） |
| F-D | 工作台升级为模块门户 | `apps/web-antd/src/views/dashboard/workspace/index.vue`（**已存在**） | **是** | §6；纯前端，无新接口（告警区块先显示"能力未上线"） |
| F-E | 打开 `sidebar.autoActivateChild` | `apps/web-antd/src/preferences.ts`（`sidebar.autoActivateChild`） | 是 | P1，可选；让点模块即进第一页 |
| F-F | 面包屑/搜索**无需改动** | — | — | 面包屑读 `route.matched`（`packages/effects/layouts/src/widgets/breadcrumb.vue:29-52`），层级变深会自动多一段；搜索读 `accessStore.accessMenus`（`user-dropdown.vue:347-352`），自动跟随新层级 |
| F-G | IoT 模块内部的页面改动 | 见旧文 §9.5 | 是 | **不在本文范围**，引用旧文 |

### 7.4 风险与回滚

| # | 风险 | 会不会发生 | 依据 / 缓解 |
|---|---|---|---|
| R1 | **权限码受影响** | **不会** | 权限码在 `sys_menu.auth_code`，reparent 只改 `pid`，不动 `auth_code`（§7.1.1 用 `UPDATE ... SET pid`）；`IotPermissionCodeGateTest` 可回归 |
| R2 | **书签 URL 断掉** | **不会** | 子菜单 `path` 不动（保持绝对路径 `/system/org`、`/iot/devices`）。另外 `generateRoutes` 在"首个子路由路径以 `/` 开头"时会**提前返回、不加 redirect**（`packages/effects/access/src/accessible.ts:142-149`），所以也不会凭空生成怪 redirect |
| R3 | **面包屑变三层** | **会，且是预期变化** | `breadcrumb.vue:29-52` 遍历 `route.matched`；层级变深 ⇒ 面包屑变 `基础管理 / 组织与权限 / 组织管理 / 用户管理`。这是**改进**，但要在验收清单里写明，避免被当 bug |
| R4 | **菜单搜索** | **不受影响** | 搜索遍历 `accessMenus`，层级无关（`user-dropdown.vue:347-352` + `global-search/search-panel.vue:216`） |
| R5 | **移动端** | **形态不变** | 移动端强制 `sidebar-nav`（`use-layout.ts:8-10`）⇒ 顶部大模块**不出现**；且**不会有白屏/错乱**。要在验收预期里写明 |
| R6 | **i18n 缺键显示原始 key** | **会，若漏加 F-C** | 顶栏文案 = `$t(sys_menu.title)`；缺键时 vben 渲染原始 key（旧仓已发生过"95 处文案渲染成 key"的事故） |
| R7 | **孤儿菜单（K2）** | **会，若不跑 §7.1.1 第 3 步** | 机制见 `SysMenuServiceImpl.java:74` + `:340-354`；自检见 §7.1.2 |
| R8 | **缓存偏好覆盖默认值（K1）** | **会** | `preferences.ts:139-150`；缓解 = F-B |
| R9 | **门禁假绿（K3）** | **会，若不扩门禁** | `IotMaintenanceAdminGateTest.java:94` 的 `startsWith("32")` |
| R10 | **上游 admin 未来占用 33xx** | 可能 | §7.2 的缓解三条 |
| R11 | **`/dashboard` 与 `/dashboard/workspace` 的关系** | 低 | `defaultHomePath: '/dashboard'`（`config.ts:20`）是**前端常量**、且后端 `homePath` 从不赋值（`UserInfoResp.homePath` 无 setter 调用）⇒ 落地页不受菜单树影响 |

**回滚方案（三步，可整体原子回滚）：**

1. **前端**：把 `app.layout` 改回 `'sidebar-nav'`（或删掉该行）+ 重建产物 + `docker compose up -d --no-deps ypbin-iot-ui`。**这一条单独就能把形态退回去**，因为菜单层级只影响左侧菜单的嵌套，`sidebar-nav` 下同样是可用的（只是左边会多一层"模块"）。
2. **数据**：执行回滚脚本 —— `UPDATE sys_menu SET pid = 0 WHERE id IN (3001,3002,3003,3005,3007,2600,3008,4001,3004,3009);` + `DELETE FROM sys_role_menu WHERE menu_id IN (3310,3320);` + `DELETE FROM sys_template_menu WHERE menu_id = 3310;` + `DELETE FROM sys_menu WHERE id IN (3310,3320);`。顺序不能反（先删授权再删菜单，或反之都可，但**先解 reparent 再删目录**最安全）。
3. **验证**：跑 §7.1.2 的两条孤儿查询（期望 0 行）+ 附录 A.4 的顶级菜单查询（期望回到 13 行）。

> ⚠️ 回滚脚本必须与迁移脚本**同批次提交**（旧仓既有做法：`007` 段落后紧跟回滚说明，且上一轮有独立的 `ROLLBACK-*.sh`）。**回滚脚本里的 `git checkout --` 不要与"改文件"放在同一个批处理**（旧仓教训三十三第 ④ 条）。

### 7.5 优先级（P0 / P1 / P2）

#### P0 —— 只看效果（**零菜单变更，1-2 小时**）

| 步骤 | 动作 | 验证 |
|---|---|---|
| P0-1 | 右上角「偏好设置 → 布局 → 混合垂直」手动切一次 | 顶栏出现 13 个顶级菜单（可能折叠出「更多」），左侧变成当前项的子菜单 → **形态证实**，不写代码 |
| P0-2 | （可选）`apps/web-antd/src/preferences.ts` 加 `app: { layout: 'mixed-nav' }`，构建 + 部署 | 新用户/清缓存用户默认就是新形态 |
| P0-3 | 破 K1：加"布局版本号"强制归一（F-B） | 老用户刷新后也是新形态 |

> **P0 的产出是"决策依据"，不是终态**：13 个顶级项不好看，正好用来向产品说明"为什么必须先归并模块"。

#### P1 —— 真正的模块化（**需要动数据**，1-2 天）

1. 写 `deploy/sql/migration/<日期>-iot-platform-module-menu.sql`（**文件名必须含 `-iot-`**，否则会被等价性脚本静默排除）+ 把同一批语句**逐字**追加到 `007-iot-data.sql` 末尾（§7.1.1 全部 SQL），然后过 `tools/check-iot-sql-equivalence.sh`。
2. **先修门禁的作用域、再扩到 `33xx`**（两件事必须同时做，§7.1.4）：
   - 把 `sql.split("INSERT INTO " + table)` 换成**按语句归属**的收集（剥 `--` 注释 → 按 `;` 切语句 → 只在该语句确实是 `INSERT [IGNORE] INTO <本表>` 时取 `id IN (...)`）；
   - 把期望改成 `MENU_INSERT` 正则捕获的 **`platform_only` 感知**规则（一律要 `sys_role_menu`；`platform_only=0` 时才要 `sys_template_menu`）；
   - 保留 `assertThat(menuIds).isNotEmpty()` 自检；
   - **重做 3 个变异**（模板缺 / 角色缺 / `platform_only` 从 1 改 0 而不补模板授权）⇒ 三个都必须转红，基线必须全绿（附录 A.10 有可复现脚本与预期输出）。
   - ⚠️ 只改 `startsWith("32")` 为 `\|\| startsWith("33")` 是**无效**的（实测不咬人）。
3. 加 `page.admin.title` / `page.ops.title`（zh-CN + en-US）。
4. **迁移前**先存孤儿基线（§7.1.2 的角色侧查询，实测基线是 5 行）→ 执行迁移 → **再跑同一查询，必须与基线一致（不新增）**，另两条（模板侧、`platform_only` 嵌套）期望 0 行 → 用**平台管理员 + 一个普通租户用户**各登一次，核对模块数与菜单项。
5. 前端 F-A/F-B 上线；验收清单包含 §7.4 的 R3（面包屑变三层）与 R5（移动端不变）。

#### P2 —— 体验打磨（可选）

1. 打开 `sidebar.autoActivateChild`（F-E）。
2. 工作台升级为模块门户（F-D），接入健康总览/维护窗口真实数据；告警区块等后端能力上线后再接。
3. `5000` 下加一层分组（对话与知识 / 模型与提示词 / 治理）——**这是新增数据**，需再走一遍双写 + 门禁。
4. 「知识与 AI」与「物联网」的 `sort` 调序（若产品要求）。
5. 把 `/message` 移到右上角铃铛（vben 原生 `#notification` 插槽）。
6. 给 i18n 门禁补一条：**扫描 SQL 里的 `sys_menu.title` 键在两份语言包里都存在**（现有 `scripts/check-iot-i18n-keys.mjs` 只扫 `views/iot` 与 `api/iot`，**管不到菜单 title** —— 这正是"顶栏显示原始 key"这类事故的盲区）。
7. **"全部模块"入口（触发条件，不是现在做）**：主流做法在服务清单变长时会收进"全部服务/产品与服务/云目录"面板（§3.4），而 **vben 5 没有原生的"全部模块"页面**。**判据：当顶级模块数 ≥ 7 个、或顶栏在 1280px 宽度下开始折叠出「更多」时**，就必须补一个"全部模块"入口（可先做在偏好抽屉/全局搜索里，再做独立页面）。当前 4-5 个模块**不需要**。

---

## 8. 独立复核结论（R6）

本节由**独立复核子代理**执行（不同上下文、只读、自行跑命令、不继承本文结论），**复核对象是两个仓的两个分支的最终状态**，复核项在派发时固定为下面 8 条 + "额外找作者没提的问题"。

### 8.1 逐项结论

| # | 复核项 | 结论 | 复核者的关键取证 |
|---|---|---|---|
| ① | `mixed-nav` = 顶部一级 + 左侧二级，与仓内类型/实现一致 | **PASS**（并纠正 1 处论据） | `app.d.ts:1-8` 7 种；`use-preferences.ts:113-115`；`use-mixed-menu.ts:24-29`（`needSplit` 确含 `navigation.split && isMixedNav`）、`:43-53`（headerMenus 清空 children）、`:58-60`；`basic/layout.vue:131-136`、`:367-377`、`:390-403`；`vben-layout.vue:597` 无第三列。**纠正**：本文原来说 `header-nav` 是"顶部一份 + 左侧一份"——**错**，`header-nav` 下 `sidebarEnableState = !isHeaderNav && ...`（`:165-168`）、`<LayoutSidebar v-if="sidebarEnableState">`（`:565`）⇒ **侧边栏整体不渲染**。已改（§2.2） |
| ② | 当前 = `sidebar-nav`、改法 = 加 `app.layout` + 重建；缓存优先（K1） | **PASS** | `config.ts:28`/`:20`/`:76`/`:88`；`apps/web-antd/src/preferences.ts` 无 layout/navigation 覆盖；`.env*` 无布局变量；**K1 独立复核成立**：`preferences.ts:132` merge、`:139-150` 缓存优先、`:356-358` 读、`:437-440` 写整份 state；旁证 `:150-158` 已为 `accessMode` 单开"强制用 overrides"先例 ⇒ P0 的 F-B 可行 |
| ③ | `3310/3320` 未占用 | **PASS** | 活库 `id IN (3310,3320)` **0 行**、`BETWEEN 3205 AND 3399` **0 行**；仓内 grep **0 命中**；§7.2 的 id 段表与活库 `GROUP BY FLOOR(id/1000)` **逐格一致** |
| ④ | 原型离线可打开、零外链 | **PASS**（含增值） | `grep -c http`=0；仅 1 个无 `src` 的内联 `<script>`；无 link/img/iframe/xmlns/url()/@import/fetch/XHR/localStorage；`node --check` 通过。**独立统计**：5 模块、admin16/ai7/iot16/ops7=**46**、iot 分组 4/3/4/3/2、标记 复用37/新增3/缺接口6；HTML 47 条 route 全部在 README §4 登记（漏登记 0），README 唯一多出的 `#/dashboard` 在 HTML 中是**别名**（虚登记 0）；6 个实页吻合 |
| ⑤ | "复用现有页面"与仓内实际一致 | **PASS** | **35/35 文件存在**（含本文点名的全部路径）；`002-data.sql:312-315` 的 `system:app:*` 4 个权限码 ✓；**IoT 标记 16/16 与旧文 §4.1 逐条一致**。唯一口径差异：旧文把「接入向导」画在独立的「🚀 起步」组，原型并入「① 接入配置」 |
| ⑥ | 活库 13 个顶级菜单与 §4.2 逐行一致 | **PASS** | 复核者实跑 `pid=0` 查询得 13 行，与 §4.2 的 id/name/type/platform_only/path **逐行一致**（含 `2600=menu`、`4001=embedded`）；附录 A.4 的输出与其实跑**逐字节相同** |
| ⑦ | 两个机制性断言（K2 孤儿 / K3 门禁） | **K2 = PASS；K3 = FAIL（已修）** | K2 成立（复核者独立读 `buildRoutes`/`buildRouteTree`/`applyTenantMenuFilter`/`selectByUserId`/`ROOT_PARENT_ID` 后确认）。**K3 判 FAIL** —— 见 §8.2 |
| ⑧ | 外部引用给得出、点得开 | **PASS** | 6 条 URL 全 200；抽查 5 条**引语逐字命中**（腾讯云/AWS/企业微信/Azure/阿里云）；自我声明诚实（"通式"标我方推断、飞书标未核实，未把二手冒充一手） |

### 8.2 K3 的 FAIL 与整改（本次最重要的修正）

**复核者用真实 JDK 复刻门禁逻辑后实测**：

1. **原门禁对 `sys_template_menu` 的检查是假绿** —— `sql.split("INSERT INTO " + table)` 的 block 跨语句串味，两张表实测 `granted` 完全相同；`320014` 从未进任何 `sys_template_menu` INSERT 却被判为已授权。**变异：删掉 `3310` 的模板授权，门禁不转红。**
2. **本文早前的判断"天真扩到 `startsWith("33")` 会产生假红"是错的** —— 实测 `MISSING=[]`。问题不是假红，而是**根本不咬人**。
3. **即使修好作用域，"两张表都必须覆盖"这条规则本身也错** —— 会在正确数据上误报 `320014/320015`（`platform_only=1`，按 `007:90-97` 的约定故意不进模板）。

**整改（已写入 §7.1.4 + 附录 A.10，并由本文作者独立复现）**：

| 整改项 | 内容 |
|---|---|
| 收集方式 | 改为**按语句归属**（剥 `--` 注释 → 按 `;` 切语句 → 只在该语句确实是 `INSERT [IGNORE] INTO <本表>` 时取 `id IN (...)`） |
| 期望规则 | 一律要 `sys_role_menu`；**仅当 `platform_only=0`** 时才要 `sys_template_menu`（正则升级为 `MENU_INSERT`，同时捕获 `id/pid/name/type/platform_only`） |
| 变异验证 | **3 个变异全部实测转红**（模板缺 / 角色缺 / 由 `platform_only` 决定的期望切换），基线实测全绿 |
| 保留自检 | 必须保留 `assertThat(menuIds).isNotEmpty()`，防"0 违规 = 没跑到" |
| 撤回 | 本文早前"天真扩法会假红"的结论与"沿用既有验证结构"的建议**均已撤回** |

### 8.3 复核者额外发现、本文已修的问题

| # | 问题 | 严重度 | 处置 |
|---|---|---|---|
| 1 | **`4001 接口文档` 归属自相矛盾**：§4.1「运维与监控」列了它，而 §4.2/§7.1.1 SQL 归「基础管理」，§5 mermaid 同时挂了两处，原型只放了 ops | **高** | 统一为 **基础管理 / 授权与开发**（理由：`platform_only=0`，放进 `platform_only=1` 的 3320 会在租户侧消失）；§4.1、§5 mermaid、原型 HTML 与原型 README **四处已全部改为同一归属**（`#/admin/api-docs`）；「运维与监控」由 3 组收为 2 组 |
| 2 | 门禁模板侧假绿（= §8.2） | **高** | 已整段重写 §7.1.4 |
| 3 | 变异验证计划不成立（= §8.2） | **高** | 已重写并给出实测通过的 3 个变异 |
| 4 | **§7.1.2"期望 0 行"实测不为 0**：活库角色侧**已有 5 条**超管孤儿（`role 1`：`230/290→3002`、`5003/5050→5000`、`270004→2700`） | 中高 | 改为"**不新增**孤儿"口径，列出迁移前基线 5 行 + 模板侧 0 行，并说明这 5 行是 `002-data.sql` 种子方式造成的既有现象、对超管无可见影响（附录 A.10 有实测输出） |
| 5 | `platform_only` 过滤实际在 `SysMenuServiceImpl.java:**74**`，本文多处写 `:73` | 低 | 已全文更正 |
| 6 | `header-nav` 形态描述错（= ①） | 低 | 已更正（§2.2、§3.1） |
| 7 | 附录 A.2 的自证命令字符串与源码不符（实际注释是"用户缓存的设置优先"） | 低 | 已更正 |
| 8 | **K2 漏了"租户模板"这一层**：非平台用户还要过 `applyTenantMenuFilter`（`allowedIds ← sys_template_menu`） | 中低 | §2.5 增补"谁受 K2 影响"行，说明两道过滤 + 指出它只补"已在用户菜单集合里"的祖先 |
| 9 | "HTTP 200 ≠ 文件存在"（nginx SPA 回退） | 中·方法学 | 已在 §2.4 明确：只对**实测过字节数一致**的 `index.html` 声明可访问；`platform-nav.html` 写的是"上线到同一位置**即可**"（未声称已部署） |

### 8.4 复核者主动确认"做得对"的部分

- K2 的机制定位与"防孤儿补授 SQL"是全篇最有价值的判断；
- §3.4 **主动推翻用户前提**且 3 条反向证据被逐一核实为真；
- K1 行号精确且有 `accessMode` 先例；
- 附录 A 基本可直接复现（A.4/A.5 与实跑**逐字节一致**）；
- `check-iot-sql-equivalence.sh` 的三条特征（只收 `*-iot-*`、归一化方式、空跑即报错）**全部属实**；
- `ItSchema` 确实只加载 `006-iot-schema.sql`（`ItSchema.java:53`）；
- `check-iot-i18n-keys.mjs:16` 确实只扫 `views/iot`+`api/iot`；
- 原型工程质量高（真零外链、46 项与 README 表格逐格吻合、route 双向无缺口）；
- 顺手发现的真实漂移属实（`docs/DEPLOY-UI.md:32-33` 写 19001，生产 `.env` = 19000、`docker ps` = `19000->80`）；
- `3310/3320` 与 `3210-3250` 空闲的结论可靠。

### 8.5 复核者的判定与本文的整改状态

> **复核判定：FAIL（有条件）** —— "方向与迁移主干站得住，但有两处实质缺陷能否决'可直接实施'"：门禁假绿 + `4001` 归属矛盾。

**本文的整改状态：§8.2 的 3 项 + §8.3 的 9 项已全部修正并落到文档与原型；K3 的修正版门禁基线通过、3 个变异转红（附录 A.10 有完整命令与输出）。**

### 8.6 第二轮独立复核（**2026-09-26，针对本修正稿**）

> 第一轮复核（§8.1–§8.5）后本文自认"未再送复核"（原记为 U10）。**实施前已按 R6 补做第二轮独立复核**：复核者为**独立子代理**（不同上下文、只读、自行跑命令、不继承作者结论），两仓 `git status` 全程为空，生产库只读，用**本地一次性 MySQL 8.4.11 容器**（与生产同版本同 `sql_mode`）做真 SQL 校验，并用**真实 JUnit** 跑了原门禁的变异（此前本文只做过逻辑复刻）。

**总判定：PASS（有条件）—— 可按本方案进入实施。** 六个断言组（id 占用 / 迁移 SQL 语法语义 / K2 机制 / K3 假绿与修正版 / K1 缓存优先 / `-iot-` 命名）**全部 PASS**。

**复核者独立复现的关键结论（与本文一致）：**

| 复核项 | 复核者实测 |
|---|---|
| K3 假绿 | 用**真实 JUnit 字节码**跑四个输入（基线 / 删 `3310` 模板授权 / 删 `3310+3320` 角色授权 / `3320` 改 `platform_only=0`）**全部"门禁通过"** ⇒ 原门禁对本次改动**完全无感** |
| 原门禁取数污染 | 两表 `granted` 均 33 条、**集合完全相同**；`320014` 从未出现在任何 `sys_template_menu` 语句里却被判为已授权 |
| **新的独立假绿路径（本文未提）** | 删掉 `3200` 的**全部直接授权**后门禁仍全绿 —— 因为 `007:173` 的 `UPDATE sys_menu SET pid = 3204 WHERE id IN (3200,…)` 被 `GRANT_IN` 当成"授权" |
| 修正版门禁 | 按描述**独立实现**后：基线 `[]/[]` 全绿；变异 1 `template=['3310']`、变异 2 `role=['3310','3320']`、变异 3 `template=['3320']` **全部转红** |
| 只修作用域不改规则 | 在真实数据上误报 `['320014','320015']` ⇒ "修作用域 + 改规则"必须同批做（与本文一致） |
| 迁移 SQL 真执行 | 与生产同版本 MySQL 加载 `001+002+003+004+006+007+§7.1.1` **零错误**；迁移后 `pid=0` 恰为 `1/3310/3204/5000/3320` 五行；10 个 reparent 与 §4.2 逐行一致 |
| 防孤儿 SQL 端到端 | 复核者**合成**自定义角色/模板后实测：`role 2→(3001,3310)`、`role 3→(3004,3320)`、`template 2→(3001,3310)` **全部补上**，角色侧孤儿仍为原 5 行、模板侧 0 行 |
| 回滚脚本 | 执行后 `pid=0` 回到 13 行、`3310/3320` 菜单行与授权行归零 ⇒ 回滚完整可用 |
| `-iot-` 命名 | 错名 + 不追加 007 ⇒ **恒绿**（危险形态）；错名 + 追加 007 ⇒ **转红**（与本文"恒绿"的旧表述不同，已按 §7.1.3 更正） |
| K1 | `preferences.ts:144-148` 缓存优先成立；`accessMode` 先例在 `:150-158`；`apps/web-antd/src/preferences.ts` 与 `.env*` 均无布局覆盖 |

**按复核意见所做的修正（本轮已落地）：**

| # | 修正 | 位置 |
|---|---|---|
| 1 | `-iot-` 命名的后果**拆成两种情况**写清（恒绿只发生在"不追加 007"时） | §7.1.3 |
| 2 | 补 `3320` 的**模板侧防孤儿** `INSERT IGNORE`，使"模板侧孤儿恒为 0 行"成为结构性保证 | §7.1.1 第 3 步 |
| 3 | `GRANT_IN` **锚定词边界** `(?<![A-Za-z0-9_])id\s+IN\s*\(`，避免 `menu_id IN (…)` 泄漏 | §7.1.4(3) |
| 4 | 补记 `MENU_INSERT` 全局扫描顺带修掉的两个 latent 缺陷（同行多值漏扫、注释 INSERT 误扫） | §7.1.4(3) |
| 5 | K2 边界表述统一为「**只对非超管用户成立**（无论其是否平台用户）」（§0.2 早前写法易被误读为"平台用户免疫"） | §0.2 |
| 6 | 行号校正：`SysMenuServiceImpl.java:72` → **`:73`**；`SysMenuMapper.java:27-46` → **`:32-45`** | §2.5 |
| 7 | 记下 `-Dsurefire.failIfNoSpecifiedTests=false` 才是正确属性名（`-DfailIfNoSpecifiedTests=false` 无效，会让 `-am` 上游模块 FAILURE） | §8.6（本表） |

**复核者未能核实的事项**：修正版门禁的**真实 JUnit** 运行（复核时修正版尚未落盘，其用 python 按描述独立复刻）——本方案实施时在 `ypbin-iot` 仓**已落盘并做了真实 JUnit 变异验证**（见实施 PR 回执）；`mixed-nav` 在真实数据下的渲染溢出、原型页真实点击仍需浏览器，记为 U1/U4/U12。

### 8.7 实施轮独立复核（**2026-09-26，针对 A/B 两个 PR 的最终状态**）

方案获批实施后，A（前端 `mixed-nav` + K1 归一）与 B（迁移 SQL + 门禁修正）各开一个 PR，并**再派一名独立子代理**（不同上下文、自行跑命令、不继承实施者结论）做实施验收。两仓 `git status` 全程干净、变异后 `007-iot-data.sql` 的 sha256 始终等于 HEAD、生产库只读、未 commit/push。

**总判定：B = PASS；A = FAIL（有条件）→ 已按复核建议修复（A 点 ① 与 B 点 ② 均已落地，修后复验结论见 PR 评论）。**

| 复核项 | 结论 | 关键证据（复核者自跑） |
|---|---|---|
| A 代码正确性 | **FAIL → 已修** | 功能全对（老用户→`mixed-nav`、用户自行改回被尊重、只清 `app.layout`、主题/语言/业务键不动、JSON 损坏不抛异常），**但** `window.localStorage` 的**取值本身**在沙箱 iframe / cookie 全禁下抛 `SecurityError`，被留在 `try` 之外 ⇒ 异常直穿 `main.ts` 的 `initApplication()`，应用**白屏**（本 PR 引入的回归；vben 的 `StorageManager#createDefaultDriver` 正是 try/catch 兜住此情形）。**已按复核建议把取值判断移入 `try`，修后 10/10 场景通过** |
| A 产物与部署 | PASS | 线上 `index.html` 200 且与 CI artifact **398/398 文件 sha256 全等**；`/api/system/menu/all` HTTP 200（body `code:401`，符合全局 HTTP200 约定）；dist 含 `mixed-nav`/`layout-migration-version`/`基础管理`/`运维与监控` |
| A i18n | PASS | 两份语言包均有 `page.admin.title`/`page.ops.title`；`page.dashboard.title`/`page.ai.title` **只改值不改键**；`node scripts/check-iot-i18n-keys.mjs` exit 0 |
| B 双写/命名/等价 | PASS | `007-iot-data.sql:175-232` 与迁移文件**逐字节 sha256 相同**；等价脚本 `OK`；"命名不含 `-iot-` 被静默排除"已用 `/tmp` 最小目录独立复现 |
| B SQL 语义 | PASS | 真实 MySQL **8.4.11**（同生产版本 + 严格模式）执行生产备份副本零错误；`pid=0` 恰 5 行；8+2 reparent 与 §4.2 逐行一致；合成自定义角色/模板验证防孤儿真的补上父目录，删掉 4 条 `INSERT IGNORE` 后一条不补 |
| B 门禁咬人 | PASS（含残留缺口，已补） | 真实 JUnit：基线绿；M1/M2/M3/M4 全红（M4 证明**旧门禁漏报 `3200`**）；**但 M5「删掉 4 条防孤儿补授」仍全绿** ⇒ K2 无门禁覆盖（见 §7.1.4(3′)，本轮已补 M6 断言） |
| B 本机测试 | PASS | iot 模块 `Tests run: 212, Failures: 0, Errors: 0`；架构测试 **41/0** |
| B 活库 | PASS（证据强度受限） | 生产三表 == 备份 + 迁移脚本（逐行逐列，除时间戳）；超管 47→49 节点**零丢失**、顶层顺序 = 工作台/基础管理/物联网/知识与 AI/运维与监控；**非超管 3 个用户的授权本来就是 0 行 ⇒「没变少」这条证据是空的**（已如实标注） |
| B 回滚 | PASS | 副本库执行回滚后三表与迁移前**逐行完全一致**（152/102/81 行） |
| B 文档一致性 | PASS（1 处行号陈旧，已修） | §7.1.3 两情形、§7.1.4 锚定与 `3320` 模板侧补授均与代码一致；行号 `:62-101`/`:77-78` 已更新为落地后的实际行号 |

**复核者额外发现、本文已处置：** ① A 的启动崩溃回归（已修）；② K2 防孤儿无门禁覆盖（已补 `orphanGuardMustBeGrantedForEveryModule` + M5/M6 变异）；③ `MENU_INSERT` 靠"位置形状"识别菜单、段过滤只认 `32xx/33xx`、`code.split(";")` 遇含分号字面量会切坏 —— 均为**已知潜在边界**，当前数据无触发，记为后续加固项；④ `docs/DEPLOY-UI.md` 的 19001 端口漂移仍在（既有问题，本轮不改，见 §8.4）。

**复核者未核实：** `mixed-nav` 的真实浏览器渲染/顶栏溢出/点击（需浏览器，本机禁止全量构建）——仍需用户在浏览器确认。


## 9. 未核实与本次不做的（R1 / R8）

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| **U1** | 13 个 / 4-5 个顶级菜单在具体视口下**是否触发**「更多」折叠、折叠几个 | **未核实** | 代码层面确认机制存在（`packages/@core/ui-kit/menu-ui/src/components/menu.vue:166-177`、`:353-364`），但本机不跑前端全量构建，**未渲染实测**。结论不依赖它（本方案无论如何都归并到 4-5 项） |
| **U2** | 阿里云/腾讯云控制台的官方文档是否**明文描述**"顶部一级 + 左侧二级"的结构 | **已核实（§3.4）** | 腾讯云**明文有**（唯一一家）；阿里云**没有**该术语，其产品清单入口在左侧导航栏、顶栏只放全局能力 ⇒ 需求前提被部分推翻 |
| **U3** | AWS / Azure / 钉钉 / 企业微信 / 飞书 管理后台的导航结构官方描述 | **已核实 4 家 / 未核实 1 家（§3.4）** | AWS（Services 菜单 + All services + 按类型分组）、Azure（portal menu flyout/docked + page header + service menu）、钉钉（左侧功能导航栏）、企业微信（官方记载"顶部导航改到左侧边栏"）均为一手；**飞书未核实**（官方帮助中心正文前端渲染，抓回的 HTML 无正文，已探测 `/hc/api/*` 无果，按 R1 不用二手补齐） |
| **U8** | "模块超出顶栏宽度时该如何处理"是否有官方通式 | **未核实（官方无此描述）** | 四家官方文档均未描述；§3.4 的"通式"已明确标注为**我方推断**。**也没有任何官方文档使用「More」术语** |
| **U4** | `mixed-nav` 在**本部署真实数据**（13 项 / 4-5 项）下的渲染截图 | **未核实** | 需要前端构建 + 浏览器；P0-1 就是为了拿到这个证据 |
| **U5** | xxl-job 控制台能否被 iframe/免登集成 | **未核实** | `deploy/sql/005-xxl-job.sql` 只证明它是独立库与独立控制台；**没有**任何集成代码 |
| **U6** | 上游 `ypbin-admin` 是否已有 `33xx` 段的规划 | **未核实** | 只查了本仓与活库；上游未来占用是**推测的风险**，不是既成事实（§7.2 给了缓解） |
| **U7** | 「知识与 AI」模块名是否与产品口径一致 | **已按用户当次指令落地改值（实施轮）** | 实测 `page.ai.title` 的现值曾是 **zh「AI 助手」/ en「AI Assistant」**。**实施轮**按用户明确给出的模块划分（工作台 / 基础管理 / **知识与 AI** / 物联网 / 运维与监控）把值改为 **「知识与 AI」/「Knowledge & AI」**（只改值不改键，两份语言包同时改）⇒ 一键可回退。仍保留：库内**没有**独立的"AI 账号"菜单，未硬造"账号与配额"页面 |
| **U9** | `page.dashboard.title` 现值是 **「概览」**（en: Dashboard），与"工作台"不一致 | **已按用户当次指令落地改值（实施轮）** | 实施轮把值改为 **「工作台」/「Workspace」**（只改值不改键），与 §4.1 的模块命名一致；一键可回退 |
| **U10** | **修正稿是否已通过独立验收** | **已解决（2026-09-26 第二轮独立复核 PASS（有条件））** | 第二轮复核（独立子代理、只读、自行跑命令）六个断言组全部 PASS，并用**真实 JUnit** 复现了 K3 假绿、用**真实 MySQL 8.4** 执行了迁移 SQL 与回滚脚本。结论与整改见 §8.6 |
| **U11** | 修正版门禁**真实 JUnit 行为** | **已解决（实施时落盘并做真实 JUnit 变异）** | 第二轮复核时修正版尚未落盘，其用独立复刻代替；**实施 PR 已把修正版落成代码并跑真实 JUnit + 3 个变异**（基线绿、3 变异红，输出见实施回执） |
| **U12** | `platform-nav.html` 的**实际渲染与点击** | **未核实** | 本次只做了静态检查（零外链、JS 语法、路由/条目与 README 双向一致）。**没有在浏览器里真正渲染、点击、切模块**（本机不跑前端全量构建，也无浏览器验证步骤）⇒ 视觉、布局宽度、交互手感均未核实。建议打开文件肉眼过一遍 |
| **U13** | 生产环境**是否存在真实租户用户 / 自定义角色** | **未核实** | 本次只按"角色/模板全表"跑了孤儿普查（无过滤，是全量），但**没有枚举生产上实际有多少租户、多少自定义角色** ⇒ "K2 会影响多少真实用户"的规模未量化。迁移前建议先查一次租户与角色清单 |

**本次明确不做（R8，避免范围蔓延）：** 不改生产库（全程只读 `SELECT`）、不改业务代码（**连方案里的迁移 SQL 与门禁修正都没有落地成文件**，只写在 §7 与 §8.2）、不动 IoT 模块内部（引旧文）、不合并任何 PR、不做 xxl-job 集成、不做移动端另套设计、不改 `docs/ux-mock/index.html` 的既有内容。**两个 PR 全程只含文档与静态原型**（ypbin-iot: 2 文件；ypbin-iot-ui: 2 文件），两仓 CI 全绿。

> **上面这段描述的是"方案轮"的范围。** 第二轮复核判定 PASS（有条件）后，用户已批准**开始实施**（A 布局切换 → B 模块拆分），实施轮会在本仓与 `ypbin-iot-ui` 分别开 PR，把 §7.1.1 的迁移 SQL、§7.1.4 的修正版门禁、§7.3 的前端改动真正落地并在生产执行迁移。实施轮的范围与验收见对应 PR 与回执，不改变本文任何结论。

---

## 附录 A · 事实核验命令与输出

> 以下命令均为**只读**。生产库访问走 `~/.ssh/config` 的 `ypbin-prod` 别名（`BatchMode yes`，密钥认证）；命令里**不含任何口令**（MySQL 口令从容器自带环境变量取，只在远端进程内使用，不落盘、不回显）。

### A.1 vben 布局能力（仓内）

```bash
cd ypbin-iot-ui
sed -n '1,10p' packages/@core/base/typings/src/app.d.ts
# type LayoutType =
#   | 'full-content' | 'header-mixed-nav' | 'header-nav' | 'header-sidebar-nav'
#   | 'mixed-nav' | 'sidebar-mixed-nav' | 'sidebar-nav';     ← 7 种，行 1-8

grep -n "isMixedNav\|isHeaderMixedNav" packages/@core/preferences/src/use-preferences.ts | head
# 99: const isHeaderMixedNav = ... layout === 'header-mixed-nav'
# 113: const isMixedNav = ... layout === 'mixed-nav'

sed -n '24,29p' packages/effects/layouts/src/basic/menu/use-mixed-menu.ts
# const needSplit = computed(() => !isMobile.value &&
#   ((preferences.navigation.split && isMixedNav.value) || isHeaderMixedNav.value));
```

### A.2 当前默认布局（仓内）

```bash
grep -n "layout: 'sidebar-nav'" packages/@core/preferences/src/config.ts     # 28
grep -rn "layout" apps/web-antd/src/preferences.ts                          # 无输出（未覆盖）
grep -rn "VITE_.*LAYOUT\|LAYOUT" apps/web-antd/.env*                        # 无输出（无构建期变量）
grep -n "用户缓存的设置优先" packages/@core/preferences/src/preferences.ts   # 146（缓存优先；注释原文是"用户缓存的设置优先"）
```

### A.3 顶级菜单来源（仓内，后端 + 前端）

```bash
cd ypbin-iot
grep -n "return buildRouteTree(visible" ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/service/impl/SysMenuServiceImpl.java   # 79
grep -n "ROOT_PARENT_ID" ypbin-common/src/main/java/cn/ypbin/admin/common/constant/AdminConstants.java                                       # 39 = 0L
grep -n "menu/all" ypbin-auth/src/main/java/cn/ypbin/admin/auth/controller/AuthController.java                                               # 86
cd ../ypbin-iot-ui
grep -n "getAllMenusApi" apps/web-antd/src/router/access.ts                   # 32
grep -n "const name = (title" packages/utils/src/helpers/generate-menus.ts    # 50
```

### A.4 现在有哪些顶级菜单（**活库实测**）

```bash
ssh ypbin-prod 'bash -s' <<'REMOTE'
docker exec ypbin-mysql bash -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e \
 "SELECT id,pid,name,type,platform_only,path,component,title,sort,status FROM sys_menu WHERE pid=0 ORDER BY sort,id" ypbin_admin'
REMOTE
```

**实测输出（13 行，按 `sort` 再 `id`）：**

```
1	0	Dashboard	catalog	1	/dashboard	BasicLayout	page.dashboard.title	-1	1
3001	0	OrgManage	catalog	0	/system/org	BasicLayout	system.org.title	1	1
3002	0	AuthManage	catalog	0	/system/auth	BasicLayout	system.auth.title	2	1
3005	0	TenantManage	catalog	1	/system/tenant	BasicLayout	system.tenant.title	3	1
2600	0	SystemFile	menu	1	/system/file	/system/file/list	system.file.title	5	1
3007	0	MessageManage	catalog	0	/message-center	BasicLayout	system.messageCenter.title	6	1
3204	0	IotPlatform	catalog	0	/iot	BasicLayout	page.iot.title	6	1
3004	0	MonitorManage	catalog	1	/system/monitor	BasicLayout	system.monitor.title	7	1
3003	0	SysManage	catalog	1	/system/sys	BasicLayout	system.sys.title	8	1
3008	0	LicenseManage	catalog	1	/system/license-manage	BasicLayout	system.license.title	9	1
5000	0	AiManage	catalog	0	/ai	BasicLayout	page.ai.title	9	1
3009	0	TrackingManage	catalog	1	/tracking	BasicLayout	tracking.title	10	1
4001	0	ApiDoc	embedded	0	/api-doc	IFrameView	system.apiDoc.title	10	1
```

⇒ 与 §4.2 表格**逐行一致**（13 条）。另：`3204` 下挂 `3200/3201/3202/3203`（本次 IoT 归并的结果，`007-iot-data.sql:173`）。

### A.5 新 id 是否被占用（**活库 + 仓内双查**）

```bash
ssh ypbin-prod 'bash -s' <<'REMOTE'
docker exec ypbin-mysql bash -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e \
 "SELECT id,pid,name FROM sys_menu WHERE id IN (3310,3320)" ypbin_admin'
docker exec ypbin-mysql bash -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e \
 "SELECT id FROM sys_menu WHERE id BETWEEN 3300 AND 3399" ypbin_admin'
REMOTE
```

**实测输出：两条均无输出（0 行）** ⇒ `3310/3320` 未占用，且 `3300-3399` 全段空闲。

顺带把旧文 §4.1 建议的 **IoT 内部 5 个分组目录 id（`3210/3220/3230/3240/3250`）** 一起查了（一次查 `3205-3399` 全段）：

```bash
ssh ypbin-prod 'docker exec ypbin-mysql bash -c '"'"'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B \
 -e "SELECT id FROM sys_menu WHERE id BETWEEN 3205 AND 3399" ypbin_admin'"'"''
```

**实测输出：无输出（0 行）** ⇒ `3205-3399` 全段空闲，旧文的 `3210-3250` 与本文的 `3310/3320` **互不冲突、都可用**。

仓内侧：

```bash
cd ypbin-iot && grep -rn "3310\|3320" deploy/sql/ ; echo "exit=$?"
# 无输出，exit=1
```

### A.6 门禁现状（仓内）

```bash
cd ypbin-iot
grep -n 'id.startsWith' ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/config/IotMaintenanceAdminGateTest.java
# 94: if (id.startsWith("32") && !granted.contains(id)) {
head -12 tools/check-iot-sql-equivalence.sh   # 比较对象 = 006+007 ↔ migration/*-iot-*.sql
```

### A.7 原型离线自证（前端仓）

```bash
cd ypbin-iot-ui
grep -c 'http' docs/ux-mock/platform-nav.html            # 期望 0
grep -nE '<(script|link|img|iframe)' docs/ux-mock/platform-nav.html   # 只应有一个内联 <script>
```

**实测输出：见 §8 复核回执**（由复核者独立执行并回填）。

---

### A.8 "复用"断言的逐一核实（31 个文件全部存在）

```bash
cd ypbin-iot-ui
for f in \
  apps/web-antd/src/views/dashboard/workspace/index.vue \
  apps/web-antd/src/views/dashboard/analytics/index.vue \
  apps/web-antd/src/views/system/{app,user,dept,post,role,menu,client,dict,tenant,auth-template,notice,file,license,log,online-user}/list.vue \
  apps/web-antd/src/views/system/config/index.vue \
  apps/web-antd/src/views/system/{app,user}/list.vue \
  apps/web-antd/src/views/_core/message/list.vue \
  apps/web-antd/src/views/tracking/{events,catalog,analysis,funnel,retention}/list.vue \
  apps/web-antd/src/views/ai/{chat,knowledge,wiki,prompt,role,config,usage}/index.vue ; do
  [ -f "$f" ] && echo "OK   $f" || echo "MISS $f"
done
```

**实测输出：31 个文件全部 `OK`，0 个 `MISS`**（含 `views/system/app/list.vue` = 开放应用页、`views/dashboard/workspace/index.vue` = 现有工作台页、`views/ai/*` 7 页、`views/tracking/*` 5 页）。⇒ §4/§5 图中所有标「复用」的页面**都在仓内真实存在**。

权限码侧同样核实（`deploy/sql/002-data.sql`）：

```bash
grep -n "system:app:" ypbin-iot/deploy/sql/002-data.sql
# 312-315: system:app:add / system:app:edit / system:app:delete / system:app:reset-secret
```

### A.9 本文引用的旧文事实（不重复核实，只做指针）

| 事实 | 出处 |
|---|---|
| IoT 内部 5 组 IA（接入配置 / 设备管理 / 数据与调试 / 运维中心 / 系统） | `docs/IOT-UX-PROPOSAL.md` §4.1、§4.2 |
| 12 条数据模型缺口（含"下行通道不存在""reported 从不写入""告警链路不存在"） | `docs/IOT-UX-PROPOSAL.md` §0.2 |
| IoT 只改前端的 F1–F6 / P0 界面清单 | `docs/IOT-UX-PROPOSAL.md` §9.5 |
| 7 平台一手对照（官方链接 + 访问日期 2026-09-25） | `docs/IOT-UX-PROPOSAL.md` §2.1、§2.3 |
| IoT 原型（可点） | `docs/ux-mock/index.html` |

**上游状态核实**：`git ls-tree origin/main docs/ux-mock/` ⇒ `README.md`、`index.html` **均在 `origin/main`（4fdba39，PR #41 已合并）**。故本文件与旧文的互链在 main 上**即时有效**。

---

### A.10 门禁缺陷的复现与修正版验证（§7.1.4 的全部证据）

**复现方式**：不跑 Maven（本机低配，且复核要求只读），而是**逐字复刻** `IotMaintenanceAdminGateTest#everyMenuIdMustBeGranted` 的取数与判定逻辑（同样的 `sql.split("INSERT INTO " + table)`、同样的 `GRANT_IN = id IN \(([^)]*)\)` 正则），输入 = **真实 `deploy/sql/007-iot-data.sql` + 本方案 §7.1.1 的追加 SQL**。

#### （1）复现"假绿"（原门禁逻辑）

```python
import re
sql = open('deploy/sql/007-iot-data.sql', encoding='utf-8').read()
GRANT_IN = re.compile(r"id IN \(([^)]*)\)")
def granted(sql, table):
    g = set()
    for b in re.split(r"INSERT INTO " + table, sql)[1:]:   # 复刻 Java String.split 的语义
        for m in GRANT_IN.finditer(b):
            for it in m.group(1).split(','):
                g.add(it.strip())
    return g
for t in ("sys_role_menu", "sys_template_menu"):
    print(t, len(granted(sql, t)), "| 320014 in granted:", "320014" in granted(sql, t))
print("320014 真的出现在 sys_template_menu 的 INSERT 里吗:",
      any("320014" in re.search(r"id IN \(([^)]*)\)", s).group(1)
          for s in re.findall(r"INSERT INTO sys_template_menu(.*?);", sql, re.S)
          if re.search(r"id IN \(([^)]*)\)", s)))
```

**实测输出：**

```
sys_role_menu 33 | 320014 in granted: True
sys_template_menu 33 | 320014 in granted: True
320014 真的出现在 sys_template_menu 的 INSERT 里吗: False
```

⇒ 两张表的 `granted` **完全相同（33 条）**，而 `320014` 从未出现在任何 `sys_template_menu` 的 INSERT 里（`007-iot-data.sql:90-97` 明文"绝不进 sys_template_menu"）。
⇒ **模板侧检查是假绿**。根因：`split` 切出的 block 跨语句，把 `:96-97` 的 **role** 授权语句也算进了 **template** 的 `granted`。

#### （2）修正版门禁的基线与三个变异

修正点：① 先剥 `--` 注释；② 按 `;` 切语句；③ **只在该语句确实是 `INSERT [IGNORE] INTO <本表>` 时**才取该语句内的 `id IN (...)`（按语句归属，不跨语句）；④ 期望改为"一律要 `sys_role_menu`；`platform_only=0` 时才要 `sys_template_menu`"。

**实测输出：**

```
== 修正版门禁：语句级归属 + 剥注释 + platform_only 感知 ==
基线: role缺失=[]   template缺失=[]        (解析菜单 35 条)        ✅ 全绿
[变异1] 删 3310 的 template 授权（platform_only=0 ⇒ 必须转红）
   role缺失=[]   template缺失=['3310']                              ✅ 转红
[变异2] 删 3310/3320 的 role 授权（必须转红）
   role缺失=['3310', '3320']   template缺失=[]                      ✅ 转红
[变异3] 把 3320 改成 platform_only=0 但不补 template 授权（必须转红）
   role缺失=[]   template缺失=['3320']                              ✅ 转红
```

#### （3）"只修作用域、不改规则"会在正确数据上假红

**实测输出：**

```
【只修"语句级归属"、仍用"两张表都必须覆盖"的朴素规则】在真实 007 上的结果：
  sys_template_menu 缺失 = ['320014', '320015']
  ⇒ 这两条 id 的 platform_only = {'320014': '1', '320015': '1'}
```

⇒ 两条 `platform_only=1` 的菜单（按既有约定**故意**不进模板）会被朴素规则误报 ⇒ **修作用域与改规则必须同时做**。

#### （4）孤儿自检的迁移前基线（活库实测）

```bash
ssh ypbin-prod 'docker exec ypbin-mysql bash -c '"'"'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "
SELECT rm.role_id, m.id, m.name, m.pid
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0 AND m.status = 1
LEFT JOIN sys_role_menu pr ON pr.role_id = rm.role_id AND pr.menu_id = m.pid
WHERE m.pid <> 0 AND pr.menu_id IS NULL ORDER BY rm.role_id, m.id" ypbin_admin'"'"''
```

**实测输出（5 行，全部是 role 1 = 超管）：**

```
1	230	SystemMenu	3002
1	290	SystemClient	3002
1	5003	AiConfig	5000
1	5050	AiUsage	5000
1	270004	SystemPushTest	2700
```

模板侧同一查询：**0 行**。⇒ §7.1.2 的正确口径是"**不新增**孤儿"，不是"绝对 0 行"。

## 附录 B · 交付物与 PR

| 交付物 | 仓库 / 路径 | PR |
|---|---|---|
| 本方案（平台级 IA） | `ypbin-iot` → `docs/PLATFORM-IA-PROPOSAL.md` | [ypbin-iot#42](https://github.com/wenbin-wb/ypbin-iot/pull/42) |
| 原型互链说明（+5 行，**不改 `index.html`**） | `ypbin-iot` → `docs/ux-mock/README.md` | 同上 |
| 平台级可点原型（单文件、零外链） | `ypbin-iot-ui` → `docs/ux-mock/platform-nav.html` | [ypbin-iot-ui#19](https://github.com/wenbin-wb/ypbin-iot-ui/pull/19) |
| 原型说明（含与 `index.html` 的分工对比） | `ypbin-iot-ui` → `docs/ux-mock/README.md` | 同上 |
| IoT 模块内部 IA（上一轮，已被本方案引用） | `ypbin-iot` → `docs/IOT-UX-PROPOSAL.md` + `docs/ux-mock/index.html` | 已合并（#41，`4fdba39`） |

**两个 PR 均按要求不合并。** 分支：`docs/platform-ia-proposal`（ypbin-iot）、`docs/platform-nav-mock`（ypbin-iot-ui），均基于各自 `origin/main`。

**部署原型（可选）**：`platform-nav.html` 与 `index.html` 一样是静态文件，拷进 `ypbin-iot-ui` 容器的 `/usr/share/nginx/html/ux-mock/` 即可经 `http://<host>:19000/ux-mock/platform-nav.html` 访问（实测该容器映射 `19000->80`）。

---

## 附：独立复核回执（原文要点，未润色）

**复核者身份与方式**：独立子代理，**不同上下文**、只读、自行跑命令、不继承本文结论；**未修改任何仓内文件**（临时产物只在 `/tmp`）、未 commit/push、生产库仅 `SELECT`、未回显任何口令。

### 总结论（复核者原文）

> **FAIL（有条件）** —— 方向与迁移主干站得住（`mixed-nav` 机制、4 模块划分、reparent+补授 SQL 语义、6 条外部引用全部 200 且引语逐字命中），但有两处实质缺陷能否决"可直接实施"：
> **(1)** 方案当作核心增值的 K3 分析**实证不成立**；更要命的是它视为"唯一能兜住 K2 盲区"的 `IotMaintenanceAdminGateTest` 的 `sys_template_menu` 覆盖面检查**是假绿** —— 我把方案要求的变异实测跑了一遍：删掉 `3310` 的模板授权，门禁**不转红**。
> **(2)** `4001 接口文档` 归属在同一份文档内自相矛盾；且 §7.1.2 要求的"期望 0 行"迁移后自检**实测不会是 0**。

### 关键实测摘录（复核者自跑）

- **门禁假绿**：`sql.split("INSERT INTO "+table)` 后**跨语句累积** `id IN (...)`，被另一张表污染；`320014/320015` 在 007 里从未出现在任何 `sys_template_menu` INSERT（按段 grep 计数 0），门禁却报 `granted=true`。
- **"天真扩到 `33xx` 会假红"不成立**：实测 `MISSING=[]`。
- **变异实测**：删 `3310` 的模板授权 → `sys_template_menu MISSING=[]`，**不转红**；只有删 `3310/3320` 的角色授权才因 `3320` 消失而红。⇒ 方案推荐的 (a)"沿用既有验证结构"**同样不咬人**，按原方案实施会得到"门禁全绿 + 变异验证也全绿"的**双重假象**。
- **孤儿自检**：活库现存 **5 条角色孤儿**（`role 1` 缺父 `2700/5000/3002`；子 `270004/5050/5003/290/230`），迁移只补 `3310/3320` 不修 ⇒ 会返 5 行，易被误判为迁移回归（`role 1` 是超管，无可见影响）。
- **外部引用**：6 条 URL 全 200；抽查 5 条**引语逐字命中**；自我声明诚实（通式标"我方推断"、飞书标未核实，未把二手冒充一手）。
- **原型**：真零外链；独立统计 **46 项**与 README 表格逐格吻合；route 双向无缺口；`#/dashboard` 确为别名（`HTML:588`）。
- **`4001` 归属矛盾**（复核者原文）："md:292(基础管理) 与 md:300(运维与监控) 同节两处；§5 mermaid md:406 `ADMIN-->DOC` 与 md:447 `OPS-->DEV` 同时挂；原型只放运维；而 §4.2(A)+§7.1.1 SQL 搬进 3310 基础管理 ⇒ 按 SQL 实施后原型 admin15/ops8 会变 16/7。"

### 复核者未能核实的事项（原文）

| # | 想核实什么 | 为什么核不了 |
|---|---|---|
| 1 | 飞书管理后台官方正文 | 帮助中心正文前端渲染，抓回的 HTML 无正文，`/hc/api/*` 探测亦无果 |
| 2 | 13 / 4-5 项在具体视口下是否触发「更多」折叠 | 需浏览器渲染；代码层机制已确认 |
| 3 | "没有任何官方文档使用 More""腾讯云是唯一明文写双层结构" | **全称否定/评估断言不可穷尽**（抽查 5 页无 "More"） |
| 4 | 真实 JUnit 运行 | 为遵守只读未做 Maven 构建，用真实 JDK 逐字复刻其正则与 `split` 语义跑判定（覆盖该测试两条断言的核心） |
| 5 | 上游 `ypbin-admin` 是否规划 `33xx` | 同本文 U6 |
| 6 | §3.4 未抽查的次级链接 | 时间与范围所限 |

### 本文对回执的处理

- **全部 12 处问题已整改**（§8.2 的 3 项 + §8.3 的 9 项）；**2 处本文此前的错误结论已明确撤回**（"天真扩法会假红"、"沿用既有验证结构"）。
- **整改后未再送第二轮独立复核** ⇒ 按 R6，本文**只声明"已按复核意见整改"，不声明"整改已通过独立验收"**（已记为 U10）。

