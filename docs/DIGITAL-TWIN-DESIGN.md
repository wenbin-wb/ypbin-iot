# 数字孪生（3D 场景）完整设计

> **状态**：v1.0 定稿（2026-10-21）。方向由用户拍板：**B 方案（3D 场景孪生）+ 原生 three.js**，
> 要求「一次性做到最完善」，不做「先简单、以后返工」。
>
> **本文档地位**：数字孪生域的**唯一设计总纲**。数据模型、接口契约、权限与菜单、实施顺序以本文为准；
> 完整后端源码见 [`DIGITAL-TWIN-BACKEND.md`](DIGITAL-TWIN-BACKEND.md)，完整前端源码见
> [`DIGITAL-TWIN-FRONTEND.md`](DIGITAL-TWIN-FRONTEND.md)。三份文档的契约必须逐字一致，改动必须三处同改。
>
> **与既有文档的关系**：本文是 `IOT-PLATFORM-DESIGN.md` §1.1「大屏/数字孪生（远期目标，优先级低）」
> 的展开落地，取代该段的「远期」定性；不改变该设计文档的其它任何决策（租约/EMQX/IoTDB/多租户底线等）。
>
> **与点位的区别（最容易混淆的一处，先钉死）**：
> `iot_point_mapping` 是**采集域**的映射（协议地址 ↔ 物模型属性），决定「采什么、从哪个寄存器采」；
> `iot_twin_point` 是**展示域**的映射（3D 空间锚点 ↔ 设备），决定「设备在模型里的哪个位置、展示哪几个属性」。
> 两者**语义正交，不得合并成一张表**，也不得互相写入。

---

## 0. 决策记录

| # | 决策 | 结论与理由 |
|---|---|---|
| **D0.1** | 产品形态 | **B 方案：3D 场景孪生**（单场景 3D 模型 + 设备锚点 + 实时驱动）。A 方案（2.5D 打点）作为 B 的**降级表达**被覆盖（B 的配置表能表达 A），C 方案（厂区 BIM/GIS）本期**排除** |
| **D0.2** | 渲染引擎 | **原生 three.js**（不用 TresJS）。理由：孪生页的交互（拾取标点、拖拽微调、相机飞行、告警高亮、资源释放）几乎全是**命令式**逻辑，声明式封装在这里不减少代码量、却多一层框架；且「配置态」与「运行态」要共用同一套场景装配代码 |
| **D0.3** | 场景层级 | **场景树**，`scene_type ∈ {park, building, floor, room}`，深度上限 `MAX_SCENE_DEPTH = 4`。房间**不单独加载模型**（在楼层场景内用区域标注表达），避免第 5 级模型管理成本超过收益 |
| **D0.4** | 场景切换 | **默认「切换加载 + 淡入淡出」**（省显存）；「飞行下钻」为可选模式，通过**场景树 anchor 变换**保证两种模式共用同一世界坐标链（见 §5.2）。飞行模式在模型文件 > `FLY_MAX_MODEL_BYTES` 时由前端显式禁用并提示原因，不静默降级 |
| **D0.5** | 点位口径 | **点位 = 设备在 3D 中的锚点**（一场景内一台设备一条锚点）；锚点上**绑定 N 个属性**做展示。不做「一个属性一条锚点」（会让同一设备散落在模型各处，与「设备点位」的业务直觉相悖） |
| **D0.6** | 属性绑定键 | **绑定 `service_identifier + property_identifier`（业务标识），冗余 `property_id` 仅作联查缓存**。理由见 §4.2（TSL 重新导入会物理删除属性行，绑定主键必然孤儿） |
| **D0.7** | 物模型是否加「孪生展示」开关 | **不加**。理由见 §4.3（决定性理由：已发布物模型不可变，加开关会让上线产品的展示配置锁死） |
| **D0.8** | 模型文件来源 | 两条通道并存：**平台内上传**（`glb`/`gltf`，落存储）与**外链登记**（用户自有静态服务 / OSS URL）。上传通道经 iot 服务流式下载，**不依赖 nginx 静态目录**，鉴权与租户隔离由 API 层承担 |
| **D0.9** | 实时数据来源 | **完全复用既有 SSE 实时流**（`GET /iot/realtime/stream` 的 `latest`/`device-status`/`alert` 三事件）+ **新增一个场景快照端点**（一次拉齐，带批量最新值）。不新建实时链路 |
| **D0.10** | 批量最新值 | 新增 `LatestValueQueryService#listLatestBatch`，**Redis pipeline + 一次批量权限校验**，禁止按设备循环 `HGETALL`（N+1 零容忍） |
| **D0.11** | 租户与所有权 | 4 张孪生表全部继承 `TenantBaseEntity`，租户隔离交给 MyBatis-Plus 租户插件（与 `iot_device` 同口径），**服务内不手写 `tenant_id` 过滤**；模型下载额外做**跨租户存在性不泄露**（查不到即 409「模型不存在」，不是 403） |
| **D0.12** | 权限粒度 | 4 个权限码：`iot:twin:list` / `create` / `update` / `delete`。**不设**「每个场景一个权限」（场景是展示配置，不是受控资源，与看板同口径）；但**模型上传/删除归 create/delete**，因为模型文件占用存储配额 |
| **D0.13** | 属性展示上限 | 单锚点绑定 **≤ 12 个属性**（`MAX_BINDINGS_PER_POINT`）、单场景点位 **≤ 500**（`MAX_POINTS_PER_SCENE`）、单属性锚点运行时值 **≤ 8KB**。上限同时写进 Req 的 `@Size` 与服务侧复核（防御式双保险） |

---

## 1. 目标 / 非目标

### 1.1 目标

| 用途 | 设计目标 |
|---|---|
| 园区总览 | 大场景模型 + 设备状态着色 + 点位标签，一屏看全厂 |
| 多级下钻 | 园区 → 楼 → 楼层 → 房间，点击下钻，可返回上级；支持面包屑与 URL 深链 |
| 配置化 | **零代码**配置：上传模型 → 建场景树 → 点选锚点 → 绑定设备与属性，全部在页面上完成 |
| 实时驱动 | 属性值实时变更、在线/离线着色、活动告警闪烁、点击锚点下钻或打开设备详情 |
| 可运维 | 反复切换场景**不涨内存**（强制 dispose）、模型文件可替换、配置错误有明确提示 |

### 1.2 非目标

- ❌ 自研组态编辑器（拖拽式管线/连线编排）——`iot_twin_link` 本期**不做**，只做设备锚点
- ❌ 动画与物理仿真（设备运动轨迹、机械臂动画）
- ❌ 视频流贴图 / 摄像头画面叠加
- ❌ 免登录大屏投屏（涉及只读分享链路与既有安全决策 D0.5，需单独立项）
- ❌ 时序回放（历史曲线仍走既有 `/iot/devices/{id}/series` 页面）
- ❌ 移动端适配（页面按桌面 1280px+ 设计，窄屏给明确提示）

### 1.3 硬约束（来自既有工程事实，实现时不可绕过）

| # | 约束 | 事实依据（已核实） |
|---|---|---|
| 1 | 本机约 5.8G 内存，不跑全量前端构建 | `IOT-PLATFORM-DESIGN.md` §1.3 |
| 2 | three.js 必须走动态 `import()` 独立 chunk，不得进主包 | 同上 + 现有动态 import 先例（`bootstrap.ts`） |
| 3 | 已发布物模型不可变（`requireProductDraft` + `IotThingModelDraftGuardTest` 门禁） | `IotThingModelServiceImpl:1016` |
| 4 | 属性行会被 TSL 重导入**物理删除**（`replaceTsl`） | `PointMappingIndex` 类注释 |
| 5 | Long 型 ID 全程字符串（序列化由 `BaseEntity` 的 `ToStringSerializer` 承担） | `BaseEntity` 源码 |
| 6 | SQL 迁移与全新安装脚本必须**语句等价**（`tools/check-iot-sql-equivalence.sh`） | 工具脚本 |
| 7 | 权限码必须同时登记在 `007-iot-data.sql` 与 `migration/*-iot-*.sql`（`IotPermissionCodeGateTest`）。该门禁有**两条**断言：① Controller 声明的码必须都在 007 与 migration 里；② 007 与 migration 两侧的码**集合必须完全相等**（`assertThat(freshInstall).isEqualTo(migration)`） | 门禁测试（已核实断言逻辑） |
| 8 | 菜单必须同时进 `sys_role_menu` 与 `sys_template_menu`（`platform_only=0` 时）（`IotMaintenanceAdminGateTest`）。**该门禁只读 `007-iot-data.sql`**（`INSTALL_SQL` 常量），不读 migration ⇒ 授权语句必须落在 007，只写 migration 会漏验。另注：其 `MODULE_CHILDREN` 只约束 `3310`/`3320` 两个平台模块目录的 K2 防孤儿补授，挂在既有 `3204` 下的新菜单不受该断言约束 | 门禁测试（已核实断言逻辑） |
| 8′ | 新增 migration 文件名**必须含 `-iot-`**，且必须登记进 `deploy/sql/migration/iot-migrations.list`（`check-iot-sql-equivalence.sh` 对清单与目录做一一对应强校验） | 工具脚本（已核实） |
| 9 | 后端注释不提「前端/vben」，前端注释不提「后端契约」以外的内部实现 | `ypbin-code-comment-no-frontend` |
| 10 | 禁止内联 FQN、禁止 `Collections.emptyXxx`、实体禁 `@Data`、枚举禁 `ordinal()`、禁循环内 DB/RPC | `SourceConventionTest` |

---

## 2. 总体架构与数据流

### 2.1 组件视图

```
浏览器（three.js 渲染进程）
  ├─ 配置态：场景树 CRUD + 模型上传 + 锚点拾取/拖拽 + 属性绑定
  └─ 运行态：模型渲染 + 锚点着色 + 实时值标签
        │  HTTP（/iot/twin/**，网关 Path=/iot/** StripPrefix=1）
        │  SSE （/iot/realtime/stream，既有）
        ▼
┌─────────────────────────────────────────────────────┐
│ ypbin-iot（18084）                                   │
│  controller/IotTwinSceneController   场景树 + 点位   │
│  controller/IotTwinModelController   模型资产 + 流式下载 │
│  service/IotTwinSceneService         树校验 + 绑定解析 │
│  service/IotTwinModelAssetService    上传/魔数校验/删除 │
│  service/IotTwinRuntimeService       场景快照组装      │
│  ├─ LatestValueQueryService#listLatestBatch（新增）   │
│  ├─ AlertInstanceService#activeCountsByDevice（复用） │
│  └─ PointMappingIndex（复用：属性标识归一）            │
└───────┬──────────────────────┬──────────────────────┘
        │ MySQL                │ Redis            │ 存储
   iot_twin_* (4 表)      iot:latest:{t}:{d}   ypbin-storage(local)
```

### 2.2 配置态数据流

```
上传 glb → iot_twin_model_asset（文件元数据 + SHA-256 + 节点名快照）
   ↓ assetId
建场景节点 iot_twin_scene（scene_type / parent_id / anchor 变换 / view_json 相机）
   ↓ sceneId
3D 内拾取模型表面交点 → iot_twin_point（device_id + local 坐标 + 法线 + 节点名）
   ↓ pointId
选属性（设备绑定产品的物模型）→ iot_twin_point_binding（service/property 标识 + 展示形态）
```

### 2.3 运行态数据流

```
进入场景
  → GET /iot/twin/scenes/{id}/runtime        （一次拉齐：点位 + 设备快照 + 最新值 + 告警数）
  → 渲染：按 onlineStatus/status 着色锚点，按 alertCount 闪烁，按 bindings 渲染值标签
  ↓
SSE latest        → 命中本场景设备集合 ⇒ 写入待渲染队列，rAF 合并后一次性更新（≤ 200ms）
SSE device-status → 设备在线/启停变化 ⇒ 更新该锚点着色
SSE alert         → 告警变化 ⇒ 重拉 runtime（带去抖，1200ms，与仪表盘同口径）
```

---

## 3. 数据模型

### 3.1 实体关系

```
iot_twin_scene (场景树，自关联；parent_id=0 为根)
   ├── model_asset_id ──► iot_twin_model_asset（模型文件/外链）
   └── 1:N ──► iot_twin_point（设备锚点）
                   └── 1:N ──► iot_twin_point_binding（展示哪几个属性）
```

**为什么只有 4 张表**：
- 场景与「场景用哪个模型」是 1:1（一个场景一个模型），不拆中间表；
- 「点位」与「绑定」拆两级，因为「设备在哪个位置」与「展示哪几个属性」是**两个独立变更频率**（位置定了很少改，展示属性经常调），拆开才能分别更新且不互相覆盖；
- 不做 `iot_twin_link`（管线连线），见 §1.2 非目标。

### 3.2 DDL 全文

> 语句同时进 `deploy/sql/007-iot-data.sql`（全新安装，**追加在文件末尾**）与
> `deploy/sql/migration/2026-10-21-iot-twin-schema.sql`（已上线库），两者**语句必须逐字等价**（注释可不同）。
>
> ⚠️ **落盘位置与顺序是硬约束，照直觉写会让 SQL 等价门禁恒红**（已实测）：
> `tools/check-iot-sql-equivalence.sh` 比对「006+007 顺序拼接」vs
> 「`migration/*-iot-*.sql` **按文件名字典序**拼接」，且**逐行 diff、不做语句级归一化**
> （只剥 `--` 注释 + 压缩空白）。而 `2026-10-21-iot-twin-**menu**.sql` 字典序**先于**
> `2026-10-21-iot-twin-**schema**.sql`（`m` < `s`）⇒ **007 里也必须 menu 段在前、schema 段在后**。
> 若按「DDL 归 006、DML 归 007」的直觉放，两边顺序必然对不上（`EXIT=1`）。既有先例一致：
> `2026-09-30-iot-credential-menu.sql` 也排在 `-credential-schema.sql` 之前。

```sql
-- =============================================================
-- 数字孪生域（2026-10-21 追加）
-- 覆盖：iot_twin_model_asset / iot_twin_scene / iot_twin_point / iot_twin_point_binding
-- 租户表：4 张表都含 tenant_id 且**不**进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables，
--       由 MyBatis-Plus 租户插件统一追加 tenant_id 条件（与 iot_device 同口径）。
-- =============================================================

CREATE TABLE iot_twin_model_asset
(
    id              BIGINT       NOT NULL COMMENT '主键',
    tenant_id       BIGINT       NOT NULL COMMENT '租户 ID',
    asset_name      VARCHAR(128) NOT NULL COMMENT '模型名称（租户内唯一）',
    asset_type      VARCHAR(16)  NOT NULL COMMENT '来源类型：upload 平台内上传 | external 外链登记',
    model_format    VARCHAR(16)  NOT NULL COMMENT '模型格式：glb | gltf',
    storage_platform VARCHAR(32) NULL COMMENT '存储平台标识（asset_type=upload 时非空）',
    storage_bucket  VARCHAR(128) NULL COMMENT '存储桶（asset_type=upload 时非空）',
    storage_path    VARCHAR(512) NULL COMMENT '存储路径（asset_type=upload 时非空）',
    external_url    VARCHAR(1024) NULL COMMENT '外链地址（asset_type=external 时非空，仅允许 http/https）',
    original_name   VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅 upload）',
    file_size       BIGINT       NULL COMMENT '文件字节数（仅 upload）',
    content_hash    VARCHAR(64)  NULL COMMENT 'SHA-256 十六进制摘要（仅 upload；用于判重与 ETag）',
    node_names_json LONGTEXT     NULL COMMENT '模型节点名快照（JSON 字符串数组；仅作按节点名预匹配的辅助索引，不是权威数据）',
    create_user     BIGINT       NULL COMMENT '创建人',
    create_time     DATETIME     NULL COMMENT '创建时间',
    update_user     BIGINT       NULL COMMENT '更新人',
    update_time     DATETIME     NULL COMMENT '更新时间',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_model_asset (tenant_id, asset_name),
    KEY idx_iot_twin_model_asset_tenant (tenant_id)
) COMMENT 'IoT 数字孪生模型资产';

CREATE TABLE iot_twin_scene
(
    id           BIGINT       NOT NULL COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '租户 ID',
    scene_code   VARCHAR(64)  NOT NULL COMMENT '场景编码（租户内唯一，用于深链与免歧义引用）',
    scene_name   VARCHAR(128) NOT NULL COMMENT '场景名称',
    parent_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '父场景 ID（0=根场景；用 0 而不是 NULL：MySQL 唯一键对 NULL 不去重）',
    scene_type   VARCHAR(16)  NOT NULL COMMENT '场景类型：park | building | floor | room',
    model_asset_id BIGINT     NULL COMMENT '绑定的模型资产 ID（NULL=纯容器节点，不渲染模型）',
    anchor_x     DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 X',
    anchor_y     DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Y',
    anchor_z     DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Z',
    anchor_rx    DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 X 轴旋转（度）',
    anchor_ry    DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Y 轴旋转（度）',
    anchor_rz    DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Z 轴旋转（度）',
    anchor_scale DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点缩放系数（> 0）',
    view_json    LONGTEXT     NULL COMMENT '默认视角与环境配置（相机位姿/背景/光照，JSON；空=前端用默认值）',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    remark       VARCHAR(500) NULL COMMENT '备注',
    create_user  BIGINT       NULL COMMENT '创建人',
    create_time  DATETIME     NULL COMMENT '创建时间',
    update_user  BIGINT       NULL COMMENT '更新人',
    update_time  DATETIME     NULL COMMENT '更新时间',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_scene (tenant_id, scene_code),
    KEY idx_iot_twin_scene_parent (tenant_id, parent_id)
) COMMENT 'IoT 数字孪生场景（树形）';

CREATE TABLE iot_twin_point
(
    id          BIGINT       NOT NULL COMMENT '主键',
    tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
    scene_id    BIGINT       NOT NULL COMMENT '所属场景',
    device_id   BIGINT       NOT NULL COMMENT '绑定的设备（iot_device.id）',
    node_name   VARCHAR(255) NULL COMMENT '绑定的模型节点名（可空；仅用于高亮该节点与按节点名预匹配）',
    local_x     DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 X',
    local_y     DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Y',
    local_z     DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Z',
    normal_x    DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 X（用于标签与图标贴面）',
    normal_y    DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点朝向法线 Y',
    normal_z    DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 Z',
    icon        VARCHAR(64)  NULL COMMENT '图标标识（图标集内的名字；空=默认设备图标）',
    label_visible TINYINT    NOT NULL DEFAULT 1 COMMENT '是否在运行态常显文字标签：1 是 0 否',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序（同场景内标签叠放顺序）',
    remark      VARCHAR(500) NULL COMMENT '备注',
    create_user BIGINT       NULL COMMENT '创建人',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_user BIGINT       NULL COMMENT '更新人',
    update_time DATETIME     NULL COMMENT '更新时间',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point (tenant_id, scene_id, device_id),
    KEY idx_iot_twin_point_scene (tenant_id, scene_id),
    KEY idx_iot_twin_point_device (device_id)
) COMMENT 'IoT 数字孪生点位（设备在场景中的锚点）';

CREATE TABLE iot_twin_point_binding
(
    id                  BIGINT       NOT NULL COMMENT '主键',
    tenant_id           BIGINT       NOT NULL COMMENT '租户 ID',
    point_id            BIGINT       NOT NULL COMMENT '所属点位（iot_twin_point.id）',
    service_identifier  VARCHAR(64)  NOT NULL COMMENT '物模型服务标识（iot_service.service_id）',
    property_identifier VARCHAR(64)  NOT NULL COMMENT '物模型属性标识（iot_property.identifier）',
    property_id         BIGINT       NULL COMMENT '属性主键缓存（仅作联查加速；属性被 TSL 重导入删除后为悬空值，读取时按标识重解析）',
    display_type        VARCHAR(16)  NOT NULL COMMENT '展示形态：value | gauge | switch | color | sparkline',
    display_role        VARCHAR(16)  NOT NULL DEFAULT 'secondary' COMMENT '展示角色：primary 主展示 | secondary 次展示',
    decimals            TINYINT      NULL COMMENT '小数位（0~6；空=原样展示）',
    unit_override       VARCHAR(32)  NULL COMMENT '单位覆盖（空=用物模型属性自带单位）',
    sort                INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_user         BIGINT       NULL COMMENT '创建人',
    create_time         DATETIME     NULL COMMENT '创建时间',
    update_user         BIGINT       NULL COMMENT '更新人',
    update_time         DATETIME     NULL COMMENT '更新时间',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point_binding (tenant_id, point_id, service_identifier, property_identifier),
    KEY idx_iot_twin_point_binding_point (point_id)
) COMMENT 'IoT 数字孪生点位属性绑定';
```

### 3.3 关键设计说明

**① `parent_id` 用 `0` 而不是 `NULL`**
`iot_device_group` 用的是可空 `parent_id`，但它没有「同级唯一」约束。孪生场景需要
`uk_iot_twin_scene(tenant_id, scene_code)` 之外的树形一致性，而 MySQL 唯一键**不把两个 NULL 视为重复** ⇒
若父 id 可空，同一租户下可以插入两条 `parent_id IS NULL` 的同名根节点。用 `0` 表示根，
与 `sys_menu.pid` 的既有约定一致，也让「查某节点的全部子节点」是一条等值查询而不是 `IS NULL OR = ?`。

**② 场景锚点用 7 个显式列而不是一个 JSON**
`anchor_*` 是**后端拥有语义**的结构化数据（参与世界坐标链计算，见 §5.2），拆分列出可查询、可校验
（`anchor_scale > 0`）；`view_json` 才是**前端拥有的展示偏好**（相机位姿、背景色、光照强度），
按看板 `config_json` 的既有先例走 JSON 透传。

**③ 坐标用 `DECIMAL(20,6)`**
与仓库既有 `scale_factor`/`offset_value` 同精度。3D 坐标不需要 float 双精度，DECIMAL 避免
JSON 序列化出 `0.30000000000000004` 这类值污染前端矩阵计算。

**④ `iot_twin_point_binding.property_id` 是缓存不是外键**
见 §4.2。它是**可悬空**的，读取时必须 `LEFT JOIN iot_property ON property_id` 复核；悬空则按
`(service_identifier, property_identifier)` 重解析并回写（自愈），响应里带 `stale=true` 让页面提示。

**⑤ 唯一键覆盖「一场景一设备一条锚点」**
`uk_iot_twin_point(tenant_id, scene_id, device_id)`：同一设备在同一场景只能有一个锚点
（否则同一个值会在两处显示，用户改了一处另一处不同步）。同一设备**可以**出现在多个场景
（园区里一个点、楼层里一个细节点），这是合理的多级表达。

**⑥ 不做 `iot_twin_link`**
管线/连线是「组态编辑器」范畴（§1.2 非目标）。若将来要做，按本文的「场景树 + 锚点」模型可以增量加表，
不需要改动现有 4 张表的语义。

---

## 4. 三个关键设计决策的论证

### 4.1 为什么点位 = 设备锚点，而不是「属性锚点」

用户的直觉是「点位就是具体设备」，这是对的，而且技术上也是最优的：

- **业务对齐**：现场人员说「3 号泵在哪」，指的是一个**设备**位置；属性（温度/压力/电流）是这台设备的
  多个读数，天然属于同一个位置。
- **避免散落**：如果按属性建锚点，一台有 20 个属性的设备会在模型上留下 20 个点，用户需要手动把它们摆在
  同一位置——这是无意义的手工劳动，且必然摆不齐。
- **降级表达**：绑定里用 `display_role=primary` 标出主展示属性，锚点图标只渲染主展示；次要属性在
  hover/点击的浮层里展示。这样「一屏很多点位」也不会糊。

### 4.2 为什么绑定用业务标识而不是属性主键（决定性）

事实依据（已核实的代码行为）：

1. `IotThingModelServiceImpl#replaceTsl` 在 TSL 重新导入时**物理删除**属性行再重建
   ⇒ 主键全部变化。`PointMappingIndex` 为此专门做了「孤儿映射」检测与 `countOrphanMappings` 巡检指标。
2. `iot_product_version` **只记录版本号/状态/发布时间，不含结构快照**
   （`IotPropertyMapper` / `IotServiceMapper` / `IotCommandMapper` / `IotEventMapper` 四处注释均明确写出）
   ⇒ 主键没有任何跨版本稳定性保证。
3. 最新值的「规范坐标」在 2026-09-26 已统一为**属性标识**
   （`PointMappingIndex` 类注释 + `LatestValueQueryService` 的归一逻辑）
   ⇒ 孪生绑定用标识与实时数据坐标**天然同构**，不需要额外映射层。

结论：绑定 `(service_identifier, property_identifier)`，`property_id` 只作缓存。

### 4.3 为什么物模型不加「是否在孪生展示」开关

| # | 理由 | 强度 |
|---|---|---|
| 1 | **职责错位**：物模型描述「产品有什么能力」；「在哪个场景、什么形态、什么位置展示」是展示层配置。同一温度属性，A 场景要大号数字、B 场景要仪表盘、C 场景不展示——一个布尔开关表达不了 | 中 |
| 2 | **已发布物模型不可变（决定性）**：`requireProductDraft` 写守卫 + `IotThingModelDraftGuardTest` 门禁要求已发布产品的物模型任何写操作必须被拒。加这个开关 ⇒ **产品一旦上线，展示配置就永久锁死**，只能重新发版，业务不可接受 | **高** |
| 3 | **粒度不够且必然双重维护**：即使有开关，孪生配置里仍要配场景/形态/位置/精度，开关沦为冗余状态，还要处理「开关=展示但孪生没配」的矛盾 | 中 |
| 4 | **`expand` 字段也不能用**：`IotProperty.expand` 存在，但前端 `PropertySaveReq` **没有** expand 字段（存不进去）；`toTslProperty` 导出时**未回填** expand（TSL 导出丢该字段）；且它同样受草稿不可变约束 | 高 |

**替代做法**：选择动作全部放在孪生配置页，物模型零改动。若将来「每个场景重复勾选属性」确实繁琐，
再单独加**孪生域的产品级默认模板**表（`iot_twin_product_default`），仍然属于孪生域配置，不污染物模型。

---

## 5. 场景切换机制（B 方案的核心）

### 5.1 两种模式

| 模式 | 行为 | 显存峰值 | 适用 |
|---|---|---|---|
| **切换加载（默认）** | 淡出 → `dispose` 旧场景全部 GPU 资源 → 加载新场景 → 淡入 | 单场景 | 全部场景 |
| **飞行下钻（可选）** | 父子场景同时驻留，相机沿贝塞尔曲线飞向子场景 anchor，飞完父场景降为远景（低模/隐藏） | 双场景 | 模型 ≤ `FLY_MAX_MODEL_BYTES`（默认 20MB） |

飞行模式在以下情况**显式禁用并提示原因**（不静默降级成切换加载，因为用户会以为是 bug）：
- 场景模型文件 > `FLY_MAX_MODEL_BYTES`
- 目标场景没有 anchor 变换且与父场景世界坐标原点重合（飞过去看不出任何变化，反而像没响应）
- 设备判定为低显存（`navigator.deviceMemory <= 4`，不支持该 API 时按不支持处理，不禁用）

### 5.2 局部坐标 → 世界坐标

每个场景只有一个「本场景局部坐标系」。子场景通过 `anchor_*` 变换嵌入父场景：

```
worldMatrix(scene) =
    identity                                    , scene 为根
    worldMatrix(parent) × anchorMatrix(scene)   , 其它

anchorMatrix(scene) = T(anchor_x, anchor_y, anchor_z)
                    × Rz(anchor_rz) × Ry(anchor_ry) × Rx(anchor_rx)
                    × S(anchor_scale)
```

- 旋转顺序固定 **Z→Y→X**（内旋，与 three.js `Object3D.rotation` 默认的 `'XYZ'` 欧拉序在数学上等价，
  实现时直接 `object.rotation.set(rx, ry, rz)` + `object.scale.setScalar(anchor_scale)` + `object.position.set(...)`，
  由 three.js 内部完成同序合成，不手写矩阵）。
- **锚点坐标 `local_x/y/z` 一律在本场景局部坐标系内**，不存世界坐标。这样整栋楼平移一次
  （改楼层的 anchor），楼上所有锚点自动跟着走，不需要逐条改点位——这是分块建模的唯一正确姿势。
- 世界坐标链由前端 `SceneManager` 沿 `parent_id` 递归合成（场景树最深 4 级，递归成本可忽略）。

### 5.3 切换状态机

```
idle ──enter(sceneId)──► loading ──成功──► ready ──leave──► disposing ──► idle
                            │                                        │
                            └──失败（404/网络/解析）──► failed ───────┘
                                                        │
                                                        └─重试─► loading
```

**每一次状态迁移都必须让 UI 可见**：`loading` 显示进度（模型字节下载进度 + 解析阶段），
`failed` 显示具体原因 + 重试按钮，`disposing` 期间禁止再次切换（避免 GPU 资源竞态）。

### 5.4 内存红线（这是这类页面最常见的生产事故）

three.js **不会**自动回收 GPU 资源。`THREE.Cache` 只缓存 HTTP 响应，**不管显存**。因此 `SceneManager` 必须：

1. `scene.traverse()` 收集全部 `geometry` / `material` / `material.map` 等贴图，逐个 `dispose()`；
2. 清空 `THREE.Cache.enabled = false`（或按需 `Cache.clear()`），避免同一 URL 的大模型被长期 pin 在内存；
3. `renderer.dispose()` 只在页面卸载时调用（不要每次切场景都重建 renderer，WebGL 上下文重建很贵且易泄漏）；
4. 释放后把引用置 `null`，让 GC 能回收 JS 侧对象；
5. 开发期以 `renderer.info.memory.{geometries,textures}` 做自检：切 10 次场景后这两个数字必须回到基线附近，
   超出 `MEMORY_LEAK_GEOMETRY_THRESHOLD = 20` 即在控制台 WARN（不阻断，但要能被看见）。

---

## 6. 接口契约

> 网关 `Path=/iot/**` + `StripPrefix=1` ⇒ 服务内路径为 `/twin/**`，客户端调用 `/iot/twin/**`。
> 全部端点返回 `R<T>`（模型内容下载例外，见 §6.5「流式下载响应」）。

### 6.1 场景树

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/iot/twin/scenes` | `iot:twin:list` | 全量场景列表（租户内，按 `parentId`+`sort` 排序；前端组树） |
| GET | `/iot/twin/scenes/{id}` | `iot:twin:list` | 单场景详情（含模型资产摘要） |
| POST | `/iot/twin/scenes` | `iot:twin:create` | 新建场景 |
| PUT | `/iot/twin/scenes/{id}` | `iot:twin:update` | 编辑场景 |
| DELETE | `/iot/twin/scenes/{id}` | `iot:twin:delete` | 删除场景（有子场景或有点位时**拒绝**） |

**`GET /iot/twin/scenes` 响应**（`List<IotTwinSceneResp>`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 场景 ID（Long 字符串化） |
| `sceneCode` | string | 场景编码（租户内唯一） |
| `sceneName` | string | 名称 |
| `parentId` | string | 父场景 ID；根场景为 `"0"` |
| `parentIdName` | string | `@RefText("twinScene")` 派生的父场景名（根场景为空） |
| `sceneType` | string | `park`/`building`/`floor`/`room` |
| `modelAssetId` | string \| null | 模型资产 ID |
| `modelAssetIdName` | string | `@RefText("twinModelAsset")` 派生（默认 suffix 是 `Name`，故派生名 = 原字段名 + `Name`） |
| `anchorX/Y/Z` `anchorRx/Ry/Rz` `anchorScale` | string | BigDecimal 全局序列化为字符串 |
| `viewJson` | string \| null | 视角配置 JSON 原文 |
| `sort` | number | 排序 |
| `pointCount` | number | 本场景点位数（一次 `GROUP BY` 统计，不逐行查） |
| `childCount` | number | 直接子场景数（同上） |
| `remark` | string | 备注 |
| `createTime` | string | 创建时间 |

**`POST/PUT /iot/twin/scenes` 请求**（`IotTwinSceneReq`）：

```jsonc
{
  "sceneCode": "B1-F3",              // 必填，^[A-Za-z0-9_-]{1,64}$，租户内唯一
  "sceneName": "1 号楼 3 层",         // 必填，≤128
  "parentId": "3201",                // 可空（null=根）；非空时必须存在且不是自己/自己的后代
  "sceneType": "floor",              // 必填，park|building|floor|room
  "modelAssetId": "5201",            // 可空；非空时资产必须存在且属于本租户
  "anchorX": 0, "anchorY": 3.2, "anchorZ": 0,
  "anchorRx": 0, "anchorRy": 0, "anchorRz": 0,
  "anchorScale": 1,                  // 必须 > 0
  "viewJson": "{\"camera\":{...}}",  // 可空；非空时必须是合法 JSON 且 ≤ 16KB
  "sort": 0,
  "remark": ""
}
```

**校验规则**（服务侧，全部抛 `BusinessException`，错误信息说人话）：
1. `sceneCode` 租户内唯一（编辑时排除自身）；
2. `parentId` 存在且与本场景不同；沿父链上溯（`MAX_SCENE_DEPTH = 4` 层封顶，同时防历史脏数据成环），
   遇到自身即拒绝「场景不能挂到自己的后代下」；
3. `parentId` 存在时 `depth(parent) + 1 <= MAX_SCENE_DEPTH`，否则拒绝并给出实际深度；
4. `modelAssetId` 存在且属于本租户（跨租户按「不存在」处理，不泄露存在性）；
5. `anchorScale > 0`；
6. `viewJson` 合法 JSON 且 ≤ `MAX_VIEW_JSON_BYTES = 16 * 1024`。

**删除规则**：存在子场景 ⇒ 拒绝「请先删除子场景」；存在点位 ⇒ 拒绝「请先删除本场景的点位」。
**不做级联删除**——级联删掉用户几个月的配置是不可逆事故，必须让用户显式确认每一层。

### 6.2 点位与属性绑定

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/iot/twin/scenes/{sceneId}/points` | `iot:twin:list` | 本场景全部点位（含绑定，一次批量取回） |
| POST | `/iot/twin/scenes/{sceneId}/points` | `iot:twin:create` | 新增锚点（可同时带绑定） |
| PUT | `/iot/twin/scenes/{sceneId}/points/{pointId}` | `iot:twin:update` | 编辑锚点 + **整体替换**其绑定 |
| DELETE | `/iot/twin/scenes/{sceneId}/points/{pointId}` | `iot:twin:delete` | 删除锚点（连同其绑定） |

**`GET .../points` 响应**（`List<IotTwinPointResp>`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 点位 ID |
| `sceneId` | string | 所属场景 |
| `deviceId` | string | 设备 ID |
| `deviceIdName` | string | `@RefText("device")` 派生的设备名（设备已删除时为空，页面回落显示裸 ID） |
| `nodeName` | string \| null | 绑定的模型节点名 |
| `localX/Y/Z` `normalX/Y/Z` | string | BigDecimal 字符串 |
| `icon` | string \| null | 图标标识 |
| `labelVisible` | boolean | 是否常显标签 |
| `sort` | number | 排序 |
| `bindings` | `BindingResp[]` | 属性绑定（按 `sort` 升序） |

**`BindingResp`**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 绑定 ID |
| `serviceIdentifier` | string | 服务标识 |
| `propertyIdentifier` | string | 属性标识 |
| `propertyId` | string \| null | 属性主键缓存（可能悬空） |
| `propertyName` | string | 属性显示名（按标识解析；解析不到时回落标识本身） |
| `dataType` | string \| null | 物模型数据类型（解析不到时为空） |
| `unit` | string \| null | 单位（`unit_override` 优先，否则物模型单位） |
| `displayType` | string | `value`/`gauge`/`switch`/`color`/`sparkline` |
| `displayRole` | string | `primary`/`secondary` |
| `decimals` | number \| null | 小数位 |
| `unitOverride` | string \| null | 单位覆盖 |
| `sort` | number | 排序 |
| `stale` | boolean | **该绑定已失效**（属性在物模型里已不存在）。页面必须显著标红并提示「请重新选择属性」，不得静默丢弃 |

**`POST/PUT` 请求**（`IotTwinPointReq`）：

```jsonc
{
  "deviceId": "4101",                 // 必填，设备必须存在且属于本租户
  "nodeName": "Pump_03",              // 可空，≤255
  "localX": 1.5, "localY": 0.2, "localZ": -2.1,
  "normalX": 0, "normalY": 1, "normalZ": 0,
  "icon": "pump",                     // 可空，≤64
  "labelVisible": true,
  "sort": 0,
  "remark": "",
  "bindings": [                       // 必填（可为空数组）；≤ 12 条
    {
      "serviceIdentifier": "Environment",
      "propertyIdentifier": "temperature",
      "displayType": "value",
      "displayRole": "primary",
      "decimals": 1,
      "unitOverride": null,
      "sort": 0
    }
  ]
}
```

**绑定校验规则**（每条都必须显式拒绝，不静默忽略）：
1. `bindings` 非 `null`（漏传 vs 传空数组语义不同：`null` 是调用方漏字段，必须报错）；
2. 每条 `(serviceIdentifier, propertyIdentifier)` 在**该设备绑定产品的物模型**里必须存在，
   否则拒绝并列全不存在的组合（一次列全，不让用户改一条试一次）；
3. 同一请求内 `(serviceIdentifier, propertyIdentifier)` 不得重复；
4. `displayType`/`displayRole` 用枚举白名单校验（未知值拒绝，不做「不认识就放过」）；
5. `decimals` ∈ [0, 6]；`unitOverride` ≤32；
6. 条数 ≤ `MAX_BINDINGS_PER_POINT = 12`；
7. `primary` 至多一条（多了页面不知道标签该显示哪个值）。

### 6.3 设备可用属性清单（配置页用，避免 N 次请求）

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/iot/twin/devices/{deviceId}/properties` | `iot:twin:list` | 该设备（经产品）可绑定的全部属性 + 是否已建采集映射 |

**响应**（`List<IotTwinDevicePropertyResp>`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `serviceId` | string | 服务主键（Long 字符串） |
| `serviceIdentifier` | string | 服务标识 |
| `serviceName` | string | 服务名称 |
| `propertyId` | string | 属性主键 |
| `propertyIdentifier` | string | 属性标识 |
| `propertyName` | string | 属性名称 |
| `dataType` | string | 物模型数据类型（9 类之一） |
| `accessMode` | string | `R`/`W`/`RW` |
| `unit` | string \| null | 单位 |
| `minValue`/`maxValue` | string \| null | 量程（`gauge` 展示形态默认用） |
| `mapped` | boolean | 该属性在本设备上**是否已建 `iot_point_mapping`**。为 `false` 时属性永远拿不到实时值，配置页必须给出「该属性未配置采集点位，绑定后不会有数据」的提示 |

> **为什么这个端点必须有**：设备的属性来自它的产品（`device.productId → product.services → properties`），
> 前端自己拼需要 3 次请求且要处理「设备未绑产品」的空态。更关键的是 `mapped` 这个字段——
> 它是「绑了也白绑」的唯一可见信号（§11 风险 R2）。

设备未绑定产品 ⇒ 返回**空数组**（不是错误），并由前端展示「该设备未绑定产品，无法配置属性」。
设备不存在 / 不属于本租户 ⇒ `409 设备不存在`。

### 6.4 运行态场景快照

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/iot/twin/scenes/{sceneId}/runtime` | `iot:twin:list` | 一次拉齐本场景全部点位的运行态 |

**响应**（`IotTwinRuntimeResp`）：

```jsonc
{
  "sceneId": "5001",
  "serverTime": "2026-10-21 10:30:00",
  "pointCount": 42,
  "truncated": false,          // 点位超过 MAX_POINTS_PER_SCENE 时为 true（理论上不可达，防御式字段）
  "points": [
    {
      "pointId": "5002",
      "deviceId": "4101",
      "deviceCode": "PUMP-03",
      "deviceName": "3 号泵",
      "onlineStatus": "online",   // online | offline | unknown
      "status": 1,                // 启停位 1 启用 0 停用
      "lastSeenAt": "2026-10-21 10:29:58",
      "alertCount": 2,            // 活动告警数
      "values": {                 // 键 = propertyIdentifier（规范坐标）
        "temperature": { "value": "26.5", "quality": "good", "ts": "1760999998000", "stale": false },
        "pressure":    { "value": null,   "quality": null,   "ts": null,        "stale": false }
      }
    }
  ]
}
```

**语义边界（必须写进实现注释与页面空态）**：
- `values` 里没有某个属性 ⇒ 该属性**从未上报**（不是 0、不是错误）；`value=null` + `quality=null` ⇒ 同上。
- `onlineStatus=unknown` ⇒ 平台还没拿到过该设备的连接事件，**不代表离线**。
- 一次请求内所有设备的 `values` 来自**同一次 Redis pipeline**，不保证彼此是同一毫秒的快照（如实标注
  `serverTime` 为服务端开始组装的时刻，不宣称"原子快照"）。

### 6.5 模型资产

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/iot/twin/model-assets` | `iot:twin:list` | 资产列表（含被多少场景引用） |
| POST | `/iot/twin/model-assets/upload` | `iot:twin:create` | 上传模型文件（multipart，字段名 `file`） |
| POST | `/iot/twin/model-assets/external` | `iot:twin:create` | 登记外链模型 |
| GET | `/iot/twin/model-assets/{id}/content` | `iot:twin:list` | **流式下载**模型（仅 `asset_type=upload`；`external` 返回 409 并提示直接用外链） |
| DELETE | `/iot/twin/model-assets/{id}` | `iot:twin:delete` | 删除资产（被场景引用时拒绝） |

**`GET .../model-assets` 响应**（`List<IotTwinModelAssetResp>`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 资产 ID |
| `assetName` | string | 名称（租户内唯一） |
| `assetType` | string | `upload` \| `external` |
| `modelFormat` | string | `glb` \| `gltf` |
| `externalUrl` | string \| null | 外链地址 |
| `contentUrl` | string | **页面实际使用的模型地址**：`upload` ⇒ `/iot/twin/model-assets/{id}/content`；`external` ⇒ 外链原文。页面不需要自己判断类型去拼路径 |
| `originalName` | string \| null | 原始文件名 |
| `fileSize` | number \| string \| null | 字节数。**后端 `Long` 经全局 Jackson（`ypbin.json.write-big-number-as-string=true`）序列化为字符串** ⇒ 真实返回是 `"1048576"`。消费方必须用 `toBackendNumber` 显式转数（直接比较会变成字符串比较） |
| `contentHash` | string \| null | SHA-256（前 16 位展示即可，全量返回） |
| `nodeNames` | string[] | 节点名快照（解析失败时为空数组） |
| `sceneCount` | number | 被多少个场景引用（`GROUP BY` 一次统计） |
| `createTime` | string | 创建时间 |

**上传请求约束**：

| 项 | 值 | 理由 |
|---|---|---|
| 字段名 | `file`（multipart） | 与 `/system/file/upload`、`/devices/import` 一致 |
| 名称 | 表单字段 `assetName`（可空，空则用文件名去掉扩展名） | 用户不必先改名 |
| 允许扩展名 | `.glb` / `.gltf` | —— |
| 字节上限 | `DeviceImportLimits` 之外独立常量 `MAX_MODEL_BYTES = 64 * 1024 * 1024`（64MB） | 建筑模型常态 5~30MB；用独立常量而不是复用导入的 2MB |
| **魔数校验（必做）** | `.glb` 前 4 字节必须是 ASCII `glTF`（`0x46546C67`）；`.gltf` 必须是 UTF-8 JSON 且顶层含 `asset` 对象 | **只校验扩展名等于没有校验**：改个后缀就能塞任意文件进存储，进而成为可下载的任意内容载体 |
| glTF 版本 | 从头部读取 `version`，必须为 `2` | glTF 1.x 与 3.x 前身格式 three.js 不支持，早失败早报错 |
| SHA-256 | 服务端计算并落库 | 判重（同租户同 hash 提示「已存在同名内容模型」）与替换校验 |

**外链登记约束**：
- `externalUrl` 必须是 `http://` 或 `https://` 绝对地址，`≤1024` 字符；
- **禁止** `javascript:`/`data:`/`file:` 等 scheme（前端会用 `fetch` 拉取，`data:` 可绕过大小限制）；
- 服务端**不主动拉取**外链（避免 SSRF），只做格式校验；连通性由页面「试加载」按钮暴露。

**流式下载响应**：
- `Content-Type`：`model/gltf-binary`（`.glb`）或 `model/gltf+json`（`.gltf`）；
- `Content-Length`：已知字节数；
- `Content-Disposition: inline`（前端 `fetch` 读流，不需要附件下载语义）；
- `Cache-Control: private, max-age=3600`（同一模型反复进出场景不重复拉取；用 `private` 因为带鉴权）；
- `ETag`：`contentHash`，支持 `If-None-Match` 返回 304（切场景时省流量）；
- **跨租户**：查不到即 `409 模型不存在`（不泄露存在性，与 `D0.5` 同口径）。

### 6.6 权限码与菜单

4 个权限码（全部 `platform_only=0`，租户可用）：

| 权限码 | 用途 |
|---|---|
| `iot:twin:list` | 读场景树/点位/运行态/模型资产/模型下载/设备属性清单 |
| `iot:twin:create` | 新建场景、新增点位、上传模型、登记外链 |
| `iot:twin:update` | 编辑场景、编辑点位与绑定 |
| `iot:twin:delete` | 删除场景、删除点位、删除模型资产 |

菜单：`3210` = 数字孪生页面（`pid=3204`，`path=/iot/twin`，`component=/iot/twin/index`，
`auth_code=iot:twin:list`，`icon=carbon:cube`，`sort=16`）；按钮 `321001/321002/321003` 对应
create/update/delete。

> **id 段选择依据**：`3200`~`3209` 是 IoT 页面菜单，`320001`~`320024`/`320101`/`320201`/`320301`/
> `3207xx`/`3209xx` 是按钮段，`3310`/`3320` 是平台模块目录。`3210` 与 `321001~` 经查仓内 SQL
> **未被占用**（`007-iot-data.sql` 与 `migration/*.sql` 全量核对）。
> `3210` 的 `platform_only=0` ⇒ 必须**同时**进 `sys_role_menu` 与 `sys_template_menu`
> （`IotMaintenanceAdminGateTest` 口径），且不进 `PLATFORM_LEVEL_MENUS` 清单。

### 6.7 与既有 `SysFileController` 的关系（必须解释清楚）

`SysFileController` 类上有 `@PlatformAccess`（`PlatformAccessAspect` 校验：非平台用户抛 403）。
**租户用户调 `/system/file/upload` 会 403**——因此孪生模型**不能**复用该通道。

另外经核实：仓库内**不存在**任何 `ypbin.storage.*` 配置，而 `StorageAutoConfiguration` 在
`local` 列表为空时不会注册任何存储策略 ⇒ `StorageRouter#route(null)` 抛
「未指定存储平台且未配置默认平台」。也就是说 **`/system/file/upload` 在当前仓的部署形态下不可用**
（这是既有缺口，不在本次修复范围，但孪生不能踩进去）。

因此孪生的上传通道**自建并自管**：

- `ypbin-service/ypbin-iot/pom.xml` 新增 `ypbin-starter-storage` 依赖（iot pom 是本仓新增文件，不受
  `SYNC.md` 白名单约束）；
- `deploy/nacos/ypbin-iot.yaml`（本仓新增文件，同样不受白名单约束）新增：

```yaml
ypbin:
  storage:
    enabled: true
    default-platform: twin
    # 孪生模型单文件上限（64MB）；业务侧 MAX_MODEL_BYTES 与之同值，两层都要卡
    max-file-size: 67108864
    local:
      - platform: twin
        base-path: /data/ypbin/twin
        # 不用 domain 生成对外 URL：模型一律经 API 流式下载（鉴权 + 租户隔离由 API 层承担）
        domain: ""
```

- `deploy/docker-compose.yml` 给 `ypbin-iot` 服务加卷：`./data/twin:/data/ypbin/twin`
  （沿用既有白名单条目「新增 ypbin-iot 服务块」，需在 `SYNC.md` 该条目补一行说明触发原因；
  **不新增白名单条目数**）。
- 上传路径的体积上限由 nginx 承担：`deploy/nginx-microservice.conf` 的 `/api/` location 当前是 `3m`，
  必须在它**之前**加一条更精确的 location：

```nginx
    # 数字孪生模型上传：单文件上限 64MB（与 iot 侧 MAX_MODEL_BYTES 同值）。
    # ⚠️ 必须是 `location =` 精确匹配，**不能**用正则 `location ~`：nginx 硬规则——
    #   正则 location 里的 proxy_pass 不允许带 URI 部分，写成 `proxy_pass http://up/;`
    #   会直接 `nginx -t` emerg，整个前端容器起不来（已用 nginx:alpine 实测复现）。
    #   而本处必须带 URI 剥掉 /api 前缀。
    # 本 location 不放 add_header ⇒ 继承 server 级安全头（nginx add_header 只在 location 自己写了才不继承）。
    location = /api/iot/twin/model-assets/upload {
        client_max_body_size 64m;
        proxy_pass http://ypbin-gateway:18080/iot/twin/model-assets/upload;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
```

> `deploy/nginx-microservice.conf` **已是白名单条目**（SSE 三行的既有登记），改它属既有条目内的追加，
> 需在 `SYNC.md` 该条目补一行「模型上传上限」说明。**不会**新增条目数。

**Spring 侧 multipart 上限**：Spring Boot 默认 `max-file-size=1MB`，必须显式放大，否则会被
`MaxUploadSizeExceededException` 拦下（starter 的 `GlobalExceptionHandler` 会转成
`R.fail(PAYLOAD_TOO_LARGE, "上传文件超出大小限制，请压缩后重试")`）。写入 `ypbin-iot.yaml`：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 64MB
      max-request-size: 66MB
```

---

## 7. 后端实现要点（完整源码见 `DIGITAL-TWIN-BACKEND.md`）

### 7.1 文件清单

```
ypbin-service-api/ypbin-iot-api/src/main/java/cn/ypbin/admin/iot/
  enums/TwinSceneType.java                      场景类型枚举
  enums/TwinAssetType.java                      模型来源枚举
  enums/TwinDisplayType.java                    展示形态枚举
  enums/TwinDisplayRole.java                    展示角色枚举
  entity/IotTwinModelAsset.java
  entity/IotTwinScene.java
  entity/IotTwinPoint.java
  entity/IotTwinPointBinding.java
  twin/TwinLimits.java                          全部上限常量（唯一来源）
  twin/TwinSceneTransform.java                  锚点变换的校验口径（纯函数，可单测）
  model/req/IotTwinSceneReq.java
  model/req/IotTwinPointReq.java
  model/req/TwinBindingReq.java
  model/req/IotTwinModelExternalReq.java
  model/resp/IotTwinSceneResp.java
  model/resp/IotTwinPointResp.java
  model/resp/TwinBindingResp.java
  model/resp/IotTwinRuntimeResp.java
  model/resp/TwinRuntimePointResp.java
  model/resp/TwinRuntimeValueResp.java
  model/resp/IotTwinModelAssetResp.java
  model/resp/IotTwinDevicePropertyResp.java

ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/
  mapper/IotTwinModelAssetMapper.java
  mapper/IotTwinSceneMapper.java
  mapper/IotTwinPointMapper.java
  mapper/IotTwinPointBindingMapper.java
  service/IotTwinSceneService.java
  service/IotTwinPointService.java
  service/IotTwinModelAssetService.java
  service/IotTwinRuntimeService.java
  service/impl/IotTwinSceneServiceImpl.java
  service/impl/IotTwinPointServiceImpl.java
  service/impl/IotTwinModelAssetServiceImpl.java
  service/impl/IotTwinRuntimeServiceImpl.java
  service/impl/TwinBindingResolver.java         绑定 ↔ 物模型属性的批量解析（纯逻辑 + 批量查库）
  service/impl/TwinModelFileGuard.java          模型文件内容校验（魔数 / glTF 版本 / 节点名归一）
  controller/IotTwinSceneController.java
  controller/IotTwinPointController.java
  controller/IotTwinDevicePropertyController.java  设备可绑定属性（配置页用）
  controller/IotTwinModelController.java
  controller/IotTwinRuntimeController.java
  provider/TwinRefTextProviders.java            @RefText 的 twinScene / twinModelAsset 两类
  values/LatestValueQueryService.java           **修改**：新增 listLatestBatch + 抽取 mergeDeviceEntries
```

测试（`ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/`）：

```
  twin/TwinSceneTransformTest.java              锚点变换校验口径
  twin/TwinModelFileGuardTest.java              魔数/版本校验（伪装成 .glb 的文件必须被拒）
  twin/TwinPermissionCodeGateTest.java          4 个权限码同时登记在 007 与 migration
  twin/TwinMenuGrantGateTest.java               3210/321001-3 同时进两张授权表、不进平台级清单
  service/impl/IotTwinSceneServiceImplTest.java 父链成环/超深/删除保护/资产归属
  service/impl/IotTwinPointServiceImplTest.java 绑定校验（重复/primary/枚举/设备唯一）
  service/impl/TwinBindingResolverTest.java     批量（不 N+1）、stale 判定、自愈回写
  values/LatestValueQueryServiceBatchTest.java  单次 pipeline、空集合短路、Redis 故障语义
```

> `LatestValueQueryService` 是**既有文件**，属本仓新增（IoT 域），不受白名单约束。

### 7.2 代码铁律落点（对照既有门禁逐条）

| 门禁 | 本设计如何满足 |
|---|---|
| `SourceConventionTest` 禁内联 FQN | 全部类顶部 import；正文只写简单类名 |
| 禁 `Collections.emptyXxx` | 一律 `List.of()` / `Map.of()` |
| 实体禁 `@Data` | 全部 `@Getter @Setter` + `@TableName` |
| 禁 `Enum.ordinal()` | 4 个枚举均带 `code`/`desc` + `of(String)` |
| 禁循环内 DB/RPC | 绑定解析、属性解析、点位统计全部**先批量取数再内存组装**（§7.3） |
| 实体须继承 Base 类 | 4 个实体继承 `TenantBaseEntity` |
| `CodingRulesTest` 禁 `printStackTrace` | 全部 `log.error("...", ex)` |
| `@Transactional` 显式 `rollbackFor` | 写方法一律 `@Transactional(rollbackFor = Exception.class)` |
| 禁字段注入 | 构造器注入（`@RequiredArgsConstructor` + `final`） |
| service/impl 不得依赖 provider | 孪生 service 不依赖 `provider` 包；`TwinRefTextProviders` 只被框架加载 |
| `IotMapperAnnotationXmlGateTest` | 每个 `@Select("<script>...")` 都有配对的 `</script>` |
| `IotPermissionCodeGateTest` | 4 个权限码同时进 `007` 与 migration |
| `IotMaintenanceAdminGateTest` | `3210`/`321001-3` 同时进 `sys_role_menu` 与 `sys_template_menu` |
| 类级 Javadoc `@author wenbin` + `@since 2026-10-21` | 全部新类 |
| 注释不提前端 | 后端注释只写自身职责与数据语义 |

### 7.3 三个关键算法

**① 绑定解析：批量、可自愈、不 N+1**

```java
// TwinBindingResolver 的核心口径（伪代码，完整实现见 BACKEND 文档 §5.3）
Map<Long, ResolvedProperty> resolve(List<Long> propertyIds,
                                    List<BindingKey> keys) {
    // 1. 一次 IN 查 iot_property（含 service_id）
    // 2. 一次 IN 查 iot_service 拿到 service.service_id（业务标识）
    // 3. 内存里按 (serviceIdentifier, propertyIdentifier) 建索引
    // 4. 命中不到 ⇒ 标记 stale=true（不抛异常：历史绑定失效是正常状态，页面要能看到并重配）
    // 5. 命中不到但能用 (serviceIdentifier, propertyIdentifier) 兜底解析到的 ⇒ 回写 property_id（自愈）
}
```

**② 场景运行态：一次 pipeline 取全部最新值**

```java
// LatestValueQueryService#listLatestBatch（新增方法）
public Map<Long, List<LatestValueResp>> listLatestBatch(List<Long> deviceIds) {
    if (deviceIds == null || deviceIds.isEmpty()) {
        return Map.of();                       // IN 查询空集合短路
    }
    // 1. 一次 selectByIds 拿设备（同时完成「属于本租户」的校验）
    // 2. 一次 loadCoordinates(deviceIds) 拿规范坐标索引
    // 3. redisTemplate.executePipelined 批量 HGETALL（**不是**循环 N 次 HGETALL）
    // 4. 每台设备内部沿用既有 listLatest 的「两种坐标形态归一 + ts 取新」裁决（抽成私有方法复用）
    // 5. Redis 不可用 ⇒ 返回每台设备的空列表（与单设备语义一致：没有最新值不是故障）
}
```

**③ 场景树深度与环校验**

```java
// IotTwinSceneServiceImpl#validateParent
// 1. parentId 为空 ⇒ 根，depth=1
// 2. 非空 ⇒ requireScene(parentId)；沿 parentId 上溯，最多 MAX_SCENE_DEPTH 步：
//    - 遇到当前 id ⇒ 「场景不能挂到自己的后代下」
//    - 到 0 为止 ⇒ 得到 depth
// 3. depth + 1 > MAX_SCENE_DEPTH ⇒ 拒绝并报实际深度
// 4. 上溯步数触顶仍未到 0 ⇒ 历史脏数据成环，抛「场景层级数据异常（父子关系成环）」
```

---

## 8. 前端实现要点（完整源码见 `DIGITAL-TWIN-FRONTEND.md`）

### 8.1 依赖与打包

```jsonc
// apps/web-antd/package.json 新增 dependencies
"three": "catalog:"          // pnpm-workspace.yaml catalog 新增 "three": "^0.186.1"

// devDependencies
"@types/three": "catalog:"   // catalog 新增 "@types/three": "^0.186.0"
```

事实依据（2026-10-21 查 npm registry 一手数据）：
- `three` 当前 `0.186.1`，**MIT**，`types/typings` 字段均为空 ⇒ **必须**配 `@types/three`；
- `@types/three` 当前 `0.186.0`，**MIT**，与 three 版本号同步（锁版本时**成对锁**）；
- addons 走 `three/addons/*` 映射（`exports` 里有 `"./addons/*": "./examples/jsm/*"`），
  已核实 `loaders/GLTFLoader.js`、`controls/OrbitControls.js`、`controls/TransformControls.js`、
  `renderers/CSS2DRenderer.js`、`libs/meshopt_decoder.module.js`、`libs/draco/gltf/draco_decoder.wasm`
  在 `0.186.1` 下均存在（HTTP 200）。

**动态 import 策略**（硬约束 2）：

```ts
// 页面首屏不加载 three；只有进入 /iot/twin 且场景就绪后才拉 three chunk
const { TwinSceneManager } = await import('#/three/twin/scene-manager');

// vite 侧：three 走独立 chunk，避免打进 index 主包
// vite.config.ts 的 build.rollupOptions.output.manualChunks 增加
//   'three-core': ['three']
//   （addons 随 scene-manager 动态 chunk 自然分包，不再单独指定）
```

### 8.2 前端结构

```
apps/web-antd/src/
  api/iot/twin.ts                           类型 + 全部请求函数（并入 api/iot/index.ts 导出）
  three/twin/                               引擎层（**零 Vue 依赖**，可单测）
    scene-manager.ts                        场景生命周期：load/dispose/switch、世界坐标链
    model-loader.ts                         GLTFLoader + DRACO/KTX2/Meshopt 装配、进度、节点名提取
    anchor-layer.ts                         锚点渲染（CSS2D 标签 + Sprite 图标）、着色、闪烁
    picker.ts                               射线拾取（含 BVH 加速）、拖拽微调（TransformControls）
    camera-rig.ts                           默认视角、聚焦飞行、重置
    disposal.ts                             GPU 资源递归回收（geometry/material/texture）
    runtime-store.ts                        运行态值合并、rAF 节流、SSE 事件接入
    types.ts                                引擎层公共类型
  views/iot/twin/
    index.vue                               页面编排：左侧场景树 + 中间画布 + 右侧属性面板
    data.ts                                 场景树列定义、表单 schema
    modules/scene-tree.vue                  场景树（新建/编辑/删除/下钻/面包屑）
    modules/scene-form.vue                  场景表单抽屉
    modules/twin-canvas.vue                 three 画布宿主（挂载/卸载/切换/错误态）
    modules/point-panel.vue                 点位列表 + 新增/编辑入口
    modules/point-form.vue                  点位表单抽屉（含属性绑定多选）
    modules/model-panel.vue                 模型资产管理（上传/外链/删除/预览）
    modules/binding-editor.vue              属性绑定编辑器（形态/角色/精度/单位）
    modules/runtime-legend.vue              运行态图例（在线/离线/告警）
    modules/device-drawer-link.ts           点击锚点 → 跳设备详情（深链，复用既有约定）
    composables/use-twin-scene.ts           场景树数据域
    composables/use-twin-runtime.ts         运行态数据域（快照 + SSE 合流）
    composables/use-twin-editor.ts          配置态交互状态机（选择/拾取/拖拽/保存）
```

### 8.3 三条必须做对的交互

**① 拾取 → 拖拽微调 → 保存**
- 点击模型表面：`Raycaster` 取第一个交点 ⇒ 锚点坐标 = `intersect.point` 转到**场景局部坐标**，
  法线 = `intersect.face.normal` 转到世界再转局部；
- 拾取必须用 `three-mesh-bvh`（`0.9.16`，MIT，peer `three>=0.159`）加速：建筑级模型原生
  `Raycaster` 是 O(三角形数)，几百万面时点击会卡顿数百毫秒；
- 已放置锚点可拖拽（`TransformControls` 的 `translate` 模式，仅 XZ 平面 + 吸附网格），
  **拖拽中禁用相机控制**（否则拖拽会带着相机一起转）；
- 保存是**显式动作**：全部改动先落在本地草稿（`use-twin-editor`），点「保存」才 PUT；
  切换场景/关闭页面前有草稿时弹确认。**不做自动保存**（坐标是精密配置，自动保存会把误拖写进库）。

**② 属性绑定的可发现性**
- 打开点位表单时，若设备**未绑定产品** ⇒ 直接提示「该设备未绑定产品，无法配置属性」并禁用绑定区；
- 绑定的属性 `mapped=false`（无采集点位）⇒ 每条旁边标「未配置采集」，
  并在表单顶部给一句「这些属性不会产生实时数据」；
- 已失效绑定（响应 `stale=true`）⇒ 标红 + 「属性已不存在，请删除后重新选择」，**不允许**静默保留。

**③ 实时值的节流**
- SSE `latest` 是**逐条**推送的。一个场景 500 个点位、每点位 1s 上报一次 ⇒ 峰值 500 事件/秒。
  若每条都触发 Vue 响应式更新，会直接把主线程打满。
- 做法：`runtime-store` 收到事件后**只写入一个 Map**（不触发响应式），用 `requestAnimationFrame`
  合并（最多每帧一次），并把「本帧有变化的锚点」批量提交给 `anchor-layer` 更新文本/颜色。
- `device-status` / `alert` 走高延迟去抖（1200ms）重拉快照，与仪表盘 `useDashboardRealtime` 同口径。

### 8.4 URL 深链

沿用设备详情的既有约定（`router.replace({ query: { sceneId } })`）：

- 进入场景 ⇒ `?sceneId=xxx`；离开 ⇒ 移除；
- 带 `?sceneId=` 刷新 / 前进后退 ⇒ 自动进入对应场景；
- `sceneId` 不存在 ⇒ **仍然按该 id 尝试加载**，由画布给出「场景不存在」错误态 + 返回根场景按钮，
  **不静默跳回根场景**（用户会以为自己点错了）；
- 支持 `?sceneId=xxx&pointId=yyy`：进入后聚焦该锚点并打开其点位面板。

---

## 9. 测试计划

### 9.1 后端单测（新增，必须全部通过）

| 测试类 | 覆盖 |
|---|---|
| `TwinSceneTransformTest` | 锚点变换口径：scale ≤ 0 拒绝、旋转合成顺序、世界坐标链（4 级） |
| `IotTwinSceneServiceImplTest` | 父链校验（自引用/成环/超深度/父不存在）、删除保护（有子/有点位）、`sceneCode` 唯一 |
| `IotTwinPointServiceImplTest` | 绑定校验（属性不存在/重复/`primary` 多于一条/超 12 条/`displayType` 非法）、空 `bindings` 传 null 报错、`deviceId` 跨租户 |
| `TwinBindingResolverTest` | 批量解析（不 N+1：verify mapper 只调 2 次）、`stale` 判定、按标识自愈回写 |
| `LatestValueQueryServiceBatchTest` | batch 与单设备结果一致、空集合短路、Redis 不可用返回空列表、pipeline 只发一次 |
| `IotTwinModelAssetServiceImplTest` | 魔数校验（假 glb 拒收）、扩展名白名单、大小上限、SHA-256 计算、被引用时删除拒绝 |
| `IotTwinRuntimeServiceImplTest` | 一次组装（不 N+1）、`truncated` 语义、设备已删除时的点位处理 |
| `TwinPermissionCodeGateTest` | 4 个权限码在 007 与 migration 同时登记（对齐 `IotPermissionCodeGateTest` 口径） |
| `TwinMenuGrantGateTest` | `3210`/`321001-3` 同时进 `sys_role_menu` 与 `sys_template_menu` |

### 9.2 后端门禁（既有，必须全绿）

```
mvn -pl ypbin-service/ypbin-iot -am test          # 单测 + 业务门禁
mvn -pl ypbin-architecture-tests test             # 架构门禁（需先建 iot 模块）
tools/check-iot-sql-equivalence.sh                # 006+007 与 migration 语句等价
```

### 9.3 前端单测（新增）

| 测试文件 | 覆盖 |
|---|---|
| `three/twin/scene-manager.test.ts` | 世界坐标链、切换状态机、dispose 调用次数（mock three） |
| `three/twin/disposal.test.ts` | 递归 dispose：geometry/material/texture 全覆盖、重复 dispose 幂等 |
| `three/twin/runtime-store.test.ts` | rAF 合并、去抖、SSE 事件分类、未知事件忽略 |
| `three/twin/picker.test.ts` | 坐标换算（世界↔局部）、法线归一、BVH 未装配时的回退 |
| `views/iot/twin/twin-config-validate.test.ts` | 场景表单校验与后端同口径、绑定校验、`stale` 展示 |
| `views/iot/twin/twin-deeplink.test.ts` | `?sceneId=` 打开/关闭/未知 id 保持尝试语义 |

### 9.4 手工验收（必须实测，不可用「应该能跑」代替）

1. 上传一个 20MB 建筑 glb → 出现在资产列表 → 建场景绑定 → 画布正确渲染；
2. 建 4 级场景树（园区/楼/楼层/房间）→ 下钻/返回 → 面包屑正确；
3. 楼层场景内拾取 10 个锚点并绑定属性 → 刷新页面 → 坐标与绑定完全回显；
4. 触发设备上报（或 mock）→ 画布数值实时变化；断开设备 → 状态变离线色；
5. 制造告警 → 锚点闪烁；确认告警 → 停止闪烁；
6. **切场景 10 次** → `renderer.info.memory.geometries/textures` 回到基线附近（§5.4）；
7. 上传伪造 glb（改扩展名）→ 明确报错，不落存储；
8. 租户 A 的 token 拉租户 B 的模型内容 → 409「模型不存在」，响应体不含 B 的任何字段。

---

## 10. 实施顺序（每阶段可独立验收）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1 数据与权限** | 4 表 DDL（006 + migration + rollback + 迁移清单）、4 权限码与菜单 SQL（007 + migration）、`TwinLimits`、4 枚举、4 实体、4 Mapper | `check-iot-sql-equivalence.sh` 通过；权限码/菜单门禁全绿 |
| **P2 场景与点位 CRUD** | 4 个 Req/Resp、场景 service（父链校验）、点位 service（绑定校验）、`TwinBindingResolver`、2 个 Controller、`TwinRefTextProviders` | 单测全绿；用 curl 走通「建树 → 加点位 → 回显」 |
| **P3 模型资产** | storage 配置 + pom 依赖 + compose 卷 + nginx location、上传（魔数/大小/SHA256）、外链登记、流式下载（含 ETag）、删除保护 | 手工上传 20MB glb 成功；伪造文件被拒；304 生效 |
| **P4 运行态** | `listLatestBatch`（pipeline）、`IotTwinRuntimeService`、运行态端点 | 批量与单设备结果一致；500 点位场景一次请求 < 300ms |
| **P5 前端引擎层** | three 依赖、`three/twin/*`（loader/manager/anchor/picker/disposal/runtime-store）、单测 | 引擎层单测全绿；内存自检通过 |
| **P6 前端页面** | api/iot/twin.ts、页面与 5 个模块、i18n（zh-CN + en-US）、深链、6 个测试 | `pnpm -F @vben/web-antd typecheck` + `vitest run` 全绿；手工验收 §9.4 |

> **P1→P4 是后端闭环，P5→P6 是前端闭环**。P3 依赖基础设施（卷/nginx），
> 若部署环境暂不具备，可先做「外链登记」通道，上传通道与 P3 其余部分同批完成——
> **不接受「上传功能先留空」**（那会留下一个用户点进去就报错的入口）。

---

## 11. 风险与主动提示

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| **R1** | **每个场景一个模型文件时，模型体积是最大成本** | 大模型首屏 10s+ | 上传时给 SHA-256 与体积提示；文档要求模型预压缩（Draco + KTX2）；前端显示下载进度；> 50MB 弹提示建议压缩 |
| **R2** | **没配采集点位的属性绑定了也不会有数据** | 用户以为孪生坏了 | §6.3 的 `mapped` 字段 + 配置页显式提示（三处） |
| **R3** | **TSL 重导入导致绑定失效** | 已有绑定变悬空 | 绑业务标识（§4.2）+ `stale` 可见 + 自愈回写 |
| **R4** | **反复切场景显存泄漏** | 页面越用越卡、最终崩溃 | §5.4 强制 dispose + 开发期内存自检阈值 |
| **R5** | **`/system/file/upload` 不可用（既有缺口）** | 若误复用会 403 或抛「未配置默认平台」 | 自建上传通道（§6.7），不复用该系统通道 |
| **R6** | **设备未绑产品 ⇒ 无属性可绑** | 配置页空态 | §6.3 返回空数组 + 页面给明确文案 |
| **R7** | **本机 5.8G 内存跑不了全量前端构建** | 本地无法完整验证前端 | three 走独立 chunk；前端验证用 `typecheck` + `vitest`（不跑全量 build），全量构建外委 CI |
| **R8** | **`iot_twin_point_binding.property_id` 悬空** | 联查拿到错数据 | 读取时 `LEFT JOIN` 复核 + `stale` 标记 + 自愈回写；**永不**把 `property_id` 当权威 |
| **R9** | **大屏投屏需求（免登录）** | 需另立只读分享链路，涉及 D0.5 安全决策 | 本期不做（§1.2），需求出现时单独立项评估 |

---

## 12. 未做 / 待决（如实登记）

1. **无免登录投屏**：需要「分享链接 + 只读 token + 租户隔离」三件套，涉及既有存在性不泄露决策（D0.5），
   必须单独立项，不在本期夹带。
2. **无管线/连线**（`iot_twin_link`）：属组态编辑器范畴（§1.2）。
3. **无历史回放**：本期只有实时；回放要接 IoTDB 时序查询 + 时间轴播放器，是独立特性。
4. **无移动端**：窄屏（< 1280px）给明确遮挡提示，不承诺可用。
5. **`node_names_json` 是辅助索引不是权威**：由前端解析 glb 后上报，服务端只做格式与长度校验。
   若前端解析失败则存空数组，不影响任何功能（只影响「按节点名批量预匹配」的便利性）。
6. **场景删除不级联**：见 §6.1，刻意如此。
