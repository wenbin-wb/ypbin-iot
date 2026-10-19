# 本仓消费 starter #106 / #107 的落地记录（ypbin-iot）

> 本文件是 `docs/data-permission.md`（同步自 admin 基座、与本仓零分歧）的**补充**，
> 只记录「本仓与基座不同的处置」。基座的语义说明、14 条迁移对照、已知边界仍以
> `docs/data-permission.md` 为准，不在此重复。

## 一句话结论

#106（树组装）与 #107（数据范围）的宿主侧迁移，admin `main`（`fd4dc07`）**已完成**。
本仓的处置原则是**能逐字采纳就采纳**：自研一份等价实现换不来任何收益，只会永久多一个同步冲突点。
最终只有 **1 个文件**与上游有真实分歧（且是修上游的缺陷），其余全部零分歧。

## 逐文件处置

| 文件 | 处置 | 与 upstream |
|---|---|---|
| `SysMenuServiceImpl.java` | 采纳上游（`TreeUtils` 函数式建树 + 内部可变 `RouteNode` + 空 children 归一） | **零分歧** |
| `DataScopeEnum.java` | 采纳上游（`fromCode` 委托 starter 的 `ScopeType.fromCode`，枚举本身保留） | **零分歧** |
| `AbstractDataScopeResolver.java` | 采纳上游（`extends AbstractDataScopeHandler`，98 行） | **零分歧** |
| `DataScopeDataSources.java` | 采纳上游（取数层，115 行） | **零分歧** |
| `DataScopeDiagnostics.java` | 采纳上游（无身份头文案常量，41 行） | **零分歧** |
| `docs/data-permission.md` | 采纳上游 | **零分歧** |
| `SysDeptServiceImpl.java` | 采纳上游 **+ 补回叶子 children 归一** | **唯一分歧**（已登记白名单第 48 项） |

### 为什么改 `SysDeptServiceImpl`（实测取证，不是推断）

`TreeUtils.build` 的 `childrenSetter` 是 `BiConsumer`，**一律写回**（可能为空的）列表；
而迁移前的递归只在**有子节点**时 `setChildren`，叶子节点 `children` 保持 `null`。
`DeptResp` 带 `@JsonInclude(NON_NULL)` —— 它只过滤 `null`，**不过滤空集合**。实测：

```
迁移后（直接采纳上游）: [{"id":1,...,"children":[{"id":2,...,"children":[]}]}]
迁移前（叶子 children=null）: [{"id":1,...,"children":[{"id":2,...}]}]
```

即部门接口的**对外响应形态**变了（叶子多出 `"children":[]`）。任务要求「行为 100% 一致」，
故补回归一。`SysMenuServiceImpl` 上游**做了**这个归一，`SysDeptServiceImpl` 漏了——
属上游的不一致，本仓按需方口径对齐（并已在上游修复方向留了注记）。

## 测试

| 测试 | 来源 | 说明 |
|---|---|---|
| `TreeAssemblyConsistencyTest`（6 条） | 采纳上游 + **修入参共用缺陷** | 孪生对照：迁移前递归实现原样留在测试里，对同一份扁平数据逐节点比对 |
| `DataScopeMigrationBoundaryTest`（10 条） | 采纳上游 | 多角色并集、部门成环、空 deptId 读写、超管短路、重入防护、脏码收窄 |
| `AdminDataScopeHandlerTest`（18 条） | **既有，零修改** | 主路径 + 诊断文案（改回旧文案即转红） |
| `SysUserServiceImplDeptScopeTest`（4 条） | **既有，零修改** | 写路径部门归属 |
| `DataScopeSupplementaryBoundaryTest`（6 条） | **本仓新增（新文件）** | 上游边界用例没覆盖的四类风险，见下 |

### 为什么新开一个测试文件而不是改上游的

`DataScopeMigrationBoundaryTest` 在上游已存在；改它会产生分歧面、必须进同步白名单（永久冲突点）。
补充用例全部放新文件 `DataScopeSupplementaryBoundaryTest`，**零同步成本**（白名单判据只约束
「改动的既有文件」）。它补的是：

1. **脏码不污染有效角色**：一条 `data_scope=99` 不能让同用户其它有效角色失效；
2. **三角色并集 + 部门去重**：同一部门被多角色重复贡献时片段文本不膨胀；
3. **写路径的成环防护**：上游只在读路径测了环，写路径同样走后代展开；
4. **重入标记不泄漏到写路径**：读路径置位 `RESOLVING` 后必须清理，否则写校验被永久拒绝。

### 孪生对照测试里修掉的一个真实缺陷（上游版本也有）

初版（含上游版）让 `actual` 与 `expected` **共用同一批可变 `DeptResp` 实例**。由于
`TreeUtils` 与递归版都会 `setChildren` 覆盖原对象，后跑的那条会把前一条的 children 覆盖掉，
断言退化成「自己和自己比」而**恒绿**。本仓改为两条路径各用**独立一份入参**，
并用变异验证确认：把归一行删掉 / 把根判定改错，用例都会转红。

## 变异验证（证明测试不是恒绿）

| 变异 | 结果 |
|---|---|
| `SysDeptServiceImpl` 去掉归一 | `TreeAssemblyConsistencyTest` 转红 |
| 建树 `rootParentId` 传 `null`（根判定丢失） | `TreeAssemblyConsistencyTest` 转红 |
| `resolveCondition` 调 `getDataScopeSql`（admin 踩过的无限递归） | 3 个数据范围测试共 24 条转红（`StackOverflowError`） |
| 去掉 `TARGET_TABLE` 白名单 | 相关用例转红 |

## 验收命令

```bash
mvn -B -pl ypbin-service/ypbin-system,ypbin-service/ypbin-access,ypbin-architecture-tests -am test

# 行数（#107 验收：应显著下降；admin 做到 98 行，本仓同值）
wc -l ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/service/support/AbstractDataScopeResolver.java
```
