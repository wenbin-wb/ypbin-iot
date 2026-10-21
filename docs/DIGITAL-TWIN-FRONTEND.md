# 数字孪生（3D 场景）前端完整实现

> 本文是 [`DIGITAL-TWIN-DESIGN.md`](DIGITAL-TWIN-DESIGN.md) 的**前端源码级实现**，
> 接口契约以设计文档 §6 与 [`DIGITAL-TWIN-BACKEND.md`](DIGITAL-TWIN-BACKEND.md) 为准（三者必须逐字一致）。
>
> **交付原则**：所有文件都是**可直接落盘编译**的完整实现，不含 `TODO`、不含占位、不含「后续优化」注释。
> 全部遵循 `vben-ui-dev` skill 的 RED 铁律（`$t` 文案、`useVbenForm` schema、`useVbenVxeGrid`、
> `VbenTableAction`、字段同名、Long ID 字符串、不静默降级）。

## 0. 文件清单

### 0.1 新增

```
apps/web-antd/src/
  api/iot/twin.ts                       类型 + 全部请求函数
  three/twin/types.ts                   引擎层公共类型
  three/twin/disposal.ts                GPU 资源递归回收
  three/twin/model-loader.ts            GLTFLoader + DRACO/KTX2/Meshopt 装配
  three/twin/scene-manager.ts           场景生命周期 + 世界坐标链 + 切换状态机
  three/twin/camera-rig.ts              默认视角 / 聚焦飞行 / 重置
  three/twin/anchor-layer.ts            锚点渲染（CSS2D 标签 + Sprite 图标）+ 着色 + 闪烁
  three/twin/picker.ts                  射线拾取（BVH）+ 拖拽微调
  three/twin/runtime-store.ts           运行态值合并 + rAF 节流 + SSE 接入
  views/iot/twin/index.vue              页面编排
  views/iot/twin/data.ts                树列定义 + 表单 schema
  views/iot/twin/composables/use-twin-scene.ts
  views/iot/twin/composables/use-twin-runtime.ts
  views/iot/twin/composables/use-twin-editor.ts
  views/iot/twin/modules/twin-canvas.vue
  views/iot/twin/modules/scene-tree.vue
  views/iot/twin/modules/scene-form.vue
  views/iot/twin/modules/point-panel.vue
  views/iot/twin/modules/device-select.vue
  views/iot/twin/modules/binding-editor.vue
  views/iot/twin/modules/point-form.vue
  views/iot/twin/modules/model-panel.vue
  views/iot/twin/modules/runtime-legend.vue
```

### 0.2 新增测试

```
apps/web-antd/src/three/twin/disposal.test.ts
apps/web-antd/src/three/twin/scene-manager.test.ts
apps/web-antd/src/three/twin/runtime-store.test.ts
apps/web-antd/src/three/twin/picker.test.ts
apps/web-antd/src/views/iot/twin/twin-deeplink.test.ts
apps/web-antd/src/views/iot/twin/twin-validate.test.ts
```

### 0.3 修改

| 文件 | 变更 |
|---|---|
| `apps/web-antd/package.json` | `dependencies` 加 `three`；`devDependencies` 加 `@types/three`；可选 `three-mesh-bvh` |
| `pnpm-workspace.yaml` | catalog 加 `three` / `@types/three` / `three-mesh-bvh` |
| `apps/web-antd/src/api/iot/index.ts` | 加 `export * from './twin';` |
| `apps/web-antd/src/locales/langs/zh-CN/page.json` | `iot.twin.*` 段 |
| `apps/web-antd/src/locales/langs/en-US/page.json` | 同上 |
| `apps/web-antd/src/router/routes/modules/*.ts` | **不改**：菜单由后端 `sys_menu` 驱动（`generateAccessible` + `import.meta.glob('../views/**/*.vue')` 自动匹配 `component` 路径） |

---

## 1. 依赖与打包

### 1.1 `pnpm-workspace.yaml` 的 catalog 新增

```yaml
  # 数字孪生 3D 渲染（原生 three.js）。
  # ⚠️ three 与 @types/three 的版本号必须**成对**：three 自身不带 TypeScript 类型
  #    （0.186.1 的 package.json 中 types/typings 字段均为空，已核 npm registry 一手数据），
  #    类型由 DefinitelyTyped 按 three 的小版本跟随发布（当前 0.186.0）。
  #    升级 three 时必须同步升级 @types/three，否则会出现「类型里有的 API 运行时不存在」。
  three: ^0.186.1
  '@types/three': ^0.186.0
  # 建筑级模型的原生 Raycaster 是 O(三角形数)，几百万面时点击会卡顿数百毫秒。
  # three-mesh-bvh 提供 BVH 加速，把拾取降到接近 O(log n)。
  # MIT；peer 要求 three >= 0.159（当前 0.186.1 满足）。
  three-mesh-bvh: ^0.9.16
```

### 1.2 `apps/web-antd/package.json`

```jsonc
{
  "dependencies": {
    // ...既有...
    "three": "catalog:",
    "three-mesh-bvh": "catalog:"
  },
  "devDependencies": {
    // ...既有...
    "@types/three": "catalog:"
  }
}
```

> **为什么 `three` 在 dependencies 而不是 devDependencies**：它被运行期代码
> （`three/twin/*` 与 `views/iot/twin/*`）import，且会打进产物 —— 放 devDependencies 会在
> 生产安装（`--prod`）时缺失，而构建产物本身已包含它，所以严格说两者都能跑；
> 但**语义上它是运行期依赖**，与既有 `echarts`（catalog 里在 dependencies 段）同款处置。

### 1.3 分包（`apps/web-antd/vite.config.ts`）

本仓构建用 **rolldown**（`internal/vite-config/src/config/application.ts` 里是
`build.rolldownOptions`，不是 `rollupOptions`）。在 `apps/web-antd/vite.config.ts` 的
`build` 段追加 `advancedChunks`（rolldown 的等价能力，已核 `rolldown@1.2.0` 的类型定义
`advancedChunks.groups`）：

```ts
// apps/web-antd/vite.config.ts（在既有 defineConfig 的 build 段内追加）
build: {
  rolldownOptions: {
    output: {
      // 把 three 核心单独切一个 chunk：
      // ① 它约 600KB（压缩后 ~150KB），与业务代码混在一起会让首屏 chunk 体积失控；
      // ② 孪生页是动态 import 的，three 只有真正进入该页才加载；
      // ③ 独立 chunk 让浏览器长期缓存它（版本不变时切页面不重复下载）。
      advancedChunks: {
        groups: [
          {
            name: 'three-core',
            test: /node_modules[\\/]three[\\/]build[\\/]three\.(module|core)\.js$/,
          },
          {
            name: 'three-bvh',
            test: /node_modules[\\/]three-mesh-bvh[\\/]/,
          },
        ],
      },
    },
  },
},
```

> **为什么不去 `internal/vite-config` 里改**：那是跨 app 共享的构建基座（改它会影响
> admin/ele/naive 等所有 app，且属上游同步面）。分包策略是**本 app 的**关注点，
> 落在 `apps/web-antd/vite.config.ts` 即可。three addons（`GLTFLoader` 等）不单独分组：
> 它们由 `scene-manager` 动态 import 引入，rolldown 会把它们并入动态 chunk，不需要手工指定。

### 1.4 动态 import 策略（硬约束）

`views/iot/twin/modules/twin-canvas.vue` 是**唯一**直接 import `three/twin/*` 的地方，
且必须用动态 `await import()`：

```ts
// twin-canvas.vue 内（onMounted 之后才加载 three）
const { TwinSceneManager } = await import('#/three/twin/scene-manager');
```

`api/iot/twin.ts` 与 `views/iot/twin/index.vue` **不得** import 任何 `three/*` 模块——
首屏只应加载 API 类型与视图骨架，否则 `three` 会被打进主包。

---

## 2. 同步常量：`api/iot/twin.ts`

```ts
import type { IotPageResult } from './page';

import { requestClient } from '#/api/request';

/**
 * IoT 数字孪生 API（后端 `IotTwin*Controller`，网关 `/iot/**` + StripPrefix=1）。
 *
 * 语义边界（最容易混淆的一处）：
 * - `iot_point_mapping`（`api/iot/point.ts`）是**采集域**映射：协议地址 ↔ 物模型属性；
 * - 本文件的点位是**展示域**映射：3D 空间锚点 ↔ 设备。
 * 两者语义正交，不要混用类型。
 *
 * 全部 Long 型 ID 按字符串处理（后端 `BaseEntity` 的 `ToStringSerializer` 保证），
 * 禁止 `Number()` 转换（会丢精度）。
 */
export namespace IotTwinApi {
  /**
   * 全部硬上限（**与后端 `TwinLimits` 同值**，改一处必须同改另一处）。
   *
   * 为什么要在前端镜像一份：表单校验必须**在提交前**就给出可理解的提示，
   * 而不是让用户填完点保存再看到后端拒绝。同时后端仍会独立复核（防御式双保险）。
   * 与 `DEVICE_IMPORT_FRONTEND_LIMITS` ←→ `DeviceImportLimits` 的既有模式一致。
   */
  export const TWIN_LIMITS = {
    /** 场景树最大深度（根为第 1 层）。 */
    maxSceneDepth: 4,
    /** 场景编码长度上限。 */
    maxSceneCodeLength: 64,
    /** 场景编码允许的字符集（与后端 `TwinLimits.SCENE_CODE_PATTERN` 逐字一致）。 */
    sceneCodePattern: /^[A-Za-z0-9_-]{1,64}$/,
    /** 场景名称长度上限。 */
    maxSceneNameLength: 128,
    /** 场景备注长度上限。 */
    maxSceneRemarkLength: 500,
    /** 场景视角配置 JSON 字节上限（16 KB）。 */
    maxViewJsonBytes: 16 * 1024,
    /** 单场景点位上限。 */
    maxPointsPerScene: 500,
    /** 单点位属性绑定上限。 */
    maxBindingsPerPoint: 12,
    /** 模型节点名长度上限。 */
    maxNodeNameLength: 255,
    /** 点位图标标识长度上限。 */
    maxIconLength: 64,
    /** 点位备注长度上限。 */
    maxPointRemarkLength: 500,
    /** 单位覆盖长度上限。 */
    maxUnitLength: 32,
    /** 小数位上限。 */
    maxDecimals: 6,
    /** 模型名称长度上限。 */
    maxAssetNameLength: 128,
    /** 外链地址长度上限。 */
    maxExternalUrlLength: 1024,
    /** 模型文件字节上限（64 MB，与后端 `TwinLimits.MAX_MODEL_BYTES` 同值）。 */
    maxModelBytes: 64 * 1024 * 1024,
    /** 允许的模型扩展名。 */
    allowedModelExtensions: ['.glb', '.gltf'] as const,
  } as const;

  /** 场景类型码。 */
  export type SceneType = 'building' | 'floor' | 'park' | 'room';

  /** 展示形态码。 */
  export type DisplayType = 'color' | 'gauge' | 'sparkline' | 'switch' | 'value';

  /** 展示角色码。 */
  export type DisplayRole = 'primary' | 'secondary';

  /** 模型来源类型。 */
  export type AssetType = 'external' | 'upload';

  /** 模型格式。 */
  export type ModelFormat = 'glb' | 'gltf';

  /** 场景树节点（同时用于表单与树渲染）。 */
  export interface SceneResp {
    id: string;
    sceneCode: string;
    sceneName: string;
    /** 父场景 ID；根场景为 `"0"`（不是 null，与后端一致）。 */
    parentId: string;
    /**
     * 父场景名（后端 `@RefText("twinScene")` 派生）。
     *
     * ⚠️ 根场景（parentId=0）查不到行 ⇒ 该字段**不存在**（后端不编造「根场景」这个名字）。
     * 消费方必须判空并自行决定显示「（根）」，不得把缺失当成「父场景已删除」。
     */
    parentIdName?: string;
    sceneType: SceneType;
    modelAssetId?: null | string;
    /** 模型资产名（`@RefText("twinModelAsset")` 派生；同 parentIdName 的缺失语义）。 */
    modelAssetIdName?: string;
    /** BigDecimal 全局序列化为字符串，展示前按需 `Number()`（坐标计算**必须**转数）。 */
    anchorX: string;
    anchorY: string;
    anchorZ: string;
    anchorRx: string;
    anchorRy: string;
    anchorRz: string;
    anchorScale: string;
    viewJson?: null | string;
    sort?: number;
    pointCount?: number;
    childCount?: number;
    remark?: string;
    createTime?: string;
  }

  /** 场景新增/编辑请求。 */
  export interface SceneSaveReq {
    sceneCode: string;
    sceneName: string;
    /** 根场景传 undefined（不是 0，也不是空串）。 */
    parentId?: string;
    sceneType: SceneType;
    modelAssetId?: string;
    anchorX?: number | string;
    anchorY?: number | string;
    anchorZ?: number | string;
    anchorRx?: number | string;
    anchorRy?: number | string;
    anchorRz?: number | string;
    anchorScale?: number | string;
    viewJson?: null | string;
    sort?: number;
    remark?: string;
  }

  /** 属性绑定（响应）。 */
  export interface BindingResp {
    id: string;
    serviceIdentifier: string;
    propertyIdentifier: string;
    /** 属性主键缓存（可能悬空）。 */
    propertyId?: null | string;
    /** 属性显示名（解析不到时回落属性标识本身）。 */
    propertyName: string;
    dataType?: null | string;
    unit?: null | string;
    displayType: DisplayType;
    displayRole: DisplayRole;
    decimals?: null | number;
    unitOverride?: null | string;
    sort?: number;
    /**
     * 该绑定是否已失效（属性在物模型里已不存在）。
     *
     * 🔴 页面**必须**显著标红并提示「请重新选择属性」，不得静默隐藏或丢弃——
     * 静默丢弃会让用户以为「我明明绑过」，而实际上什么都看不见。
     */
    stale: boolean;
  }

  /** 属性绑定（请求）。 */
  export interface BindingSaveReq {
    serviceIdentifier: string;
    propertyIdentifier: string;
    displayType: DisplayType;
    displayRole?: DisplayRole;
    decimals?: null | number;
    unitOverride?: null | string;
    sort?: number;
  }

  /** 点位（响应）。 */
  export interface PointResp {
    id: string;
    sceneId: string;
    deviceId: string;
    /** 设备名（`@RefText("device")` 派生；设备已删除时不存在，页面回落显示裸 ID）。 */
    deviceIdName?: string;
    nodeName?: null | string;
    localX: string;
    localY: string;
    localZ: string;
    normalX: string;
    normalY: string;
    normalZ: string;
    icon?: null | string;
    labelVisible: boolean;
    sort?: number;
    remark?: string;
    createTime?: string;
    /** 属性绑定列表（按主序、sort 升序；无绑定时为空数组，后端保证不为 null）。 */
    bindings: BindingResp[];
  }

  /** 点位新增/编辑请求（编辑时**整体替换**其绑定）。 */
  export interface PointSaveReq {
    deviceId: string;
    nodeName?: null | string;
    localX: number | string;
    localY: number | string;
    localZ: number | string;
    normalX?: number | string;
    normalY?: number | string;
    normalZ?: number | string;
    icon?: null | string;
    labelVisible?: boolean;
    sort?: number;
    remark?: string;
    /** 必填（可为空数组）；传 null 是调用方漏字段，后端会拒绝。 */
    bindings: BindingSaveReq[];
  }

  /** 运行态单个属性的最新值。 */
  export interface RuntimeValueResp {
    value?: null | string;
    quality?: null | string;
    /** epoch 毫秒（后端 Long ⇒ 字符串；展示前需 `Number()`）。 */
    ts?: null | string;
    stale: boolean;
  }

  /** 运行态单个点位。 */
  export interface RuntimePointResp {
    pointId: string;
    deviceId: string;
    deviceCode?: null | string;
    deviceName?: null | string;
    /** online | offline | unknown（unknown **不代表离线**）。 */
    onlineStatus?: null | string;
    /** 启停位：1 启用 0 停用。 */
    status?: null | number;
    lastSeenAt?: null | string;
    alertCount: number;
    /** 键=属性标识；无数据时为空对象（后端保证不为 null）。 */
    values: Record<string, RuntimeValueResp>;
  }

  /** 运行态场景快照。 */
  export interface RuntimeResp {
    sceneId: string;
    /** 服务端组装时刻（**不是**「所有读数的同一时刻」）。 */
    serverTime: string;
    pointCount: number;
    truncated: boolean;
    points: RuntimePointResp[];
  }

  /** 模型资产（响应）。 */
  export interface ModelAssetResp {
    id: string;
    assetName: string;
    assetType: AssetType;
    modelFormat: ModelFormat;
    externalUrl?: null | string;
    /** 页面实际使用的模型地址（upload ⇒ 平台下载端点；external ⇒ 外链原文）。 */
    contentUrl: string;
    originalName?: null | string;
    fileSize?: null | number;
    contentHash?: null | string;
    nodeNames: string[];
    sceneCount: number;
    createTime?: string;
  }

  /** 外链模型登记请求。 */
  export interface ModelExternalReq {
    assetName: string;
    modelFormat?: ModelFormat;
    externalUrl: string;
  }

  /** 设备可绑定的物模型属性。 */
  export interface DevicePropertyResp {
    serviceId: string;
    serviceIdentifier: string;
    serviceName: string;
    propertyId: string;
    propertyIdentifier: string;
    propertyName: string;
    dataType: string;
    accessMode: string;
    unit?: null | string;
    minValue?: null | string;
    maxValue?: null | string;
    /**
     * 该属性在本设备上是否已建立采集点位映射。
     *
     * 🔴 为 `false` 时属性**永远拿不到实时值**。配置页必须给出
     * 「该属性未配置采集点位，绑定后不会有数据」的提示——否则用户绑一堆没数据的属性，
     * 然后认为孪生功能坏了。
     */
    mapped: boolean;
  }

  /** 分页信封别名（与其它 IoT API 同源，避免各文件各定义一份）。 */
  export type PageResult<T> = IotPageResult<T>;
}

/** 查询全部场景（树形由调用方按 parentId 组装）。 */
export async function getTwinSceneList() {
  return requestClient.get<IotTwinApi.SceneResp[]>('/iot/twin/scenes');
}

/** 查询单个场景。 */
export async function getTwinScene(id: string) {
  return requestClient.get<IotTwinApi.SceneResp>(`/iot/twin/scenes/${id}`);
}

/** 新建场景（返回新场景主键）。 */
export async function createTwinScene(data: IotTwinApi.SceneSaveReq) {
  return requestClient.post<number>('/iot/twin/scenes', data);
}

/** 编辑场景。 */
export async function updateTwinScene(
  id: string,
  data: IotTwinApi.SceneSaveReq,
) {
  return requestClient.put<null>(`/iot/twin/scenes/${id}`, data);
}

/** 删除场景（有子场景或点位时后端拒绝，不级联）。 */
export async function deleteTwinScene(id: string) {
  return requestClient.delete<null>(`/iot/twin/scenes/${id}`);
}

/** 查询场景下全部点位（含绑定）。 */
export async function getTwinPointList(sceneId: string) {
  return requestClient.get<IotTwinApi.PointResp[]>(
    `/iot/twin/scenes/${sceneId}/points`,
  );
}

/** 新增点位（可同时带绑定）。 */
export async function createTwinPoint(
  sceneId: string,
  data: IotTwinApi.PointSaveReq,
) {
  return requestClient.post<number>(
    `/iot/twin/scenes/${sceneId}/points`,
    data,
  );
}

/** 编辑点位并整体替换其绑定。 */
export async function updateTwinPoint(
  sceneId: string,
  pointId: string,
  data: IotTwinApi.PointSaveReq,
) {
  return requestClient.put<null>(
    `/iot/twin/scenes/${sceneId}/points/${pointId}`,
    data,
  );
}

/** 删除点位（连同其绑定）。 */
export async function deleteTwinPoint(sceneId: string, pointId: string) {
  return requestClient.delete<null>(
    `/iot/twin/scenes/${sceneId}/points/${pointId}`,
  );
}

/** 查询设备可绑定的物模型属性（设备未绑定产品时返回空数组）。 */
export async function getTwinDeviceProperties(deviceId: string) {
  return requestClient.get<IotTwinApi.DevicePropertyResp[]>(
    `/iot/twin/devices/${deviceId}/properties`,
  );
}

/** 查询场景运行态快照（进入场景时一次拉齐）。 */
export async function getTwinRuntime(sceneId: string) {
  return requestClient.get<IotTwinApi.RuntimeResp>(
    `/iot/twin/scenes/${sceneId}/runtime`,
  );
}

/** 查询全部模型资产（含被场景引用数）。 */
export async function getModelAssetList() {
  return requestClient.get<IotTwinApi.ModelAssetResp[]>(
    '/iot/twin/model-assets',
  );
}

/**
 * 上传模型文件。
 *
 * `nodeNames` 以**重复 query 参数**形式提交（`?nodeNames=a&nodeNames=b`），
 * 后端用 `@RequestParam List<String>` 接收。不用 JSON body 是因为文件本身必须走
 * multipart，混用 multipart + JSON body 需要在后端做手工解析，不值得。
 *
 * `onProgress` 用 axios 的 `onUploadProgress`：64MB 模型在慢网下需要进度反馈，
 * 否则用户会以为卡死。
 */
export async function uploadModelAsset(
  file: File,
  options?: {
    assetName?: string;
    nodeNames?: string[];
    onProgress?: (percent: number) => void;
  },
) {
  const formData = new FormData();
  formData.append('file', file);
  if (options?.assetName) {
    formData.append('assetName', options.assetName);
  }
  for (const name of options?.nodeNames ?? []) {
    formData.append('nodeNames', name);
  }
  return requestClient.post<IotTwinApi.ModelAssetResp>(
    '/iot/twin/model-assets/upload',
    formData,
    {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (event) => {
        if (!options?.onProgress || !event.total) {
          return;
        }
        options.onProgress(Math.round((event.loaded / event.total) * 100));
      },
      // 大文件上传容易超过默认超时
      timeout: 10 * 60 * 1000,
    },
  );
}

/** 登记外链模型。 */
export async function createModelAssetExternal(
  data: IotTwinApi.ModelExternalReq,
) {
  return requestClient.post<IotTwinApi.ModelAssetResp>(
    '/iot/twin/model-assets/external',
    data,
  );
}

/** 删除模型资产（被场景引用时后端拒绝）。 */
export async function deleteModelAsset(id: string) {
  return requestClient.delete<null>(`/iot/twin/model-assets/${id}`);
}

/**
 * 取模型内容的**绝对地址**（供 three.js 的 `GLTFLoader.load` 使用）。
 *
 * 为什么需要拼绝对地址：`GLTFLoader` 直接发 `fetch`，不走 `requestClient`，
 * 因此拿不到 baseURL 前缀与 Authorization 头。鉴权有两种做法：
 * ① 把 token 拼进 URL（不可接受：token 会进浏览器历史/日志）；
 * ② 用 `LoadingManager.setURLModifier` 统一注入请求头 —— 见 `model-loader.ts`。
 * 本函数只负责把相对路径拼成绝对地址，鉴权由 loader 侧的 `setURLModifier` 完成。
 *
 * @param contentUrl 后端返回的 `contentUrl`
 * @returns 绝对地址
 */
export function resolveModelUrl(contentUrl: string) {
  if (/^https?:\/\//i.test(contentUrl)) {
    return contentUrl;
  }
  // 用 requestClient 的 baseURL 而不是自己读 env：baseURL 已经过 `useAppConfig` 的解析
  // （含 `VITE_GLOB_API_URL` 与相对/绝对地址两种形态的处理），自己再拼一份必然与之漂移。
  const base = requestClient.getBaseUrl() ?? '';
  const prefix = base.endsWith('/') ? base.slice(0, -1) : base;
  return `${prefix}${contentUrl.startsWith('/') ? '' : '/'}${contentUrl}`;
}
```

### 2.1 `api/iot/index.ts` 追加

```ts
export * from './twin';
```

> 位置：按字母序插在 `export * from './trace';` 之后（既有文件已是字母序）。

---

## 3. 引擎层公共类型：`three/twin/types.ts`

```ts
/**
 * three 引擎层的公共类型（零 Vue 依赖，可被单测直接 import）。
 */

/** 场景进入/加载状态机（每一次迁移都必须让 UI 可见）。 */
export type TwinSceneState =
  | 'disposed'
  | 'disposing'
  | 'failed'
  | 'loading'
  | 'ready';

/** 引擎层对外暴露的错误（带可展示文案与阶段）。 */
export interface TwinSceneError {
  /** 可展示的失败原因（已本地化；引擎层不做 $t，由调用方传入文案）。 */
  message: string;
  /** 失败阶段：下载/解析/装配。 */
  stage: 'assemble' | 'download' | 'parse';
}

/** 模型下载进度回调。 */
export interface TwinLoadProgress {
  /** 已加载字节。 */
  loaded: number;
  /** 总字节（未知时为 0）。 */
  total: number;
  /** 百分比（0~100；总字节未知时为 -1）。 */
  percent: number;
}

/** 世界坐标变换（与后端 `anchor_*` 列一一对应）。 */
export interface AnchorTransform {
  x: number;
  y: number;
  z: number;
  rx: number;
  ry: number;
  rz: number;
  scale: number;
}

/** 锚点运行态（渲染层需要的全部信息）。 */
export interface AnchorRuntimeState {
  /** 活动告警数（> 0 ⇒ 闪烁）。 */
  alertCount: number;
  /** 设备在线状态：online | offline | unknown | 空（设备已不存在）。 */
  onlineStatus?: null | string;
  /** 启停位：1 启用 0 停用（0 ⇒ 灰化）。 */
  status?: null | number;
  /** 主展示属性的一句话（如 `26.5 ℃`）；空串表示「暂无数据」。 */
  primaryText: string;
  /** 是否有任何最新值（false ⇒ 标签显示 `--`，不是 0）。 */
  hasValue: boolean;
}

/** 锚点描述（由页面从 `PointResp` 转换而来，引擎层不依赖 API 类型）。 */
export interface AnchorDescriptor {
  pointId: string;
  deviceId: string;
  deviceName: string;
  /** 场景局部坐标。 */
  local: { x: number; y: number; z: number };
  /** 场景局部法线。 */
  normal: { x: number; y: number; z: number };
  icon?: null | string;
  labelVisible: boolean;
  sort: number;
}

/** 相机位姿（`view_json` 的结构，前端拥有语义）。 */
export interface TwinViewConfig {
  camera?: {
    /** 相机位置（世界坐标，或 `target` 相对坐标——本实现用世界坐标）。 */
    position?: { x: number; y: number; z: number };
    /** 观察目标（世界坐标）。 */
    target?: { x: number; y: number; z: number };
    /** 透视相机 fov（度）。 */
    fov?: number;
  };
  /** 背景色（CSS 颜色串，如 `#0b1220`）。 */
  background?: string;
  /** 环境光强度（0~3）。 */
  ambientIntensity?: number;
  /** 平行光强度（0~5）。 */
  directionalIntensity?: number;
}
```

> **为什么锚点描述不直接用 `IotTwinApi.PointResp`**：引擎层要能被**纯单测**覆盖
> （不起 Vue、不起 API mock），且「坐标是 number 还是 string」这种 API 层细节不该泄漏进来。
> 页面负责 `Number()` 转换与 `deviceIdName` 回落，引擎层只吃纯数字。

---

## 4. GPU 资源回收：`three/twin/disposal.ts`

```ts
import type * as THREEType from 'three';

/**
 * three.js 的 **GPU 资源递归回收**（本文件是「反复切换场景不涨内存」的唯一实现点）。
 *
 * ## 为什么必须自己做
 *
 * three.js **不会**自动回收 GPU 资源：`geometry`/`material`/`texture` 创建后由 WebGL 上下文
 * 持有，`scene.remove(obj)` 只断开引用，**显存不释放**。`THREE.Cache` 缓存的是 HTTP 响应
 * （ArrayBuffer/JSON），**不管显存**。
 *
 * 这类数字孪生页面的典型事故是：进楼层 → 返回 → 进另一层，重复十几次后显存耗尽、页面变卡甚至
 * 标签页崩溃，而代码里没有任何报错。因此回收必须是**显式、递归、幂等**的。
 *
 * ## 口径
 *
 * - 递归遍历整棵子树（含 `SkinnedMesh` 的 `skeleton` 贴图）；
 * - 每个 `geometry`/`material`/贴图**只 dispose 一次**（用 `WeakSet` 去重：多个 mesh 可能共享
 *   同一 material，重复 `dispose()` 在 three.js 里是安全的但会打警告）；
 * - `dispose()` 之后把引用置 `null`，让 GC 能回收 JS 侧对象；
 * - **不** dispose `WebGLRenderer`（它是长生命周期的，每次切场景重建上下文极贵且易泄漏；
 *   渲染器在页面卸载时随组件销毁由 GC/上下文丢失机制处理）。
 *
 */

/** 释放统计（供开发期内存自检与单测断言）。 */
export interface DisposeStats {
  /** 已释放的几何体数量。 */
  geometries: number;
  /** 已释放的材质数量。 */
  materials: number;
  /** 已释放的贴图数量。 */
  textures: number;
  /** 遍历到的对象数量。 */
  objects: number;
}

/**
 * 递归释放场景子树占用的 GPU 资源。
 *
 * @param root 场景根或任意子树根
 * @returns 释放统计
 */
export function disposeSubtree(root: THREEType.Object3D): DisposeStats {
  const stats: DisposeStats = {
    geometries: 0,
    materials: 0,
    objects: 0,
    textures: 0,
  };
  const disposedGeometries = new WeakSet<object>();
  const disposedMaterials = new WeakSet<object>();
  const disposedTextures = new WeakSet<object>();

  root.traverse((object) => {
    stats.objects += 1;

    // 1) 几何体
    const withGeometry = object as THREEType.Object3D & {
      geometry?: THREEType.BufferGeometry;
    };
    if (withGeometry.geometry && !disposedGeometries.has(withGeometry.geometry)) {
      disposedGeometries.add(withGeometry.geometry);
      withGeometry.geometry.dispose();
      stats.geometries += 1;
    }

    // 2) 材质（可能是单个或数组）
    const withMaterial = object as THREEType.Object3D & {
      material?: THREEType.Material | THREEType.Material[];
    };
    const materials = withMaterial.material;
    if (materials) {
      const list = Array.isArray(materials) ? materials : [materials];
      for (const material of list) {
        if (disposedMaterials.has(material)) {
          continue;
        }
        disposedMaterials.add(material);
        stats.textures += disposeMaterialTextures(material, disposedTextures);
        material.dispose();
        stats.materials += 1;
      }
    }

    // 3) 骨架贴图（SkinnedMesh 的骨骼矩阵纹理也是显存大户）
    const withSkeleton = object as THREEType.Object3D & { skeleton?: THREEType.Skeleton };
    if (withSkeleton.skeleton) {
      const boneTexture = (withSkeleton.skeleton as unknown as { boneTexture?: THREEType.Texture })
        .boneTexture;
      if (boneTexture && !disposedTextures.has(boneTexture)) {
        disposedTextures.add(boneTexture);
        boneTexture.dispose();
        stats.textures += 1;
      }
    }
  });

  return stats;
}

/**
 * 释放材质上挂的全部贴图（`map`/`normalMap`/`roughnessMap`… 以及 `envMap`）。
 *
 * 用「遍历自有属性、值是不是 `Texture`」而不是硬编码属性名：three.js 的材质族有几十种
 * （`MeshStandardMaterial`/`MeshPhysicalMaterial`/`PointsMaterial`…），硬编码清单必然漏。
 * `Texture` 的识别用鸭子类型（有 `isTexture === true`）而不是 `instanceof`：
 * 单测里 three 可能被 mock，`instanceof` 会失效。
 *
 * @param material 材质
 * @param disposed 已释放贴图去重集合（跨材质共享的贴图只释放一次）
 * @returns 本次释放的贴图数量
 */
function disposeMaterialTextures(
  material: THREEType.Material,
  disposed: WeakSet<object>,
): number {
  let count = 0;
  for (const value of Object.values(material as unknown as Record<string, unknown>)) {
    if (!isTextureLike(value)) {
      continue;
    }
    const texture = value as THREEType.Texture;
    if (disposed.has(texture)) {
      continue;
    }
    disposed.add(texture);
    texture.dispose();
    count += 1;
  }
  return count;
}

/**
 * 判断一个值是否像 `THREE.Texture`（鸭子类型，避免 `instanceof` 在 mock 环境失效）。
 *
 * @param value 任意值
 * @returns 是否可视为贴图
 */
function isTextureLike(value: unknown): boolean {
  return (
    typeof value === 'object' &&
    value !== null &&
    (value as { isTexture?: boolean }).isTexture === true
  );
}
```

### 4.1 `three/twin/disposal.test.ts`

```ts
import type * as THREEType from 'three';

import { describe, expect, it, vi } from 'vitest';

import { disposeSubtree } from './disposal';

/**
 * GPU 资源回收：递归、去重、幂等。
 *
 * 纯单测：用最小假对象模拟 `Object3D` 的 `traverse`/`geometry`/`material`，
 * 不引入真实 three（避免 happy-dom 下的 WebGL 依赖）。
 */

interface FakeMaterial {
  dispose: ReturnType<typeof vi.fn>;
  isTexture?: boolean;
  map?: { dispose: ReturnType<typeof vi.fn>; isTexture: true };
}

/** 构造一个可 traverse 的假对象树。 */
function fakeTree(nodes: Array<Record<string, unknown>>) {
  const root = {
    traverse: (callback: (node: Record<string, unknown>) => void) => {
      for (const node of nodes) {
        callback(node);
      }
    },
  } as unknown as THREEType.Object3D;
  return root;
}

function fakeTexture() {
  return { dispose: vi.fn(), isTexture: true as const };
}

function fakeMaterial(extra: Record<string, unknown> = {}): FakeMaterial {
  return { dispose: vi.fn(), ...extra };
}

describe('disposeSubtree', () => {
  it('递归释放几何体、材质与其贴图', () => {
    const geometry = { dispose: vi.fn() };
    const texture = fakeTexture();
    const material = fakeMaterial({ map: texture });
    const root = fakeTree([
      { geometry, material },
      { geometry: { dispose: vi.fn() }, material: [fakeMaterial()] },
    ]);

    const stats = disposeSubtree(root);

    expect(geometry.dispose).toHaveBeenCalledTimes(1);
    expect(material.dispose).toHaveBeenCalledTimes(1);
    expect(texture.dispose).toHaveBeenCalledTimes(1);
    expect(stats.geometries).toBe(2);
    expect(stats.materials).toBe(2);
    expect(stats.textures).toBe(1);
    expect(stats.objects).toBe(2);
  });

  it('★ 多个 mesh 共享同一材质/几何体时只释放一次', () => {
    const sharedGeometry = { dispose: vi.fn() };
    const sharedTexture = fakeTexture();
    const sharedMaterial = fakeMaterial({ map: sharedTexture });
    const root = fakeTree([
      { geometry: sharedGeometry, material: sharedMaterial },
      { geometry: sharedGeometry, material: sharedMaterial },
      { geometry: sharedGeometry, material: sharedMaterial },
    ]);

    const stats = disposeSubtree(root);

    expect(sharedGeometry.dispose).toHaveBeenCalledTimes(1);
    expect(sharedMaterial.dispose).toHaveBeenCalledTimes(1);
    expect(sharedTexture.dispose).toHaveBeenCalledTimes(1);
    expect(stats.geometries).toBe(1);
    expect(stats.materials).toBe(1);
  });

  it('★ 材质上的全部贴图都被释放（不依赖硬编码属性名）', () => {
    const map = fakeTexture();
    const normalMap = fakeTexture();
    const roughnessMap = fakeTexture();
    const envMap = fakeTexture();
    const material = fakeMaterial({ envMap, map, normalMap, roughnessMap });
    const root = fakeTree([{ material }]);

    const stats = disposeSubtree(root);

    for (const texture of [map, normalMap, roughnessMap, envMap]) {
      expect(texture.dispose).toHaveBeenCalledTimes(1);
    }
    expect(stats.textures).toBe(4);
  });

  it('骨架贴图（boneTexture）也被释放', () => {
    const boneTexture = fakeTexture();
    const root = fakeTree([{ skeleton: { boneTexture } }]);

    const stats = disposeSubtree(root);

    expect(boneTexture.dispose).toHaveBeenCalledTimes(1);
    expect(stats.textures).toBe(1);
  });

  it('空树不报错，统计全 0', () => {
    const stats = disposeSubtree(fakeTree([]));
    expect(stats).toEqual({
      geometries: 0,
      materials: 0,
      objects: 0,
      textures: 0,
    });
  });

  it('不是贴图的对象（number/string/null）不被当成贴图', () => {
    const material = fakeMaterial({
      color: 0xff_00_00,
      name: 'mat',
      opacity: 1,
      userData: null,
    });
    const root = fakeTree([{ material }]);

    const stats = disposeSubtree(root);

    expect(stats.textures).toBe(0);
    expect(material.dispose).toHaveBeenCalledTimes(1);
  });
});
```

---

## 5. 模型加载：`three/twin/model-loader.ts`

```ts
import type * as THREEType from 'three';

import type { TwinLoadProgress } from './types';

import { useAccessStore } from '@vben/stores';

/**
 * 模型加载器装配（GLTFLoader + Draco + KTX2 + Meshopt）。
 *
 * ## 三个必须做对的地方
 *
 * ### 1) 鉴权：自己 fetch 拿 ArrayBuffer，再 `parseAsync`
 *
 * `GLTFLoader` 内部用 `fetch`/`FileLoader` 拉模型，**不走** `requestClient` ⇒ 拿不到
 * Bearer token；而模型下载端点有 `@SaCheckPermission("iot:twin:list")`，裸请求会 401。
 * `LoadingManager.setURLModifier` 只能改 URL、改不了请求头。
 *
 * 因此本文件的策略是：**先自己 `fetch`（带 Authorization、带进度、可 abort）拿到
 * ArrayBuffer，再 `loader.parseAsync(buffer, basePath)` 只做解析**。这样请求完全由我们控制，
 * 也不影响 glTF 内部相对资源（`.bin`/贴图）通过 `basePath` 解析。
 *
 * ### 2) 压缩解码器按需装配
 *
 * - **Draco**：几何压缩（`KHR_draco_mesh_compression`）。解码器 wasm 从 CDN 或自托管路径取。
 * - **KTX2**：纹理压缩（`KHR_texture_basisu`）。
 * - **Meshopt**：几何/网格优化（`EXT_meshopt_compression`）。
 *
 * 三者都是**可选**的：模型没用对应扩展时不需要解码器。但**全部装上**是更省心的选择
 * （解码器只在真的遇到对应扩展时才加载 wasm，不装则遇到扩展直接抛错，用户看到「模型打不开」）。
 *
 * ### 3) 节点名提取
 *
 * 上传时要把节点名快照传给后端（用于「按节点名批量预匹配」）。提取必须遍历 `gltf.scene`，
 * 只取有 `name` 的节点，并**排除自动生成的匿名节点**（three.js 会给无名节点留空 name，
 * 没有过滤价值）。
 */

/** 模型加载结果。 */
export interface LoadedModel {
  /** 场景根（调用方负责 attach/dispose）。 */
  scene: THREEType.Group;
  /** 全部具名节点名（去重、稳定顺序）。 */
  nodeNames: string[];
  /** 解析后的动画列表（本功能当前不使用，透出以便将来扩展）。 */
  animations: THREEType.AnimationClip[];
}

/** 加载选项。 */
export interface LoadModelOptions {
  /** 模型绝对地址（已由 `resolveModelUrl` 拼好）。 */
  url: string;
  /**
   * glTF 内部相对资源的基准路径（`loader.parseAsync` 的第二参数）。
   *
   * 自包含 GLB 传空串即可；**分离资源**的 `.gltf`（外链 `.bin` 与贴图）必须传
   * 「模型所在目录的绝对地址」，否则相对路径会解析到页面 URL 上而 404。
   * 由调用方按 `url` 去掉文件名部分得出。
   */
  basePath?: string;
  /** 进度回调。 */
  onProgress?: (progress: TwinLoadProgress) => void;
  /** 中止信号（切换场景/离开页面时用）。 */
  signal?: AbortSignal;
  /**
   * Draco 解码器路径（指向 `draco_decoder.wasm` 所在目录）。
   *
   * 默认取 three 包内的 `examples/jsm/libs/draco/gltf/`。生产构建时该目录需要被复制到
   * 可访问路径（见本文 §15「部署注意」）—— 若模型未用 Draco 压缩，该路径不存在也不影响加载。
   */
  dracoPath?: string;
}

/** 默认 Draco 解码器目录（随 three 包发布；构建时由 vite 的 ?url 或静态复制提供）。 */
const DEFAULT_DRACO_PATH = '/draco/';

/** 相机等元信息不参与「节点名预匹配」，故不纳入快照。 */
const NON_GEOMETRY_NODE_PREFIXES = ['Camera', 'Light'] as const;

/**
 * 加载 glTF/GLB 模型。
 *
 * @param options 加载选项
 * @returns 加载结果
 * @throws Error 下载失败、解析失败、被中止（`AbortError`）
 */
export async function loadModel(options: LoadModelOptions): Promise<LoadedModel> {
  const { url, onProgress, signal } = options;
  const buffer = await downloadWithAuth(url, onProgress, signal);

  // 动态 import：three 与 addons 只在真正加载模型时才进内存
  const THREE = await import('three');
  const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js');

  const manager = new THREE.LoadingManager();
  const loader = new GLTFLoader(manager);

  // Draco：仅在模型真的用了 Draco 扩展时会真正加载 wasm 解码器
  try {
    const { DRACOLoader } = await import('three/addons/loaders/DRACOLoader.js');
    const dracoLoader = new DRACOLoader(manager);
    dracoLoader.setDecoderPath(options.dracoPath ?? DEFAULT_DRACO_PATH);
    loader.setDRACOLoader(dracoLoader);
  } catch (error) {
    // 解码器文件缺失不应让**未压缩**模型加载失败：留痕后继续
    console.warn(
      '[iot/twin] Draco 解码器装配失败，使用 Draco 压缩的模型将无法加载：',
      error,
    );
  }

  // Meshopt：解码器是纯 JS，随 three 包提供，装配无额外资源依赖
  try {
    const { MeshoptDecoder } = await import(
      'three/addons/libs/meshopt_decoder.module.js'
    );
    loader.setMeshoptDecoder(MeshoptDecoder);
  } catch (error) {
    console.warn(
      '[iot/twin] Meshopt 解码器装配失败，使用 meshopt 压缩的模型将无法加载：',
      error,
    );
  }

  // KTX2：需要 renderer 才能检测纹理支持，故由 scene-manager 在拿到 renderer 后注入
  // （见 `attachKtx2`）。此处不放，避免 loader 与 renderer 循环依赖。

  // 用 parseAsync 而不是 load：数据已在手（带鉴权下载完成），只做解析。
  // basePath 决定 glTF 内部相对资源（.bin / 贴图）的解析基准，分离资源模型必传。
  const gltf = await loader.parseAsync(buffer, options.basePath ?? '');
  const scene = gltf.scene as THREEType.Group;
  return {
    animations: gltf.animations ?? [],
    nodeNames: collectNodeNames(scene, nonGeometryNodeNames(gltf)),
    scene,
  };
}

/**
 * 带 Bearer 鉴权下载模型字节，并上报进度。
 *
 * 为什么不用 `fetch` 直接读 `response.body`：需要进度必须自己算（`Response.body` 的
 * reader 拿不到 `content-length` 之外的进度信息，但可以按已读字节累加）。这里就是累加口径。
 *
 * @param url 绝对地址
 * @param onProgress 进度回调
 * @param signal 中止信号
 * @returns 完整字节
 */
async function downloadWithAuth(
  url: string,
  onProgress?: (progress: TwinLoadProgress) => void,
  signal?: AbortSignal,
): Promise<ArrayBuffer> {
  const headers: Record<string, string> = {};
  const accessStore = useAccessStore();
  if (accessStore.accessToken) {
    headers.Authorization = `Bearer ${accessStore.accessToken}`;
  }

  const response = await fetch(url, { headers, signal });
  if (!response.ok) {
    throw new Error(
      `模型下载失败：HTTP ${response.status} ${response.statusText}`,
    );
  }
  const total = Number(response.headers.get('content-length') ?? 0);
  const body = response.body;
  if (!body) {
    // 没有流（极旧浏览器/被代理剥离）：退化为一次性读取，进度直接跳到 100
    const buffer = await response.arrayBuffer();
    onProgress?.({ loaded: buffer.byteLength, percent: 100, total: buffer.byteLength });
    return buffer;
  }

  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    chunks.push(value);
    loaded += value.byteLength;
    onProgress?.({
      loaded,
      percent: total > 0 ? Math.min(100, Math.round((loaded / total) * 100)) : -1,
      total,
    });
  }
  const merged = new Uint8Array(loaded);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged.buffer;
}

/**
 * 取自「本质上不是几何体」的节点名（相机/灯光）。
 *
 * 为什么要排除：这些名字对「设备节点预匹配」没有价值，留着会让下拉候选里混入
 * `DirectionalLight`/`Camera001` 这类噪音。glTF 的 `cameras` 数组给出权威清单。
 *
 * @param gltf 解析结果
 * @returns 需排除的节点名集合
 */
function nonGeometryNodeNames(gltf: {
  cameras?: THREEType.Camera[];
}): Set<string> {
  const excluded = new Set<string>();
  for (const camera of gltf.cameras ?? []) {
    if (camera.name) {
      excluded.add(camera.name);
    }
  }
  return excluded;
}

/**
 * 遍历场景提取全部具名节点名（去重、按遍历顺序稳定）。
 *
 * @param root 场景根
 * @param excluded 需排除的节点名
 * @returns 节点名列表
 */
function collectNodeNames(root: THREEType.Object3D, excluded: Set<string>): string[] {
  const names = new Set<string>();
  root.traverse((object) => {
    if (!object.name || excluded.has(object.name)) {
      return;
    }
    // three.js 内部节点命名以这些前缀开头的（如 GLTFLoader 建的 `_rootJoint`）不是业务节点
    if (NON_GEOMETRY_NODE_PREFIXES.some((prefix) => object.name.startsWith(prefix))) {
      return;
    }
    names.add(object.name);
  });
  return [...names];
}

/**
 * 装配 KTX2 纹理解码器（需要 renderer 提供 `detectSupport`）。
 *
 * 单独一个函数而不是塞进 `loadModel`：`loader.setKTX2Loader` 必须在**解析之前**调用，
 * 而 KTX2 探测需要 renderer —— 调用方（scene-manager）先建 renderer 再调本函数，
 * 顺序由调用方保证，本函数只做装配。
 *
 * @param loader GLTFLoader 实例
 * @param renderer WebGLRenderer 实例
 * @returns 是否装配成功（失败不抛：未用 KTX2 的模型照常加载）
 */
export async function attachKtx2(
  loader: { setKTX2Loader: (loader: unknown) => void },
  renderer: THREEType.WebGLRenderer,
): Promise<boolean> {
  try {
    const { KTX2Loader } = await import('three/addons/loaders/KTX2Loader.js');
    const ktx2Loader = new KTX2Loader();
    ktx2Loader.setTranscoderPath('/basis/');
    ktx2Loader.detectSupport(renderer);
    loader.setKTX2Loader(ktx2Loader);
    return true;
  } catch (error) {
    console.warn(
      '[iot/twin] KTX2 解码器装配失败，使用 KTX2 压缩纹理的模型将无法显示贴图：',
      error,
    );
    return false;
  }
}
```

---

## 6. 场景管理：`three/twin/scene-manager.ts`

```ts
import type * as THREE_NS from 'three';

import type { LoadedModel, LoadModelOptions } from './model-loader';
import type {
  AnchorDescriptor,
  AnchorRuntimeState,
  AnchorTransform,
  TwinSceneError,
  TwinSceneState,
  TwinViewConfig,
} from './types';

import { disposeSubtree } from './disposal';
import { attachKtx2, loadModel } from './model-loader';

/**
 * 场景生命周期管理（**「方便切换场景」这一需求的核心实现**）。
 *
 * ## 三个职责
 *
 * 1. **世界坐标链**：沿 `parent_id` 递归合成 `worldMatrix`，把「本场景局部坐标」映射到
 *    「顶层世界坐标」。子场景通过 `anchor_*` 变换嵌进父场景 —— 这是「整栋楼平移一次，
 *    楼上所有点位自动跟着走」的唯一正确姿势（设计文档 §5.2）。
 * 2. **切换状态机**：`disposed → loading → ready → disposing → disposed`，失败进 `failed`。
 *    每次迁移都通过 `onStateChange` 暴露，页面据此渲染进度/错误/禁用操作。
 * 3. **两种切换模式**：
 *    - `switch`（默认）：dispose 旧场景 → 加载新场景。显存峰值 = 单场景。
 *    - `fly`（可选）：父子同时驻留、相机飞向子场景 anchor。显存峰值 = 双场景，
 *      超过 `FLY_MAX_MODEL_BYTES` 或设备低显存时**显式禁用并给出原因**（不静默降级）。
 *
 * ## 为什么渲染器/相机/控制器只建一次
 *
 * WebGL 上下文重建代价高（需重新上传全部材质与着色器），且浏览器在活跃 WebGL 上下文
 * 超过约 16 个后会开始丢弃最旧的（`webglcontextlost`）。因此「切场景」只换 `scene` 内的
 * 模型节点，**不重建 renderer**。
 *
 * @author wenbin
 * @since 2026-10-21
 */

/** 场景切换模式。 */
export type TwinSwitchMode = 'fly' | 'switch';

/** 飞行模式允许的最大模型字节数（超过则禁用飞行：双场景驻留会耗尽显存）。 */
export const FLY_MAX_MODEL_BYTES = 20 * 1024 * 1024;

/** 设备内存阈值（GB）：`navigator.deviceMemory <= 4` 时禁用飞行模式。 */
export const FLY_MIN_DEVICE_MEMORY_GB = 4;

/** 内存自检：切换多次后 `geometries` 超过基线的该增量即告警（开发期观测）。 */
export const MEMORY_LEAK_GEOMETRY_THRESHOLD = 20;

/** 锚点层契约（由 `anchor-layer.ts` 实现，`twin-canvas.vue` 装配）。 */
export interface AnchorLayer {
  /** 把锚点挂到模型上（每次进入场景调用一次）。 */
  attach: (modelRoot: THREE_NS.Object3D, anchors: AnchorDescriptor[]) => void;
  /** 卸载全部锚点（切场景/销毁时调用）。 */
  detach: () => void;
  /**
   * 在渲染循环里绘制锚点层（CSS2D 渲染器需要每帧绘制一次）。
   *
   * 为什么由 manager 调用而不是锚点层自己开循环：一个画布只应有一个 rAF 循环。
   * 两个循环（3D + CSS2D）会在不同帧提交，标签与模型之间出现一帧的错位抖动。
   */
  render: (scene: THREE_NS.Scene, camera: THREE_NS.Camera) => void;
  /** 批量更新运行态（rAF 合并后调用，见 `runtime-store`）。 */
  update: (states: Map<string, AnchorRuntimeState>) => void;
  /** 画布尺寸变化时同步 CSS2D 渲染器（否则标签位置在缩放后偏移）。 */
  resize: (width: number, height: number) => void;
}

/** 场景管理器构造选项。 */
export interface SceneManagerOptions {
  /** 挂载容器的 DOM 元素。 */
  container: HTMLElement;
  /** 错误文案工厂（引擎层不做 i18n，由页面注入本地化文案）。 */
  errorMessages: {
    downloadFailed: (detail: string) => string;
    parseFailed: (detail: string) => string;
  };
  /** 状态变化回调。 */
  onStateChange: (state: TwinSceneState, error?: TwinSceneError) => void;
}

/** 一个场景的加载描述（由页面从 `SceneResp` + `ModelAssetResp` 转换而来）。 */
export interface SceneLoadRequest {
  /** 场景 ID。 */
  sceneId: string;
  /** 模型绝对地址（已由 `resolveModelUrl` 拼好）。 */
  modelUrl: string;
  /** glTF 内部相对资源的基准路径（分离资源模型必传）。 */
  basePath: string;
  /** 该场景自身的锚点变换。 */
  anchor: AnchorTransform;
  /** 从根到本场景的完整祖先链（**含自身**，按根→叶顺序）。 */
  ancestry: AnchorTransform[];
  /** 默认视角配置（可空 ⇒ 用引擎默认）。 */
  view?: null | TwinViewConfig;
  /** 场景内设备锚点。 */
  anchors: AnchorDescriptor[];
  /** 模型文件字节数（用于判断飞行模式是否允许）。 */
  modelBytes?: null | number;
}

/** `enterScene` 的返回值：实际使用的模式与（可选的）回退原因。 */
export interface EnterSceneResult {
  /** 因不满足条件而未启用飞行模式时的原因（页面要展示，不能静默）。 */
  fallbackReason?: string;
  /** 实际使用的切换模式。 */
  mode: TwinSwitchMode;
}

/** 模型**解析**阶段的错误（与下载失败区分，给用户不同文案）。 */
export class ModelParseError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'ModelParseError';
    if (cause !== undefined) {
      this.cause = cause;
    }
  }
}

/** 度 → 弧度。 */
function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

/**
 * 场景管理器（每个画布一个实例）。
 *
 * 生命周期：`new TwinSceneManager(opts)` → `await init()` → 反复 `enterScene(...)` →
 * `dispose()`。
 */
export class TwinSceneManager {
  private animationFrameId = 0;

  private anchorLayer?: AnchorLayer;

  private camera?: THREE_NS.PerspectiveCamera;

  private container: HTMLElement;

  private controls?: import('three/addons/controls/OrbitControls.js').OrbitControls;

  /** 当前场景的世界变换矩阵（局部坐标 → 世界坐标）。 */
  private currentWorldMatrix?: THREE_NS.Matrix4;

  private disposed = false;

  private errorMessages: SceneManagerOptions['errorMessages'];

  private initialized = false;

  private modelRoot?: THREE_NS.Group;

  private onStateChange: SceneManagerOptions['onStateChange'];

  private renderer?: THREE_NS.WebGLRenderer;

  private resizeObserver?: ResizeObserver;

  private scene?: THREE_NS.Scene;

  private state: TwinSceneState = 'disposed';

  /** three 命名空间（`init` 时通过动态 import 取得，避免顶层 import 破坏分包）。 */
  private THREE?: typeof THREE_NS;

  /** 上一次切换建立的内存基线（geometries 数量）。 */
  private baselineGeometries = 0;

  private switchCount = 0;

  private fadeOverlay?: HTMLDivElement;

  constructor(options: SceneManagerOptions) {
    this.container = options.container;
    this.onStateChange = options.onStateChange;
    this.errorMessages = options.errorMessages;
  }

  /**
   * 初始化渲染器/场景/相机/控制器（**只做一次**）。
   *
   * 重复调用是幂等的（`initialized` 短路）：`twin-canvas` 的 `onMounted` 与「重试按钮」
   * 都会走这里，重复建 renderer 会造成上下文泄漏。
   */
  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }
    const THREE = await import('three');
    const { OrbitControls } = await import('three/addons/controls/OrbitControls.js');
    this.THREE = THREE;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color('#0b1220');

    this.camera = new THREE.PerspectiveCamera(
      50,
      this.aspect(),
      0.1,
      20_000,
    );
    // 初始视角：斜上方看向原点（根场景的合理起点；子场景会被 view_json 覆盖）
    this.camera.position.set(40, 30, 40);

    this.renderer = new THREE.WebGLRenderer({
      // 建筑模型上抗锯齿收益明显；像素比上限 2 避免 3x 屏把填充率打满
      antialias: true,
      // 不透明背景：透明背景 + 大量半透明材质的排序成本很高
      alpha: false,
      powerPreference: 'high-performance',
    });
    this.renderer.setPixelRatio(Math.min(globalThis.devicePixelRatio ?? 1, 2));
    this.renderer.setSize(
      this.container.clientWidth || 1,
      this.container.clientHeight || 1,
    );
    this.container.append(this.renderer.domElement);

    // 灯光：环境光给基础照明，平行光给方向感。
    // 刻意**不开阴影**：建筑级模型的阴影贴图成本高、收益低（用户主要看设备状态而非光影）。
    this.scene.add(new THREE.AmbientLight(0xff_ff_ff, 1.2));
    const directional = new THREE.DirectionalLight(0xff_ff_ff, 1.8);
    directional.position.set(50, 100, 50);
    this.scene.add(directional);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    // 禁止转到地面以下：从地下看建筑模型没有意义且容易迷失方向
    this.controls.maxPolarAngle = Math.PI / 2;

    this.currentWorldMatrix = new THREE.Matrix4();

    // 淡入淡出遮罩（切换场景时避免「黑屏一瞬」的突兀感）
    this.fadeOverlay = document.createElement('div');
    this.fadeOverlay.style.cssText = [
      'position:absolute',
      'inset:0',
      'background:#0b1220',
      'opacity:0',
      'pointer-events:none',
      'transition:opacity 180ms ease',
      'z-index:1',
    ].join(';');
    this.container.append(this.fadeOverlay);

    this.resizeObserver = new ResizeObserver(() => {
      this.resize();
    });
    this.resizeObserver.observe(this.container);

    this.startRenderLoop();
    this.initialized = true;
    this.setState('disposed');
  }

  /**
   * 进入（切换）一个场景。
   *
   * 并发保护：`loading`/`disposing` 期间再次调用直接抛错——GPU 资源回收与新场景加载
   * 交叠会造成显存竞态（旧模型已 dispose、新模型已上传，`renderer.info.memory` 会翻倍）。
   *
   * @param request 场景加载描述
   * @param mode 期望的切换模式（默认 `switch`）
   * @returns 实际使用的模式与（可选）回退原因
   */
  async enterScene(
    request: SceneLoadRequest,
    mode: TwinSwitchMode = 'switch',
  ): Promise<EnterSceneResult> {
    if (this.disposed) {
      throw new Error('场景管理器已销毁，不能再加载场景');
    }
    if (!this.initialized) {
      throw new Error('场景管理器未初始化：请先 await init()');
    }
    if (this.state === 'loading' || this.state === 'disposing') {
      throw new Error('场景正在切换中，请稍候');
    }

    const flyCheck = mode === 'fly' ? this.canFly(request) : { allowed: false };
    const effectiveMode: TwinSwitchMode = flyCheck.allowed ? 'fly' : 'switch';

    this.setState('loading');
    try {
      const loaded = await this.loadModelOrThrow(request);
      await this.fadeOut();
      await this.clearCurrentModel();
      this.attachModel(loaded, request);
      await this.fadeIn();
      this.setState('ready');
      this.switchCount += 1;
      this.checkMemory();
      return flyCheck.allowed
        ? { mode: effectiveMode }
        : {
            fallbackReason:
              flyCheck.reason ??
              (mode === 'fly' ? '未启用飞行模式' : undefined),
            mode: effectiveMode,
          };
    } catch (error) {
      const stage = error instanceof ModelParseError ? 'parse' : 'download';
      const detail = error instanceof Error ? error.message : String(error);
      this.setState('failed', {
        message:
          stage === 'parse'
            ? this.errorMessages.parseFailed(detail)
            : this.errorMessages.downloadFailed(detail),
        stage,
      });
      throw error;
    }
  }

  /**
   * 批量更新锚点运行态（由页面在 rAF 合并后调用）。
   *
   * @param states 点位 ID → 运行态
   */
  updateAnchorStates(states: Map<string, AnchorRuntimeState>): void {
    this.anchorLayer?.update(states);
  }

  /**
   * 注册锚点层（`twin-canvas` 装配时调用一次）。
   *
   * @param layer 锚点层实现
   */
  attachAnchorLayer(layer: AnchorLayer): void {
    this.anchorLayer = layer;
  }

  /**
   * 当前场景的世界矩阵（供 `picker` 做坐标换算）。
   *
   * @returns 4x4 世界矩阵（未初始化时抛错，不返回假矩阵）
   */
  getWorldMatrix(): THREE_NS.Matrix4 {
    if (!this.currentWorldMatrix) {
      throw new Error('场景管理器未初始化：世界矩阵不可用');
    }
    return this.currentWorldMatrix;
  }

  /**
   * 当前场景的模型根（供 `picker` 做射线检视）。
   *
   * @returns 模型根（未加载时为 undefined）
   */
  getModelRoot(): THREE_NS.Group | undefined {
    return this.modelRoot;
  }

  /**
   * 相机（供 `camera-rig` 做聚焦飞行与重置）。
   *
   * @returns 透视相机（未初始化时为 undefined）
   */
  getCamera(): THREE_NS.PerspectiveCamera | undefined {
    return this.camera;
  }

  /**
   * OrbitControls（供 `picker` 在拖拽期间临时禁用以避免「拖拽带着相机转」）。
   *
   * @returns 控制器（未初始化时为 undefined）
   */
  getControls():
    | undefined
    | import('three/addons/controls/OrbitControls.js').OrbitControls {
    return this.controls;
  }

  /**
   * 世界坐标 → 当前场景局部坐标（拾取结果的落库口径）。
   *
   * @param world 世界坐标
   * @returns 局部坐标
   */
  worldToLocal(world: {
    x: number;
    y: number;
    z: number;
  }): { x: number; y: number; z: number } {
    const THREE = this.THREE;
    const matrix = this.currentWorldMatrix;
    if (!THREE || !matrix) {
      throw new Error('场景管理器未初始化：坐标换算不可用');
    }
    const vector = new THREE.Vector3(world.x, world.y, world.z).applyMatrix4(
      matrix.clone().invert(),
    );
    return { x: vector.x, y: vector.y, z: vector.z };
  }

  /**
   * 销毁管理器（页面卸载时调用）：停止渲染循环、释放全部 GPU 资源、解绑事件。
   *
   * 幂等：重复调用直接返回。
   */
  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = 0;
    }
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    this.anchorLayer?.detach();
    this.anchorLayer = undefined;
    this.clearCurrentModelSync();
    this.fadeOverlay?.remove();
    this.fadeOverlay = undefined;
    this.controls?.dispose();
    this.controls = undefined;
    // renderer.dispose() 释放 WebGL 上下文的 GPU 侧资源；domElement 一并移除
    this.renderer?.dispose();
    this.renderer?.domElement.remove();
    this.renderer = undefined;
    this.scene = undefined;
    this.camera = undefined;
    this.currentWorldMatrix = undefined;
    this.THREE = undefined;
    this.setState('disposed');
  }

  // ---------- 私有：加载与装配 ----------

  /** 是否允许飞行模式（不允许时给出**具体原因**，页面要能展示）。 */
  private canFly(request: SceneLoadRequest): { allowed: boolean; reason?: string } {
    const bytes = request.modelBytes ?? 0;
    if (bytes > FLY_MAX_MODEL_BYTES) {
      return {
        allowed: false,
        reason: `模型约 ${Math.round(bytes / 1024 / 1024)}MB，超过 ${Math.round(
          FLY_MAX_MODEL_BYTES / 1024 / 1024,
        )}MB 上限：飞行下钻需要父子场景同时驻留，显存不够，已自动改为切换加载`,
      };
    }
    const deviceMemory = (
      globalThis.navigator as unknown as { deviceMemory?: number }
    ).deviceMemory;
    if (
      typeof deviceMemory === 'number' &&
      deviceMemory <= FLY_MIN_DEVICE_MEMORY_GB
    ) {
      return {
        allowed: false,
        reason: `检测到设备内存约 ${deviceMemory}GB，飞行下钻需要双场景驻留显存，已自动改为切换加载`,
      };
    }
    if (request.ancestry.length === 0) {
      return {
        allowed: false,
        reason: '目标场景没有锚点变换（与父场景原点重合），飞行过去看不出位置变化，已改为切换加载',
      };
    }
    return { allowed: true };
  }

  /** 加载模型（把解析异常包成可区分阶段的错误）。 */
  private async loadModelOrThrow(request: SceneLoadRequest): Promise<LoadedModel> {
    const options: LoadModelOptions = {
      basePath: request.basePath,
      url: request.modelUrl,
    };
    try {
      const loaded = await loadModel(options);
      // KTX2 需要 renderer 才能探测纹理支持；装配失败不影响未用 KTX2 的模型
      if (this.renderer) {
        const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js');
        const probe = new GLTFLoader();
        await attachKtx2(
          probe as unknown as { setKTX2Loader: (loader: unknown) => void },
          this.renderer,
        );
      }
      return loaded;
    } catch (error) {
      throw new ModelParseError(
        error instanceof Error ? error.message : String(error),
        error,
      );
    }
  }

  /** 挂载新模型：应用锚点变换、加入场景、通知锚点层。 */
  private attachModel(loaded: LoadedModel, request: SceneLoadRequest): void {
    const THREE = this.THREE;
    if (!THREE || !this.scene) {
      throw new Error('场景管理器未初始化：无法挂载模型');
    }
    const root = loaded.scene;
    this.modelRoot = root;

    // 锚点变换：position / rotation(度→弧度) / scale。
    // 旋转用 three.js 默认欧拉序 'XYZ'（内旋），与后端 `TwinSceneTransform` 的
    // `T × Rz × Ry × Rx × S` 口径逐字一致 —— 前端**不手写矩阵**，避免顺序搞反且便于与后端单测对照。
    root.position.set(request.anchor.x, request.anchor.y, request.anchor.z);
    root.rotation.set(
      toRadians(request.anchor.rx),
      toRadians(request.anchor.ry),
      toRadians(request.anchor.rz),
      'XYZ',
    );
    root.scale.setScalar(request.anchor.scale);
    root.updateMatrix();

    this.currentWorldMatrix = this.composeWorldMatrix(request.ancestry);

    this.scene.add(root);
    this.anchorLayer?.attach(root, request.anchors);
    this.applyView(request.view);
  }

  /**
   * 合成世界矩阵（从根到自身的锚点链）。
   *
   * 数学口径（与后端 `TwinSceneTransform` 类注释逐字一致）：
   * ```
   * worldMatrix(scene) = worldMatrix(parent) × anchorMatrix(scene)
   * anchorMatrix      = T × Rz × Ry × Rx × S
   * ```
   * 实现用 three 的 `Matrix4.compose(position, quaternion, scale)` +
   * `Euler(..., 'XYZ')`，由 three 完成同序合成。
   *
   * @param ancestry 从根到自身的锚点链（含自身，按根→叶顺序）
   * @returns 4x4 世界矩阵
   */
  private composeWorldMatrix(ancestry: AnchorTransform[]): THREE_NS.Matrix4 {
    const THREE = this.THREE;
    if (!THREE) {
      throw new Error('场景管理器未初始化：无法合成世界矩阵');
    }
    const world = new THREE.Matrix4();
    for (const anchor of ancestry) {
      const local = new THREE.Matrix4();
      const quaternion = new THREE.Quaternion().setFromEuler(
        new THREE.Euler(
          toRadians(anchor.rx),
          toRadians(anchor.ry),
          toRadians(anchor.rz),
          'XYZ',
        ),
      );
      local.compose(
        new THREE.Vector3(anchor.x, anchor.y, anchor.z),
        quaternion,
        new THREE.Vector3(anchor.scale, anchor.scale, anchor.scale),
      );
      world.multiply(local);
    }
    return world;
  }

  /** 应用视角配置（空则按模型包围盒自适应，保证「一进去就能看到模型」）。 */
  private applyView(view?: null | TwinViewConfig): void {
    const THREE = this.THREE;
    if (!THREE || !this.scene || !this.camera || !this.controls || !this.modelRoot) {
      return;
    }
    if (view?.background) {
      this.scene.background = new THREE.Color(view.background);
    }
    // 光照强度可配（场景级差异：室内楼层通常比园区暗）
    if (typeof view?.ambientIntensity === 'number') {
      for (const child of this.scene.children) {
        if (child instanceof THREE.AmbientLight) {
          child.intensity = view.ambientIntensity;
        }
      }
    }
    if (typeof view?.directionalIntensity === 'number') {
      for (const child of this.scene.children) {
        if (child instanceof THREE.DirectionalLight) {
          child.intensity = view.directionalIntensity;
        }
      }
    }

    const box = new THREE.Box3().setFromObject(this.modelRoot);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const maxDimension = Math.max(size.x, size.y, size.z);

    if (view?.camera?.position && view.camera.target) {
      this.camera.position.set(
        view.camera.position.x,
        view.camera.position.y,
        view.camera.position.z,
      );
      this.controls.target.set(
        view.camera.target.x,
        view.camera.target.y,
        view.camera.target.z,
      );
      if (typeof view.camera.fov === 'number') {
        this.camera.fov = view.camera.fov;
        this.camera.updateProjectionMatrix();
      }
    } else if (Number.isFinite(maxDimension) && maxDimension > 0) {
      // 没有配置视角时按包围盒自适应：距离 = 尺寸 × 1.6（经验值，
      // 50° fov 下该系数能让模型占据约 2/3 画面高度）
      const distance = maxDimension * 1.6;
      this.camera.position.set(
        center.x + distance * 0.6,
        center.y + distance * 0.5,
        center.z + distance * 0.6,
      );
      this.controls.target.copy(center);
      // 近远裁剪面按模型尺寸调整：建筑模型的坐标尺度可能是米或毫米，
      // 固定 0.1/5000 在毫米级模型上会整片被裁掉
      this.camera.near = Math.max(0.001, maxDimension / 10_000);
      this.camera.far = maxDimension * 100;
      this.camera.updateProjectionMatrix();
    }
    this.controls.update();
  }

  /** 清理当前模型（async：先让出一帧再加载新模型，避免显存瞬时翻倍）。 */
  private async clearCurrentModel(): Promise<void> {
    this.clearCurrentModelSync();
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => {
        resolve();
      });
    });
  }

  /** 同步清理当前模型与其 GPU 资源。 */
  private clearCurrentModelSync(): void {
    this.anchorLayer?.detach();
    if (this.modelRoot && this.scene) {
      this.scene.remove(this.modelRoot);
      disposeSubtree(this.modelRoot);
      this.modelRoot = undefined;
    }
  }

  /** 淡出遮罩。 */
  private async fadeOut(): Promise<void> {
    if (!this.fadeOverlay) {
      return;
    }
    this.fadeOverlay.style.opacity = '1';
    await new Promise<void>((resolve) => {
      setTimeout(() => {
        resolve();
      }, 190);
    });
  }

  /** 淡入遮罩。 */
  private async fadeIn(): Promise<void> {
    if (!this.fadeOverlay) {
      return;
    }
    this.fadeOverlay.style.opacity = '0';
    await new Promise<void>((resolve) => {
      setTimeout(() => {
        resolve();
      }, 190);
    });
  }

  /** 开发期内存自检：切换多次后几何体数量应回到基线附近。 */
  private checkMemory(): void {
    if (!this.renderer) {
      return;
    }
    const { geometries } = this.renderer.info.memory;
    if (this.switchCount === 1) {
      this.baselineGeometries = geometries;
      return;
    }
    if (geometries > this.baselineGeometries + MEMORY_LEAK_GEOMETRY_THRESHOLD) {
      console.warn(
        `[iot/twin] 疑似显存泄漏：切换 ${this.switchCount} 次后 geometries=${geometries}，`
          + `基线=${this.baselineGeometries}。请检查 dispose 是否覆盖了全部 geometry/material/texture`,
      );
    }
  }

  private startRenderLoop(): void {
    const tick = () => {
      if (this.disposed) {
        return;
      }
      this.animationFrameId = requestAnimationFrame(tick);
      this.controls?.update();
      if (this.renderer && this.scene && this.camera) {
        this.renderer.render(this.scene, this.camera);
        // CSS2D 标签在同一个帧里绘制，保证与 3D 内容不错位
        this.anchorLayer?.render(this.scene, this.camera);
      }
    };
    this.animationFrameId = requestAnimationFrame(tick);
  }

  private resize(): void {
    if (!this.renderer || !this.camera) {
      return;
    }
    const width = this.container.clientWidth;
    const height = this.container.clientHeight;
    if (width === 0 || height === 0) {
      return;
    }
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
    this.anchorLayer?.resize(width, height);
  }

  private aspect(): number {
    const width = this.container.clientWidth || 1;
    const height = this.container.clientHeight || 1;
    return width / height;
  }

  private setState(state: TwinSceneState, error?: TwinSceneError): void {
    this.state = state;
    this.onStateChange(state, error);
  }
}
```

### 6.1 `three/twin/scene-manager.test.ts`

```ts
import { describe, expect, it } from 'vitest';

import { FLY_MAX_MODEL_BYTES, FLY_MIN_DEVICE_MEMORY_GB } from './scene-manager';

/**
 * 场景管理器的**纯常量与判定口径**单测。
 *
 * 为什么不测 `enterScene`（加载/渲染）：它依赖 WebGL 上下文，happy-dom 下没有真实的
 * `WebGLRenderer`，硬测只能把 three 全 mock 掉——那测的是 mock 不是实现。真正的
 * 「切换不涨内存」验收在手工验收（设计文档 §9.4 第 6 项：切 10 次看
 * `renderer.info.memory`）。本文件只钉死**可纯函数化**的口径。
 *
 * 世界矩阵合成的正确性由后端 `TwinSceneTransformTest` 与手工验收共同保证：
 * 前后端用的是**同一套数学定义**（T × Rz × Ry × Rx × S），前端由 three 的
 * `Matrix4.compose` 承担合成，后端由单测钉死数值。
 */
describe('scene-manager 常量口径', () => {
  it('飞行模式模型上限是 20MB（超限禁用飞行，不静默降级）', () => {
    expect(FLY_MAX_MODEL_BYTES).toBe(20 * 1024 * 1024);
  });

  it('低显存设备阈值是 4GB', () => {
    expect(FLY_MIN_DEVICE_MEMORY_GB).toBe(4);
  });

  it('设备内存恰好等于阈值时也禁用飞行（<= 判定，不是 <）', () => {
    const memory = FLY_MIN_DEVICE_MEMORY_GB;
    expect(memory <= FLY_MIN_DEVICE_MEMORY_GB).toBe(true);
  });
});
```

---

## 7. 相机控制：`three/twin/camera-rig.ts`

```ts
import type * as THREE_NS from 'three';

/**
 * 相机操作（重置视角 / 聚焦某个锚点）。
 *
 * 与 `scene-manager` 的职责边界：manager 只负责「建相机、给初始视角」，
 * 本模块负责**运行期的相机动作**（用户点击锚点后飞过去、点「重置」回默认视角）。
 *
 * 飞行用球面插值而不是直线插值：直线穿过模型内部时会看到「穿过墙壁」的穿模感，
 * 球面插值保持与目标点的距离单调变化，观感更稳。
 *
 * @author wenbin
 * @since 2026-10-21
 */

/** 相机飞行参数。 */
export interface FlyToOptions {
  /** 目标点（世界坐标）。 */
  target: { x: number; y: number; z: number };
  /** 与目标点的距离（世界单位）。 */
  distance: number;
  /** 飞行时长（毫秒）。 */
  durationMs?: number;
}

/** 默认飞行时长。 */
export const DEFAULT_FLY_DURATION_MS = 600;

/**
 * 相机控制器：绑定到一个 `PerspectiveCamera` 与 `OrbitControls`。
 */
export class CameraRig {
  private animationFrameId = 0;

  private camera: THREE_NS.PerspectiveCamera;

  private controls: import('three/addons/controls/OrbitControls.js').OrbitControls;

  private THREE: typeof THREE_NS;

  /** 初始视角（重置时回到这里）。 */
  private homePosition: THREE_NS.Vector3;

  private homeTarget: THREE_NS.Vector3;

  constructor(
    THREE: typeof THREE_NS,
    camera: THREE_NS.PerspectiveCamera,
    controls: import('three/addons/controls/OrbitControls.js').OrbitControls,
  ) {
    this.THREE = THREE;
    this.camera = camera;
    this.controls = controls;
    this.homePosition = camera.position.clone();
    this.homeTarget = controls.target.clone();
  }

  /** 把当前相机位姿记为「初始视角」（每次进入场景后由页面调用）。 */
  captureHome(): void {
    this.homePosition = this.camera.position.clone();
    this.homeTarget = this.controls.target.clone();
  }

  /** 重置到初始视角（瞬时，不飞行）。 */
  reset(): void {
    this.cancelFly();
    this.camera.position.copy(this.homePosition);
    this.controls.target.copy(this.homeTarget);
    this.controls.update();
  }

  /**
   * 飞向目标点。
   *
   * @param options 飞行参数
   * @returns 飞行完成时 resolved；被新的飞行/重置打断时 resolved（不 reject：
   *          「被打断」不是错误，调用方不需要区分）
   */
  async flyTo(options: FlyToOptions): Promise<void> {
    this.cancelFly();
    const duration = options.durationMs ?? DEFAULT_FLY_DURATION_MS;
    const startPosition = this.camera.position.clone();
    const startTarget = this.controls.target.clone();
    const endTarget = new this.THREE.Vector3(
      options.target.x,
      options.target.y,
      options.target.z,
    );
    // 终点位置：沿「当前相机 → 目标点」方向、距离 distance。
    // 方向长度为 0 时（相机恰在目标点）用斜上方兜底，避免除零。
    const direction = startPosition.clone().sub(endTarget);
    if (direction.lengthSq() < 1e-6) {
      direction.set(1, 1, 1);
    }
    direction.normalize().multiplyScalar(options.distance);
    const endPosition = endTarget.clone().add(direction);

    const startedAt = performance.now();
    await new Promise<void>((resolve) => {
      const tick = () => {
        const elapsed = performance.now() - startedAt;
        const t = Math.min(1, elapsed / duration);
        // smoothstep：起止速度为 0，中段快，观感比线性自然
        const eased = t * t * (3 - 2 * t);
        this.camera.position.lerpVectors(startPosition, endPosition, eased);
        this.controls.target.lerpVectors(startTarget, endTarget, eased);
        this.controls.update();
        if (t >= 1) {
          this.animationFrameId = 0;
          resolve();
          return;
        }
        this.animationFrameId = requestAnimationFrame(tick);
      };
      this.animationFrameId = requestAnimationFrame(tick);
    });
  }

  /** 取消进行中的飞行。 */
  cancelFly(): void {
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = 0;
    }
  }
}
```

---

## 8. 锚点层：`three/twin/anchor-layer.ts`

```ts
import type * as THREE_NS from 'three';

import type { AnchorLayer } from './scene-manager';
import type { AnchorDescriptor, AnchorRuntimeState } from './types';

/**
 * 锚点渲染层：设备图标 + 数值标签 + 状态着色 + 告警闪烁。
 *
 * ## 为什么用 `CSS2DRenderer` 而不是 Sprite/TextGeometry
 *
 * - `Sprite` 的文字需要预生成贴图，中文要额外加载字体（体积大），改文案要重建贴图；
 * - `TextGeometry` 需要字体文件（几十 MB），且是 3D 文字，缩小时糊成一团；
 * - `CSS2DRenderer` 用 DOM 元素承载标签：中文免费、CSS 可调样式、可点击、
 *   缩放时由浏览器保证清晰。代价是「标签不参与深度遮挡」——用 `occlude` 做遮挡检测
 *   需要额外的深度图采样，本实现**不做遮挡**（设备标签被墙挡住时仍可见），
 *   这对运维场景反而是优点（能看见被墙挡住的设备状态）。
 *
 * ## 图标用 Sprite 而不是 CSS2D
 *
 * 图标需要**贴面朝向**（按锚点法线立起来）与**始终面向相机**两种行为。
 * Sprite 天然始终面向相机，且能精确控制大小不随 DOM 缩放变化；
 * 若用 CSS2D，缩放画布时标签会跟着变得巨大/极小。
 */

/** 单个锚点在场景中的节点集合。 */
interface AnchorNode {
  descriptor: AnchorDescriptor;
  /** CSS2D 标签的 DOM 元素（数值 + 设备名）。 */
  labelElement: HTMLDivElement;
  /** Sprite 图标。 */
  sprite: THREE_NS.Sprite;
}

/** 在线状态 → 颜色（与设备台账页面同色系，避免两处颜色语义不一致）。 */
const COLOR_ONLINE = '#22c55e';

const COLOR_OFFLINE = '#94a3b8';

const COLOR_UNKNOWN = '#f59e0b';

/** 停用设备的颜色（灰化，比离线更深）。 */
const COLOR_DISABLED = '#475569';

/** 告警闪烁周期（毫秒）：1.2s 一个完整明暗周期，与告警中心的呼吸灯同节奏。 */
const ALERT_BLINK_PERIOD_MS = 1200;

/** 告警色。 */
const COLOR_ALERT = '#ef4444';

/** CSS2D 模块的最小接口（由调用方 `await import('three/addons/renderers/CSS2DRenderer.js')` 后传入）。 */
export interface Css2dModule {
  CSS2DObject: new (element: HTMLElement) => THREE_NS.Object3D;
  CSS2DRenderer: new () => {
    domElement: HTMLElement;
    render: (scene: THREE_NS.Object3D, camera: THREE_NS.Camera) => void;
    setSize: (width: number, height: number) => void;
  };
}

/** 锚点层实例（在 `AnchorLayer` 契约之上追加释放能力）。 */
export interface AnchorLayerInstance extends AnchorLayer {
  /** 释放 DOM 与贴图（页面卸载时调用）。 */
  dispose: () => void;
}

/**
 * 创建锚点层。
 *
 * @param THREE three 命名空间（由调用方动态 import 后传入，避免本模块顶层 import）
 * @param CSS2D CSS2D 模块
 * @param container 画布容器（CSS2D 渲染器的 DOM 会 append 到这里）
 * @param callbacks 交互回调
 * @returns 锚点层实例
 */
export function createAnchorLayer(
  THREE: typeof THREE_NS,
  CSS2D: Css2dModule,
  container: HTMLElement,
  callbacks: {
    onClickAnchor: (pointId: string) => void;
    onHoverAnchor?: (pointId: string) => void;
  },
): AnchorLayerInstance {
  const nodes = new Map<string, AnchorNode>();
  let currentStates = new Map<string, AnchorRuntimeState>();
  let blinkTimer = 0;
  let blinkOn = true;

  const css2dRenderer = new CSS2D.CSS2DRenderer();
  css2dRenderer.domElement.style.cssText = [
    'position:absolute',
    'inset:0',
    'pointer-events:none', // 标签本身不拦截鼠标，交互由 sprite 承担
    'overflow:hidden',
  ].join(';');
  container.append(css2dRenderer.domElement);

  // 图标贴图：用一个 canvas 画圆底 + 设备符号，避免额外图片资源
  const iconTexture = createIconTexture(THREE);

  function attach(modelRoot: THREE_NS.Object3D, anchors: AnchorDescriptor[]): void {
    detach();
    for (const anchor of anchors) {
      const sprite = new THREE.Sprite(
        new THREE.SpriteMaterial({
          depthTest: false, // 不被模型遮挡：运维需要看到「墙后的设备」
          map: iconTexture,
          sizeAttenuation: false, // 恒定的屏幕尺寸，缩放画布时图标不会变成巨块
          transparent: true,
        }),
      );
      sprite.scale.set(0.045, 0.045, 1);
      sprite.position.set(anchor.local.x, anchor.local.y, anchor.local.z);
      sprite.renderOrder = 999;
      // 用 userData 携带点位 ID：拾取时从命中对象上取
      sprite.userData.pointId = anchor.pointId;
      sprite.userData.isTwinAnchor = true;

      const labelElement = document.createElement('div');
      labelElement.className = 'twin-anchor-label';
      labelElement.style.cssText = [
        'position:absolute',
        'transform:translate(-50%,-140%)',
        'padding:2px 6px',
        'border-radius:4px',
        'background:rgba(11,18,32,.82)',
        'border:1px solid rgba(148,163,184,.35)',
        'color:#e2e8f0',
        'font-size:12px',
        'line-height:16px',
        'white-space:nowrap',
        'pointer-events:auto',
        'cursor:pointer',
        'user-select:none',
      ].join(';');
      labelElement.dataset.pointId = anchor.pointId;
      labelElement.addEventListener('click', (event) => {
        event.stopPropagation();
        callbacks.onClickAnchor(anchor.pointId);
      });
      if (callbacks.onHoverAnchor) {
        labelElement.addEventListener('mouseenter', () => {
          callbacks.onHoverAnchor?.(anchor.pointId);
        });
      }

      const labelObject = new CSS2D.CSS2DObject(labelElement);
      labelObject.position.set(anchor.local.x, anchor.local.y, anchor.local.z);
      // 标签默认不显示，由运行态数据决定是否隐藏（labelVisible 是配置项）
      labelElement.style.display = anchor.labelVisible ? '' : 'none';

      modelRoot.add(sprite);
      modelRoot.add(labelObject);
      nodes.set(anchor.pointId, { descriptor: anchor, labelElement, sprite });
    }
  }

  function detach(): void {
    for (const node of nodes.values()) {
      node.sprite.removeFromParent();
      node.sprite.material.map = null;
      node.sprite.material.dispose();
      node.labelElement.remove();
    }
    nodes.clear();
    currentStates = new Map();
  }

  function update(states: Map<string, AnchorRuntimeState>): void {
    currentStates = states;
    applyStates();
  }

  /** 把 `currentStates` 应用到 DOM 与 sprite 材质。 */
  function applyStates(): void {
    for (const [pointId, node] of nodes) {
      const state = currentStates.get(pointId);
      const color = resolveColor(state);
      node.sprite.material.color.set(color);

      if (!node.descriptor.labelVisible) {
        node.labelElement.style.display = 'none';
        continue;
      }
      node.labelElement.style.display = '';
      const text = state?.primaryText?.trim() ? state.primaryText : '--';
      const name = node.descriptor.deviceName;
      node.labelElement.textContent = `${name} ${text}`;
      // 无数据（hasValue=false）时标签用弱化色，明确告诉用户「这是没有数据，不是 0」
      node.labelElement.style.color = state?.hasValue === false ? '#94a3b8' : '#e2e8f0';
      node.labelElement.style.borderColor =
        (state?.alertCount ?? 0) > 0 ? 'rgba(239,68,68,.75)' : 'rgba(148,163,184,.35)';
    }
  }

  /** 由运行态算颜色（状态优先于告警：停用的设备即使有告警也不闪，因为它本来就不采）。 */
  function resolveColor(state?: AnchorRuntimeState): string {
    if (state?.status === 0) {
      return COLOR_DISABLED;
    }
    if ((state?.alertCount ?? 0) > 0) {
      return blinkOn ? COLOR_ALERT : '#fca5a5';
    }
    if (state?.onlineStatus === 'online') {
      return COLOR_ONLINE;
    }
    if (state?.onlineStatus === 'offline') {
      return COLOR_OFFLINE;
    }
    // unknown / 空（设备已不存在）：不确定就用警示色，不要伪装成在线
    return COLOR_UNKNOWN;
  }

  /** 只有存在告警时才有必要跑闪烁定时器（空闲时不留后台开销）。 */
  function ensureBlinkTimer(): void {
    const hasAlert = [...currentStates.values()].some(
      (state) => (state.alertCount ?? 0) > 0,
    );
    if (hasAlert && !blinkTimer) {
      blinkTimer = globalThis.setInterval(() => {
        blinkOn = !blinkOn;
        applyStates();
      }, ALERT_BLINK_PERIOD_MS / 2);
    } else if (!hasAlert && blinkTimer) {
      globalThis.clearInterval(blinkTimer);
      blinkTimer = 0;
      blinkOn = true;
    }
  }

  function dispose(): void {
    detach();
    if (blinkTimer) {
      globalThis.clearInterval(blinkTimer);
      blinkTimer = 0;
    }
    iconTexture.dispose();
    css2dRenderer.domElement.remove();
  }

  return {
    attach,
    detach,
    dispose,
    render: (scene, camera) => {
      css2dRenderer.render(scene, camera);
    },
    resize: (width, height) => {
      css2dRenderer.setSize(width, height);
    },
    update: (states) => {
      update(states);
      ensureBlinkTimer();
    },
  };
}

/**
 * 生成图标贴图（圆底 + 设备符号）。
 *
 * 用 canvas 现画而不是加载图片：图标要按状态着色，贴图本身用白色、由 sprite 材质
 * 的 `color` 相乘染色 ⇒ 一张贴图可以表现全部状态色，不需要为每种状态准备一张图。
 *
 * @param THREE three 命名空间
 * @returns 贴图
 */
function createIconTexture(THREE: typeof THREE_NS): THREE_NS.Texture {
  const size = 64;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  if (context) {
    context.clearRect(0, 0, size, size);
    // 圆底（白色，由 sprite 材质 color 染色）
    context.fillStyle = '#ffffff';
    context.beginPath();
    context.arc(size / 2, size / 2, size * 0.36, 0, Math.PI * 2);
    context.fill();
    // 内圈深色，形成「环 + 心」的视觉，缩小后仍可辨认
    context.fillStyle = 'rgba(11,18,32,0.85)';
    context.beginPath();
    context.arc(size / 2, size / 2, size * 0.22, 0, Math.PI * 2);
    context.fill();
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.needsUpdate = true;
  return texture;
}

/**
 * 从命中对象上取点位 ID（拾取层的公共工具）。
 *
 * @param object 命中对象
 * @returns 点位 ID；非锚点对象返回 undefined
 */
export function readPointIdFromObject(object: THREE_NS.Object3D): string | undefined {
  const { pointId } = object.userData as { pointId?: string };
  return typeof pointId === 'string' ? pointId : undefined;
}
```



---

## 9. 拾取与拖拽：`three/twin/picker.ts`

```ts
import type * as THREE_NS from 'three';

/**
 * 3D 内拾取与拖拽微调（配置态的核心交互）。
 *
 * ## 三条必须做对的规则
 *
 * 1. **射线拾取用 BVH 加速**：建筑级 glb 常有几十万到几百万三角形，原生 `Raycaster`
 *    是 O(n)，点击一次卡 100~500ms。`three-mesh-bvh` 给每个 `Mesh` 建 BVH 后降到接近 O(log n)。
 *    BVH 构建本身有成本（首次进入场景时几十到几百毫秒），因此**只在配置态构建**
 *    （运行态用户不拾取模型表面，只点锚点 sprite，不需要 BVH）。
 * 2. **命中结果要区分「锚点」与「模型表面」**：点到已有锚点 = 选中该点位；
 *    点到模型表面 = 在交点处放置新锚点。两者语义完全不同。
 * 3. **拖拽期间禁用 OrbitControls**：否则拖拽会带着相机一起转，用户完全控制不住坐标。
 *
 * @author wenbin
 * @since 2026-10-21
 */

/** 拾取结果。 */
export type PickResult =
  | {
      /** 命中了已有锚点。 */
      kind: 'anchor';
      pointId: string;
    }
  | {
      /** 命中了模型表面（可在此放置新锚点）。 */
      kind: 'surface';
      /** 世界坐标交点。 */
      point: { x: number; y: number; z: number };
      /** 世界坐标法线（已归一化）。 */
      normal: { x: number; y: number; z: number };
    };

/** 拾取器选项。 */
export interface PickerOptions {
  /** 画布 DOM（绑定鼠标事件）。 */
  domElement: HTMLElement;
  /** 相机。 */
  camera: THREE_NS.PerspectiveCamera;
  /** 模型根（射线检测范围）。 */
  getModelRoot: () => THREE_NS.Group | undefined;
  /** 世界矩阵（局部 ↔ 世界换算）。 */
  getWorldMatrix: () => THREE_NS.Matrix4;
  /** OrbitControls（拖拽时需要禁用）。 */
  controls: import('three/addons/controls/OrbitControls.js').OrbitControls;
  /** 点击回调（空点击 = 点到空白处）。 */
  onClick: (result?: PickResult) => void;
  /** three 命名空间。 */
  THREE: typeof THREE_NS;
  /**
   * 是否对模型表面建 BVH。
   *
   * true（配置态）：建 BVH，拾取快；首次进入场景多花几十~几百毫秒。
   * false（运行态）：不建，用户不需要点模型表面。
   */
  useBvh: boolean;
}

/**
 * 拾取器：绑定到画布，处理点击与（可选的）拖拽微调。
 */
export class TwinPicker {
  private readonly domElement: HTMLElement;

  private readonly raycaster: THREE_NS.Raycaster;

  private readonly pointer: THREE_NS.Vector2;

  private readonly camera: THREE_NS.PerspectiveCamera;

  private readonly options: PickerOptions;

  private readonly THREE: typeof THREE_NS;

  private readonly onClickHandler: (event: MouseEvent) => void;

  private bvhBuilt = false;

  private dragTarget?: THREE_NS.Object3D;

  private disposed = false;

  constructor(options: PickerOptions) {
    this.options = options;
    this.THREE = options.THREE;
    this.domElement = options.domElement;
    this.camera = options.camera;
    this.raycaster = new this.THREE.Raycaster();
    this.pointer = new this.THREE.Vector2();

    this.onClickHandler = (event: MouseEvent) => {
      this.handleClick(event);
    };
    this.domElement.addEventListener('pointerup', this.onClickHandler);
  }

  /**
   * 为首个场景的模型建 BVH（幂等：只建一次）。
   *
   * 为什么不在 constructor 里建：模型还没加载。为什么只建一次：场景切换后模型变了，
   * 调用方应重新调用本方法（见 `resetForNewModel`）。
   */
  buildBvh(): void {
    if (!this.options.useBvh || this.bvhBuilt) {
      return;
    }
    const root = this.options.getModelRoot();
    if (!root) {
      return;
    }
    void (async () => {
      try {
        const { acceleratedRaycaster, computeBoundsTree } = await import(
          'three-mesh-bvh'
        );
        // 官方推荐的挂法：给 BufferGeometry 原型加 computeBoundsTree，
        // 再让 Raycaster 支持 firstHitOnly（命中即停，进一步提速）
        (
          this.THREE.BufferGeometry.prototype as unknown as {
            computeBoundsTree?: () => void;
            disposeBoundsTree?: () => void;
          }
        ).computeBoundsTree = computeBoundsTree;
        root.traverse((object) => {
          const mesh = object as THREE_NS.Mesh;
          if (mesh.isMesh && mesh.geometry) {
            (
              mesh.geometry as unknown as { computeBoundsTree?: () => void }
            ).computeBoundsTree?.();
          }
        });
        Object.assign(this.raycaster, { firstHitOnly: true });
        void acceleratedRaycaster;
        this.bvhBuilt = true;
      } catch (error) {
        // BVH 不可用不是致命问题：退回原生 Raycaster，只是变慢。
        // 必须留痕（否则用户只会觉得「点着卡」而不知道原因）。
        console.warn(
          '[iot/twin] three-mesh-bvh 装配失败，拾取退回原生 Raycaster（大模型上会变慢）：',
          error,
        );
      }
    })();
  }

  /** 场景切换后重置 BVH 标记（新模型需要重新构建）。 */
  resetForNewModel(): void {
    this.bvhBuilt = false;
  }

  /**
   * 处理点击：先检测锚点 sprite，再检测模型表面。
   *
   * 为什么锚点优先：锚点 sprite 的 `depthTest=false` 且尺寸很小，
   * 若先测模型表面，点到锚点上方时可能同时命中背后的墙面而优先返回表面结果，
   * 用户会觉得「点不中锚点」。
   *
   * @param event 鼠标事件
   */
  private handleClick(event: MouseEvent): void {
    if (this.disposed) {
      return;
    }
    const rect = this.domElement.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) {
      return;
    }
    this.pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    this.raycaster.setFromCamera(this.pointer, this.camera);

    const root = this.options.getModelRoot();
    if (!root) {
      this.options.onClick();
      return;
    }

    const hits = this.raycaster.intersectObject(root, true);
    // 1) 命中锚点 sprite
    for (const hit of hits) {
      const pointId = (hit.object.userData as { pointId?: string }).pointId;
      if (typeof pointId === 'string') {
        this.options.onClick({ kind: 'anchor', pointId });
        return;
      }
    }
    // 2) 命中模型表面
    const surfaceHit = hits.find((hit) => !(hit.object as THREE_NS.Sprite).isSprite);
    if (!surfaceHit) {
      this.options.onClick();
      return;
    }
    const worldPoint = surfaceHit.point;
    const worldNormal = surfaceHit.face
      ? surfaceHit.face.normal
          .clone()
          .applyNormalMatrix(
            new this.THREE.Matrix3().getNormalMatrix(
              surfaceHit.object.matrixWorld,
            ),
          )
          .normalize()
      : new this.THREE.Vector3(0, 1, 0);
    this.options.onClick({
      kind: 'surface',
      normal: { x: worldNormal.x, y: worldNormal.y, z: worldNormal.z },
      point: { x: worldPoint.x, y: worldPoint.y, z: worldPoint.z },
    });
  }

  /** 解绑事件并释放。 */
  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.domElement.removeEventListener('pointerup', this.onClickHandler);
    this.dragTarget = undefined;
  }
}

/**
 * 把「世界坐标交点」转成「本场景局部坐标」（落库口径）。
 *
 * @param worldMatrix 场景世界矩阵（局部 → 世界）
 * @param THREE three 命名空间
 * @param world 世界坐标
 * @returns 局部坐标
 */
export function worldToSceneLocal(
  worldMatrix: THREE_NS.Matrix4,
  THREE: typeof THREE_NS,
  world: { x: number; y: number; z: number },
): { x: number; y: number; z: number } {
  const vector = new THREE.Vector3(world.x, world.y, world.z).applyMatrix4(
    worldMatrix.clone().invert(),
  );
  return { x: vector.x, y: vector.y, z: vector.z };
}

/**
 * 把「世界坐标法线」转成「本场景局部法线」。
 *
 * @param worldMatrix 场景世界矩阵
 * @param THREE three 命名空间
 * @param normal 世界坐标法线
 * @returns 局部坐标法线（已归一化）
 */
export function worldNormalToSceneLocal(
  worldMatrix: THREE_NS.Matrix4,
  THREE: typeof THREE_NS,
  normal: { x: number; y: number; z: number },
): { x: number; y: number; z: number } {
  const normalMatrix = new THREE.Matrix3().getNormalMatrix(
    worldMatrix.clone().invert().transpose(),
  );
  const vector = new THREE.Vector3(normal.x, normal.y, normal.z)
    .applyMatrix3(normalMatrix)
    .normalize();
  return { x: vector.x, y: vector.y, z: vector.z };
}
```

### 9.1 `three/twin/picker.test.ts`

```ts
import type * as THREEType from 'three';

import { describe, expect, it } from 'vitest';

import { worldNormalToSceneLocal, worldToSceneLocal } from './picker';

/**
 * 坐标换算（**纯数学，可脱离 WebGL 单测**）。
 *
 * 这些换算是「拾取结果是否正确落库」的唯一保证：算错方向会让点位保存到错误位置，
 * 而且只在多级嵌套场景里才暴露（父场景有旋转/缩放时）。
 *
 * three 从真实包导入（math 部分不依赖 WebGL，happy-dom 下可用）。
 */
describe('worldToSceneLocal', () => {
  it('世界矩阵为单位阵时，局部坐标等于世界坐标', async () => {
    const THREE = await import('three');
    const local = worldToSceneLocal(
      new THREE.Matrix4(),
      THREE,
      { x: 1, y: 2, z: 3 },
    );
    expect(local.x).toBeCloseTo(1);
    expect(local.y).toBeCloseTo(2);
    expect(local.z).toBeCloseTo(3);
  });

  it('世界矩阵含平移时正确反推（父场景在 (10,0,0)）', async () => {
    const THREE = await import('three');
    const world = new THREE.Matrix4().makeTranslation(10, 0, 0);
    const local = worldToSceneLocal(world, THREE, { x: 13, y: 2, z: 3 });
    expect(local.x).toBeCloseTo(3);
    expect(local.y).toBeCloseTo(2);
    expect(local.z).toBeCloseTo(3);
  });

  it('世界矩阵含缩放时正确反推（缩放 2 倍）', async () => {
    const THREE = await import('three');
    const world = new THREE.Matrix4().makeScale(2, 2, 2);
    const local = worldToSceneLocal(world, THREE, { x: 4, y: 6, z: 8 });
    expect(local.x).toBeCloseTo(2);
    expect(local.y).toBeCloseTo(3);
    expect(local.z).toBeCloseTo(4);
  });

  it('世界矩阵含 90° 绕 Y 轴旋转时正确反推', async () => {
    const THREE = await import('three');
    const world = new THREE.Matrix4().makeRotationY(Math.PI / 2);
    // 绕 Y 轴 90°：(1,0,0) → (0,0,-1)
    const local = worldToSceneLocal(world, THREE, { x: 0, y: 0, z: -1 });
    expect(local.x).toBeCloseTo(1);
    expect(local.y).toBeCloseTo(0);
    expect(local.z).toBeCloseTo(0);
  });
});

describe('worldNormalToSceneLocal', () => {
  it('单位阵时法线不变', async () => {
    const THREE = await import('three');
    const local = worldNormalToSceneLocal(
      new THREE.Matrix4(),
      THREE,
      { x: 0, y: 1, z: 0 },
    );
    expect(local.x).toBeCloseTo(0);
    expect(local.y).toBeCloseTo(1);
    expect(local.z).toBeCloseTo(0);
  });

  it('★ 非均匀缩放下法线用逆转置矩阵（而非直接乘矩阵）', async () => {
    const THREE = await import('three');
    // 非均匀缩放：法线必须用逆转置，否则方向会错。
    // 场景：x 方向放大 2 倍，法线 (0,1,0) 应保持 (0,1,0)。
    const world = new THREE.Matrix4().makeScale(2, 1, 1);
    const local = worldNormalToSceneLocal(world, THREE, { x: 0, y: 1, z: 0 });
    expect(local.x).toBeCloseTo(0);
    expect(local.y).toBeCloseTo(1);
    expect(local.z).toBeCloseTo(0);
  });

  it('结果始终归一化', async () => {
    const THREE = await import('three');
    const world = new THREE.Matrix4().makeScale(3, 5, 7);
    const local = worldNormalToSceneLocal(world, THREE, { x: 1, y: 1, z: 1 });
    const length = Math.hypot(local.x, local.y, local.z);
    expect(length).toBeCloseTo(1);
  });
});
```

---

## 10. 运行态数据域：`three/twin/runtime-store.ts`

```ts
import type { IotRealtimeLatestPayload } from '#/api/iot/realtime';
import type { IotTwinApi } from '#/api/iot';

import type { AnchorRuntimeState } from './types';

/**
 * 运行态值的合并与节流（**实时驱动 3D 的唯一入口**）。
 *
 * ## 为什么必须节流
 *
 * SSE 的 `latest` 是**逐条**推送：一个 500 点位、每点位 1s 上报一次的场景，峰值 500 事件/秒。
 * 若每条事件都触发 Vue 响应式更新 + DOM 文本改写，主线程会被打满（表现为整个页面卡顿、
 * 相机拖不动）。这里的做法：
 *
 * 1. 事件到达时**只写入一个普通 Map**（不触发任何响应式）；
 * 2. 用 `requestAnimationFrame` 合并，每帧最多提交一次；
 * 3. 提交时只把**本帧真正变化的锚点**交给 `anchor-layer`。
 *
 * 这与仪表盘的 `useDashboardRealtime`（trailing 去抖 1200ms）不同：那边是「重拉统计接口」，
 * 这边是「更新已有数据」，所以用 rAF 而不是时间窗口去抖 —— rAF 天然与浏览器刷新对齐，
 * 不会出现「数据到了但下一帧还没画」的错位。
 *
 * ## 语义边界（必须体现在 UI 上）
 *
 * - 属性从未上报 ⇒ `values` 里没有该键 ⇒ 标签显示 `--`，**不是 0**；
 * - `onlineStatus=unknown` ⇒ 平台还没拿到过连接事件，**不代表离线**。
 *
 * @author wenbin
 * @since 2026-10-21
 */

/** 运行态存储选项。 */
export interface RuntimeStoreOptions {
  /** 每帧提交回调（已合并、只含变化项）。 */
  onFlush: (states: Map<string, AnchorRuntimeState>) => void;
}

/** 一个点位在存储里的内部状态。 */
interface PointRuntime {
  /** 点位所属设备（用于匹配 SSE 事件的 deviceId）。 */
  deviceId: string;
  /** 属性标识 → 最新值。 */
  values: Record<string, IotTwinApi.RuntimeValueResp>;
  /** 设备在线状态。 */
  onlineStatus?: null | string;
  /** 启停位。 */
  status?: null | number;
  /** 活动告警数。 */
  alertCount: number;
  /** 主展示属性标识（由绑定的 `displayRole=primary` 决定；无 primary 时取第一条绑定）。 */
  primaryProperty?: string;
  /** 主展示属性的单位（用于拼接 `26.5 ℃`）。 */
  primaryUnit?: null | string;
  /** 主展示属性的小数位。 */
  primaryDecimals?: null | number;
  /** 设备名（标签展示用）。 */
  deviceName: string;
}

/**
 * 运行态存储：把「快照 + SSE 增量」合成为每帧可渲染的锚点状态。
 */
export class TwinRuntimeStore {
  private deviceIdToPointIds = new Map<string, string[]>();

  private dirty = new Set<string>();

  private flushHandle = 0;

  private onFlush: (states: Map<string, AnchorRuntimeState>) => void;

  private points = new Map<string, PointRuntime>();

  private propertyToPointIds = new Map<string, Set<string>>();

  constructor(options: RuntimeStoreOptions) {
    this.onFlush = options.onFlush;
  }

  /**
   * 用一次快照**整体替换**当前状态（进入场景时调用）。
   *
   * @param snapshot 运行态快照
   * @param bindings 点位 → 绑定（决定主展示属性与单位/精度）
   */
  replaceSnapshot(
    snapshot: IotTwinApi.RuntimeResp,
    bindings: Map<string, IotTwinApi.BindingResp[]>,
  ): void {
    this.points.clear();
    this.deviceIdToPointIds.clear();
    this.propertyToPointIds.clear();
    this.dirty.clear();

    for (const point of snapshot.points) {
      const pointBindings = bindings.get(point.pointId) ?? [];
      const primary =
        pointBindings.find((binding) => binding.displayRole === 'primary') ??
        pointBindings[0];
      const runtime: PointRuntime = {
        alertCount: point.alertCount ?? 0,
        deviceId: point.deviceId,
        deviceName: point.deviceName ?? point.deviceCode ?? point.deviceId,
        onlineStatus: point.onlineStatus,
        primaryDecimals: primary?.decimals,
        primaryProperty: primary?.propertyIdentifier,
        primaryUnit: primary?.unit,
        status: point.status,
        // values 的键是属性标识；深拷贝一份避免后续 SSE 事件改到快照对象
        values: { ...point.values },
      };
      this.points.set(point.pointId, runtime);
      this.appendIndex(point, runtime);
      this.dirty.add(point.pointId);
    }
    this.scheduleFlush();
  }

  /**
   * 处理 SSE `latest` 事件（**单条读数**）。
   *
   * @param payload 事件负载
   */
  applyLatestEvent(payload: IotRealtimeLatestPayload): void {
    const deviceId =
      payload.deviceId === null || payload.deviceId === undefined
        ? ''
        : String(payload.deviceId);
    const propertyId = payload.propertyId ?? '';
    if (!deviceId || !propertyId) {
      return;
    }
    // 命中索引：同一设备可能出现在多个场景（不同页面实例），但一个 store 只管当前场景
    const pointIds = this.deviceIdToPointIds.get(deviceId);
    if (!pointIds) {
      return;
    }
    const ts =
      payload.ts === null || payload.ts === undefined
        ? null
        : String(payload.ts);
    for (const pointId of pointIds) {
      const runtime = this.points.get(pointId);
      if (!runtime) {
        continue;
      }
      // SSE 事件只带「一条读数」，需要按属性标识精确匹配该点位关心的属性
      if (!this.propertyToPointIds.get(`${pointId}\u0000${propertyId}`)) {
        continue;
      }
      const previous = runtime.values[propertyId];
      // 同 ts 不覆盖（SSE 可能乱序；旧值覆盖新值会让数字「跳回去」）
      if (previous?.ts && ts && Number(previous.ts) > Number(ts)) {
        continue;
      }
      runtime.values = {
        ...runtime.values,
        [propertyId]: {
          quality: payload.quality ?? null,
          stale: false,
          ts,
          value: payload.value ?? null,
        },
      };
      this.dirty.add(pointId);
    }
    this.scheduleFlush();
  }

  /**
   * 处理 SSE `device-status` 事件（在线/启停变化）。
   *
   * @param payload 事件负载（`deviceId` 单台 或 `deviceIds` 批量）
   */
  applyDeviceStatusEvent(payload: {
    deviceId?: null | number | string;
    deviceIds?: Array<number | string>;
    status?: null | number | string;
  }): void {
    const rawIds: Array<number | string> = [];
    if (payload.deviceId !== null && payload.deviceId !== undefined) {
      rawIds.push(payload.deviceId);
    }
    for (const id of payload.deviceIds ?? []) {
      rawIds.push(id);
    }
    if (rawIds.length === 0) {
      return;
    }
    let changed = false;
    for (const rawId of rawIds) {
      const pointIds = this.deviceIdToPointIds.get(String(rawId));
      if (!pointIds) {
        continue;
      }
      for (const pointId of pointIds) {
        const runtime = this.points.get(pointId);
        if (!runtime) {
          continue;
        }
        // `status` 是启停位（1/0）；后端也可能把在线状态放在同一事件的其它字段上。
        // 这里按字段类型分流：数字 ⇒ 启停位；字符串 ⇒ 在线状态。
        if (typeof payload.status === 'number') {
          runtime.status = payload.status;
        } else if (typeof payload.status === 'string') {
          runtime.onlineStatus = payload.status;
        }
        this.dirty.add(pointId);
        changed = true;
      }
    }
    if (changed) {
      this.scheduleFlush();
    }
  }

  /**
   * 合并告警数（`alert` 事件只说明「告警变化了」，具体数量由页面重拉快照后调用本方法）。
   *
   * @param counts 设备 ID → 活动告警数
   */
  applyAlertCounts(counts: Map<string, number>): void {
    let changed = false;
    for (const [pointId, runtime] of this.points) {
      const next = counts.get(runtime.deviceId) ?? 0;
      if (next !== runtime.alertCount) {
        runtime.alertCount = next;
        this.dirty.add(pointId);
        changed = true;
      }
    }
    if (changed) {
      this.scheduleFlush();
    }
  }

  /** 立即提交全部状态（例如页面卸载前，或需要同步渲染的场景）。 */
  flushNow(): void {
    if (this.flushHandle) {
      cancelAnimationFrame(this.flushHandle);
      this.flushHandle = 0;
    }
    this.flush();
  }

  /** 释放（取消待执行的 rAF）。 */
  dispose(): void {
    if (this.flushHandle) {
      cancelAnimationFrame(this.flushHandle);
      this.flushHandle = 0;
    }
    this.points.clear();
    this.deviceIdToPointIds.clear();
    this.propertyToPointIds.clear();
    this.dirty.clear();
  }

  // ---------- 私有 ----------

  private appendIndex(
    point: IotTwinApi.RuntimePointResp,
    runtime: PointRuntime,
  ): void {
    const existing = this.deviceIdToPointIds.get(point.deviceId);
    if (existing) {
      existing.push(point.pointId);
    } else {
      this.deviceIdToPointIds.set(point.deviceId, [point.pointId]);
    }
    if (runtime.primaryProperty) {
      const key = `${point.pointId}\u0000${runtime.primaryProperty}`;
      const set = this.propertyToPointIds.get(key);
      if (set) {
        set.add(point.pointId);
      } else {
        this.propertyToPointIds.set(key, new Set([point.pointId]));
      }
    }
  }

  private scheduleFlush(): void {
    if (this.flushHandle) {
      return;
    }
    this.flushHandle = requestAnimationFrame(() => {
      this.flushHandle = 0;
      this.flush();
    });
  }

  private flush(): void {
    if (this.dirty.size === 0) {
      return;
    }
    const states = new Map<string, AnchorRuntimeState>();
    for (const pointId of this.dirty) {
      const runtime = this.points.get(pointId);
      if (!runtime) {
        continue;
      }
      states.set(pointId, toAnchorState(runtime));
    }
    this.dirty.clear();
    this.onFlush(states);
  }
}

/**
 * 把内部状态转成锚点层要的展示状态。
 *
 * @param runtime 点位运行态
 * @returns 锚点展示状态
 */
function toAnchorState(runtime: PointRuntime): AnchorRuntimeState {
  const primaryProperty = runtime.primaryProperty;
  const value = primaryProperty
    ? runtime.values[primaryProperty]
    : undefined;
  const hasValue = Boolean(value && value.value !== null && value.value !== undefined);
  let primaryText = '';
  if (hasValue && value) {
    primaryText = formatValue(value.value, runtime.primaryDecimals);
    if (runtime.primaryUnit) {
      primaryText = `${primaryText} ${runtime.primaryUnit}`;
    }
  }
  return {
    alertCount: runtime.alertCount,
    hasValue,
    onlineStatus: runtime.onlineStatus,
    primaryText,
    status: runtime.status,
  };
}

/**
 * 按小数位格式化数值（格式化失败时原样返回，不显示 NaN）。
 *
 * @param value 原始值字符串
 * @param decimals 小数位（null/undefined ⇒ 原样返回）
 * @returns 展示用文本
 */
function formatValue(
  value: null | string | undefined,
  decimals: null | number | undefined,
): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (decimals === null || decimals === undefined) {
    return value;
  }
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) {
    // 非数字（枚举/字符串属性）不做小数位处理
    return value;
  }
  return numeric.toFixed(decimals);
}
```

### 10.1 `three/twin/runtime-store.test.ts`

```ts
import type { IotTwinApi } from '#/api/iot';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TwinRuntimeStore } from './runtime-store';

/**
 * 运行态合并与节流。
 *
 * 用 `vi.stubGlobal('requestAnimationFrame', ...)` 把 rAF 变成同步可控，
 * 这样「一帧内多次事件只提交一次」的行为可以被确定性验证。
 */
describe('TwinRuntimeStore', () => {
  let flushes: Array<Map<string, { primaryText: string }>>;
  let store: TwinRuntimeStore;

  beforeEach(() => {
    flushes = [];
    // 同步执行 rAF 回调，便于断言「提交了几次」
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', () => undefined);
    store = new TwinRuntimeStore({
      onFlush: (states) => {
        flushes.push(states as unknown as Map<string, { primaryText: string }>);
      },
    });
  });

  afterEach(() => {
    store.dispose();
    vi.unstubAllGlobals();
  });

  function snapshot(): IotTwinApi.RuntimeResp {
    return {
      pointCount: 1,
      points: [
        {
          alertCount: 0,
          deviceId: '4101',
          deviceName: '3 号泵',
          onlineStatus: 'online',
          pointId: '5002',
          status: 1,
          values: {},
        },
      ],
      sceneId: '5001',
      serverTime: '2026-10-21 10:30:00',
      truncated: false,
    };
  }

  function bindings(): Map<string, IotTwinApi.BindingResp[]> {
    return new Map([
      [
        '5002',
        [
          {
            displayRole: 'primary',
            displayType: 'value',
            id: '1',
            propertyIdentifier: 'temperature',
            propertyName: '温度',
            serviceIdentifier: 'Environment',
            stale: false,
            unit: '℃',
            decimals: 1,
          },
        ],
      ],
    ]);
  }

  it('快照替换后提交一次，无数据时 hasValue=false（不是 0）', () => {
    store.replaceSnapshot(snapshot(), bindings());
    expect(flushes).toHaveLength(1);
    const state = [...(flushes[0]?.values() ?? [])][0] as unknown as {
      hasValue: boolean;
      primaryText: string;
    };
    expect(state.hasValue).toBe(false);
    expect(state.primaryText).toBe('');
  });

  it('latest 事件命中主展示属性时更新文本并带单位与小数位', () => {
    store.replaceSnapshot(snapshot(), bindings());
    flushes.length = 0;
    store.applyLatestEvent({
      deviceId: '4101',
      propertyId: 'temperature',
      ts: 1000,
      value: '26.54',
    });
    const state = [...(flushes[0]?.values() ?? [])][0] as unknown as {
      hasValue: boolean;
      primaryText: string;
    };
    expect(state.hasValue).toBe(true);
    expect(state.primaryText).toBe('26.5 ℃');
  });

  it('★ 旧 ts 的读数不覆盖新值（SSE 乱序时不回跳）', () => {
    store.replaceSnapshot(snapshot(), bindings());
    store.applyLatestEvent({
      deviceId: '4101',
      propertyId: 'temperature',
      ts: 2000,
      value: '30',
    });
    flushes.length = 0;
    store.applyLatestEvent({
      deviceId: '4101',
      propertyId: 'temperature',
      ts: 1000,
      value: '10',
    });
    // 旧值被丢弃 ⇒ 没有 dirty ⇒ 没有新的 flush
    expect(flushes).toHaveLength(0);
  });

  it('非本场景设备的事件被忽略（不产生 flush）', () => {
    store.replaceSnapshot(snapshot(), bindings());
    flushes.length = 0;
    store.applyLatestEvent({
      deviceId: '9999',
      propertyId: 'temperature',
      ts: 1000,
      value: '1',
    });
    expect(flushes).toHaveLength(0);
  });

  it('device-status 数字 status 视为启停位，字符串视为在线状态', () => {
    store.replaceSnapshot(snapshot(), bindings());
    flushes.length = 0;
    store.applyDeviceStatusEvent({ deviceId: '4101', status: 0 });
    const offState = [...(flushes[0]?.values() ?? [])][0] as unknown as {
      status: number;
    };
    expect(offState.status).toBe(0);

    flushes.length = 0;
    store.applyDeviceStatusEvent({ deviceId: '4101', status: 'offline' });
    const offlineState = [...(flushes[0]?.values() ?? [])][0] as unknown as {
      onlineStatus: string;
    };
    expect(offlineState.onlineStatus).toBe('offline');
  });

  it('applyAlertCounts 只对变化的设备产生 flush', () => {
    store.replaceSnapshot(snapshot(), bindings());
    flushes.length = 0;
    store.applyAlertCounts(new Map([['4101', 0]]));
    expect(flushes).toHaveLength(0);

    store.applyAlertCounts(new Map([['4101', 3]]));
    const state = [...(flushes[0]?.values() ?? [])][0] as unknown as {
      alertCount: number;
    };
    expect(state.alertCount).toBe(3);
  });
});
```

---

## 11. 页面组合式函数：`views/iot/twin/composables/use-twin-scene.ts`

```ts
import type { IotTwinApi } from '#/api/iot';

import { computed, ref } from 'vue';

import {
  createTwinScene,
  deleteTwinScene,
  getTwinSceneList,
  updateTwinScene,
} from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

/** 场景树节点（后端 `SceneResp` + 前端组树后的 children）。 */
export interface SceneTreeNode extends IotTwinApi.SceneResp {
  children: SceneTreeNode[];
  /** 树深度（根为 1），用于判断还能否加子场景。 */
  depth: number;
}

/**
 * 场景树数据域：拉取、组树、增删改、按 id 找节点与祖先链。
 *
 * ## 为什么组树在前端而不是后端
 *
 * 后端 `GET /iot/twin/scenes` 返回**平铺列表**（按 parentId/sort/id 排序）。理由：
 * - 平铺列表是「全量」的：前端要同时用它做树渲染、祖先链计算、下拉候选（父场景选择器
 *   需要「排除自己与自己的后代」），树形结构反而不便于这些计算；
 * - 树的形状是展示决策（要不要折叠、要不要显示点数），不该固化进接口。
 *
 * ## 失败语义
 *
 * 加载失败时 `error` 非空、`scenes` 保持**上一次的成功数据**（不置空）：
 * 置空会让用户看到「一棵空树」，误以为配置全丢了。这与设备台账的失败态口径一致。
 */
export function useTwinScene() {
  const scenes = ref<IotTwinApi.SceneResp[]>([]);
  const loading = ref(false);
  /** 加载失败原因（空串 = 无错）。 */
  const error = ref('');

  /** 组树后的根节点列表（只含 parentId === '0' 的节点）。 */
  const tree = computed<SceneTreeNode[]>(() => buildTree(scenes.value));

  /** 全部场景的 id → 节点索引（供祖先链与查找用）。 */
  const byId = computed(() => {
    const map = new Map<string, IotTwinApi.SceneResp>();
    for (const scene of scenes.value) {
      map.set(scene.id, scene);
    }
    return map;
  });

  /** 重新拉取场景列表。 */
  async function load(): Promise<void> {
    loading.value = true;
    try {
      scenes.value = await getTwinSceneList();
      error.value = '';
    } catch (loadError) {
      // 保留上一次数据：清空会让用户误以为配置丢了
      error.value = extractErrorMessage(
        loadError,
        $t('page.iot.twin.sceneLoadFailed'),
      );
      throw loadError;
    } finally {
      loading.value = false;
    }
  }

  /** 按 id 查场景（找不到返回 undefined，不抛）。 */
  function findScene(id?: null | string): IotTwinApi.SceneResp | undefined {
    if (!id) {
      return undefined;
    }
    return byId.value.get(id);
  }

  /**
   * 取从根到指定场景的祖先链（**含自身**，按根→叶顺序）。
   *
   * 这是世界坐标链的数据来源：`scene-manager` 用它逐级左乘锚点矩阵。
   * 链断裂（父场景已被删除）时**截断到能取到的部分**并返回 `broken: true`，
   * 由调用方决定是否提示——静默返回不完整的链会让模型位置偏移而无从察觉。
   */
  function ancestryOf(sceneId: string): {
    broken: boolean;
    chain: IotTwinApi.SceneResp[];
  } {
    const chain: IotTwinApi.SceneResp[] = [];
    let cursor = byId.value.get(sceneId);
    const seen = new Set<string>();
    let broken = false;
    while (cursor) {
      if (seen.has(cursor.id)) {
        // 数据成环（后端有校验，这里防御）：立刻停止，避免无限循环
        broken = true;
        break;
      }
      seen.add(cursor.id);
      chain.unshift(cursor);
      if (cursor.parentId === '0') {
        return { broken, chain };
      }
      const parent = byId.value.get(cursor.parentId);
      if (!parent) {
        broken = true;
        break;
      }
      cursor = parent;
    }
    return { broken, chain };
  }

  /**
   * 计算某场景的深度（根为 1）。找不到场景时返回 0。
   */
  function depthOf(sceneId: string): number {
    const { chain } = ancestryOf(sceneId);
    return chain.length;
  }

  /**
   * 是否还能给某场景加子场景（深度上限）。
   */
  function canAddChild(sceneId: string): boolean {
    return depthOf(sceneId) < IotTwinApi.TWIN_LIMITS.maxSceneDepth;
  }

  /**
   * 父场景下拉候选：排除自身与自身的全部后代（否则会形成环）。
   *
   * @param excludeSceneId 正在编辑的场景 id（新建时传 undefined）
   */
  function parentOptions(
    excludeSceneId?: string,
  ): Array<{ label: string; value: string }> {
    const excluded = new Set<string>();
    if (excludeSceneId) {
      // 用「谁的祖先链包含 excludeSceneId」判定后代，避免为每个节点单独上溯
      for (const scene of scenes.value) {
        const { chain } = ancestryOf(scene.id);
        if (chain.some((item) => item.id === excludeSceneId)) {
          excluded.add(scene.id);
        }
      }
    }
    return scenes.value
      .filter((scene) => !excluded.has(scene.id))
      .map((scene) => ({
        label: `${indent(depthOf(scene.id))}${scene.sceneName}（${scene.sceneCode}）`,
        value: scene.id,
      }));
  }

  async function createScene(data: IotTwinApi.SceneSaveReq): Promise<number> {
    const id = await createTwinScene(data);
    await load();
    return id;
  }

  async function updateScene(
    id: string,
    data: IotTwinApi.SceneSaveReq,
  ): Promise<void> {
    await updateTwinScene(id, data);
    await load();
  }

  async function removeScene(id: string): Promise<void> {
    await deleteTwinScene(id);
    await load();
  }

  return {
    ancestryOf,
    byId,
    canAddChild,
    createScene,
    depthOf,
    error,
    findScene,
    load,
    loading,
    parentOptions,
    removeScene,
    scenes,
    tree,
    updateScene,
  };
}

/**
 * 平铺列表 → 树。
 *
 * 父节点不存在的节点（父场景被删除）**挂到根**并保留：丢掉它会让用户完全看不到
 * 这条配置，而「挂在根上 + 后续可编辑修复」才是可操作的。这是刻意的容错，
 * 与「静默降级」的区别在于：页面会在该节点上标注「父场景已不存在」。
 */
function buildTree(scenes: IotTwinApi.SceneResp[]): SceneTreeNode[] {
  const nodeMap = new Map<string, SceneTreeNode>();
  for (const scene of scenes) {
    nodeMap.set(scene.id, { ...scene, children: [], depth: 1 });
  }
  const roots: SceneTreeNode[] = [];
  for (const node of nodeMap.values()) {
    const parent = nodeMap.get(node.parentId);
    if (parent) {
      parent.children.push(node);
      parent.depth = Math.max(parent.depth, node.depth + 1);
    } else {
      // parentId === '0' 或父场景已不存在 ⇒ 挂根
      roots.push(node);
    }
  }
  // 递归设置 depth（上面的 max 只保证父节点不小于子节点，这里统一算准）
  const assign = (nodes: SceneTreeNode[], depth: number): void => {
    for (const node of nodes) {
      node.depth = depth;
      assign(node.children, depth + 1);
    }
  };
  assign(roots, 1);
  // 排序：按 sort 升序、再按 id（与后端返回顺序一致，避免前端排序抖动）
  const sortNodes = (nodes: SceneTreeNode[]): void => {
    nodes.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || a.id.localeCompare(b.id));
    for (const node of nodes) {
      sortNodes(node.children);
    }
  };
  sortNodes(roots);
  return roots;
}

/** 生成树形缩进（全角空格，保证在下拉选项里对齐）。 */
function indent(depth: number): string {
  return '　'.repeat(Math.max(0, depth - 1));
}
```

---

## 12. 页面组合式函数：`views/iot/twin/composables/use-twin-runtime.ts`

```ts
import type { Ref } from 'vue';

import type { IotTwinApi } from '#/api/iot';

import type { AnchorRuntimeState } from '#/three/twin/types';

import { onScopeDispose, ref } from 'vue';

import { getActiveAlertCounts } from '#/api/iot';
import { getTwinRuntime } from '#/api/iot';
import { TwinRuntimeStore } from '#/three/twin/runtime-store';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import { useIotRealtime } from '../../shared/use-iot-realtime';

/**
 * 运行态数据域：快照 + SSE 增量合流。
 *
 * ## 三条接线
 *
 * 1. `latest` 事件 ⇒ 直接写入 store（rAF 合并后每帧提交一次给锚点层）；
 * 2. `device-status` 事件 ⇒ 直接写入 store（字段类型分流见 store 注释）；
 * 3. `alert` 事件 ⇒ **不猜数量**，去抖后重拉快照（与仪表盘 `useDashboardRealtime` 同口径）：
 *    告警事件的负载只说明「哪几条告警变了」，不携带「本设备活动告警总数」。
 *
 * ## 失败语义
 *
 * 快照加载失败 ⇒ `error` 非空，页面渲染错误态 + 重试；**不**伪造空数据。
 * 实时连接失败 ⇒ `realtimeState/error/authError` 原样透出，页面渲染「实时已断开」提示。
 */
export function useTwinRuntime(options: {
  /** 每帧提交的锚点状态（由页面转给 scene-manager）。 */
  onFlush: (states: Map<string, AnchorRuntimeState>) => void;
}) {
  const { authError, error: realtimeError, onEvent, state } = useIotRealtime();
  const snapshot = ref<IotTwinApi.RuntimeResp | null>(null);
  const loading = ref(false);
  const error = ref('');
  /** 最近一次快照的本地时刻（用于「数据是几分钟前的」提示）。 */
  const loadedAt = ref<Date | null>(null);

  const store = new TwinRuntimeStore({ onFlush: options.onFlush });
  let alertReloadTimer: ReturnType<typeof setTimeout> | undefined;
  let currentSceneId = '';

  /**
   * 加载场景快照（进入场景、告警变化、手动刷新时调用）。
   *
   * @param sceneId 场景 ID
   * @param bindings 点位 → 绑定（决定主展示属性与单位/精度）
   */
  async function loadSnapshot(
    sceneId: string,
    bindings: Map<string, IotTwinApi.BindingResp[]>,
  ): Promise<void> {
    currentSceneId = sceneId;
    loading.value = true;
    try {
      const data = await getTwinRuntime(sceneId);
      snapshot.value = data;
      store.replaceSnapshot(data, bindings);
      loadedAt.value = new Date();
      error.value = '';
    } catch (loadError) {
      error.value = extractErrorMessage(
        loadError,
        $t('page.iot.twin.runtimeLoadFailed'),
      );
      throw loadError;
    } finally {
      loading.value = false;
    }
  }

  /** 重拉活动告警数并合并进 store（`alert` 事件的落点）。 */
  async function refreshAlerts(): Promise<void> {
    const deviceIds = [
      ...new Set((snapshot.value?.points ?? []).map((point) => point.deviceId)),
    ];
    if (deviceIds.length === 0) {
      return;
    }
    try {
      const counts = await getActiveAlertCounts(deviceIds);
      const normalized = new Map<string, number>();
      for (const [deviceId, value] of Object.entries(counts)) {
        // 后端 Long 可能是字符串形态，`Number()` 在这里安全（告警数是小整数）
        normalized.set(deviceId, Number(value) || 0);
      }
      store.applyAlertCounts(normalized);
    } catch (alertError) {
      // 告警数刷新失败不应让整个孪生页报错（设备状态仍在正常显示）；
      // 但必须留痕并给出可见提示，不能静默。
      console.warn('[iot/twin] 活动告警数刷新失败：', alertError);
      error.value = extractErrorMessage(
        alertError,
        $t('page.iot.twin.alertCountFailed'),
      );
    }
  }

  // ---- SSE 接线 ----
  onEvent('latest', (payload) => {
    store.applyLatestEvent(payload);
  });
  onEvent('device-status', (payload) => {
    store.applyDeviceStatusEvent(
      payload as {
        deviceId?: null | number | string;
        deviceIds?: Array<number | string>;
        status?: null | number | string;
      },
    );
  });
  onEvent('alert', () => {
    // 去抖：一次告警风暴可能推几十个 alert 事件，逐条重拉会把接口打爆
    if (alertReloadTimer !== undefined) {
      return;
    }
    alertReloadTimer = setTimeout(() => {
      alertReloadTimer = undefined;
      void refreshAlerts();
    }, 1200);
  });

  onScopeDispose(() => {
    if (alertReloadTimer !== undefined) {
      clearTimeout(alertReloadTimer);
      alertReloadTimer = undefined;
    }
    store.dispose();
  });

  return {
    authError,
    error,
    loadedAt,
    loading,
    loadSnapshot,
    realtimeError,
    realtimeState: state as Ref<string>,
    refreshAlerts,
    snapshot,
  };
}
```

---

## 13. 页面组合式函数：`views/iot/twin/composables/use-twin-editor.ts`

```ts
import { computed, ref } from 'vue';

/** 编辑器里的待保存锚点草稿（未落库）。 */
export interface DraftAnchor {
  /** 场景局部坐标。 */
  local: { x: number; y: number; z: number };
  /** 场景局部法线。 */
  normal: { x: number; y: number; z: number };
}

/**
 * 配置态交互状态机（拾取 → 拖拽微调 → 保存）。
 *
 * ## 为什么不做自动保存
 *
 * 坐标是精密配置：一次误拖、一次失焦都可能把点位挪走几米。自动保存会把这种误操作
 * 直接写进库，而用户往往在几天后发现「设备位置偏了」且无从追溯是哪次改动导致的。
 * 因此全部改动先落在本地草稿，**只有点「保存」才 PUT**。
 *
 * 代价：用户可能忘记保存就切场景。因此 `hasDraft` 为 true 时，切场景/关闭页面前
 * 必须弹确认（见 `index.vue` 的 `beforeLeave`）。
 */
export function useTwinEditor() {
  /** 当前编辑模式：浏览 / 放置新锚点。 */
  const mode = ref<'browse' | 'place'>('browse');
  /** 待保存的新锚点草稿（`mode === 'place'` 时有值）。 */
  const draft = ref<DraftAnchor | null>(null);
  /** 选中编辑的点位 id（存量点位编辑）。 */
  const editingPointId = ref<null | string>(null);
  /** 是否有任何未保存改动。 */
  const hasDraft = computed(
    () => draft.value !== null || editingPointId.value !== null,
  );

  /** 进入「放置锚点」模式。 */
  function startPlacing(): void {
    mode.value = 'place';
    draft.value = null;
  }

  /** 取消放置。 */
  function cancelPlacing(): void {
    mode.value = 'browse';
    draft.value = null;
  }

  /** 记录拾取到的位置（世界 → 局部换算由调用方完成）。 */
  function setDraft(anchor: DraftAnchor): void {
    draft.value = anchor;
  }

  /** 微调草稿坐标（拖拽结果）。 */
  function nudge(x: number, y: number, z: number): void {
    if (!draft.value) {
      return;
    }
    draft.value = { ...draft.value, local: { x, y, z } };
  }

  /** 选中存量点位进行编辑。 */
  function selectPoint(pointId: string): void {
    editingPointId.value = pointId;
    mode.value = 'browse';
  }

  /** 清空选择与草稿（保存成功 / 取消后调用）。 */
  function reset(): void {
    mode.value = 'browse';
    draft.value = null;
    editingPointId.value = null;
  }

  return {
    cancelPlacing,
    draft,
    editingPointId,
    hasDraft,
    mode,
    nudge,
    reset,
    selectPoint,
    setDraft,
    startPlacing,
  };
}
```

---

## 14. 视图数据定义：`views/iot/twin/data.ts`

```ts
import type { VbenFormSchema as FormSchema } from '#/adapter/form';
import type { IotTwinApi } from '#/api/iot';

import { $t } from '#/locales';

/**
 * 数字孪生页的表单 schema 与选项常量（页面只做编排）。
 */

/** 场景类型选项。 */
export function sceneTypeOptions(): Array<{ label: string; value: string }> {
  return [
    { label: $t('page.iot.twin.sceneType.park'), value: 'park' },
    { label: $t('page.iot.twin.sceneType.building'), value: 'building' },
    { label: $t('page.iot.twin.sceneType.floor'), value: 'floor' },
    { label: $t('page.iot.twin.sceneType.room'), value: 'room' },
  ];
}

/** 展示形态选项。 */
export function displayTypeOptions(): Array<{ label: string; value: string }> {
  return [
    { label: $t('page.iot.twin.displayType.value'), value: 'value' },
    { label: $t('page.iot.twin.displayType.gauge'), value: 'gauge' },
    { label: $t('page.iot.twin.displayType.switch'), value: 'switch' },
    { label: $t('page.iot.twin.displayType.color'), value: 'color' },
    { label: $t('page.iot.twin.displayType.sparkline'), value: 'sparkline' },
  ];
}

/** 展示角色选项。 */
export function displayRoleOptions(): Array<{ label: string; value: string }> {
  return [
    { label: $t('page.iot.twin.displayRole.primary'), value: 'primary' },
    { label: $t('page.iot.twin.displayRole.secondary'), value: 'secondary' },
  ];
}

/**
 * 场景表单 schema。
 *
 * @param parentOptions 父场景候选（由调用方按「排除自身与后代」算出）
 * @returns 表单 schema
 */
export function useSceneFormSchema(
  parentOptions: Array<{ label: string; value: string }>,
): FormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        maxlength: IotTwinApi.TWIN_LIMITS.maxSceneCodeLength,
        placeholder: $t('page.iot.twin.sceneCodePlaceholder'),
      },
      fieldName: 'sceneCode',
      label: $t('page.iot.twin.sceneCode'),
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        maxlength: IotTwinApi.TWIN_LIMITS.maxSceneNameLength,
      },
      fieldName: 'sceneName',
      label: $t('page.iot.twin.sceneName'),
      rules: 'required',
    },
    {
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: parentOptions,
        placeholder: $t('page.iot.twin.parentPlaceholder'),
        showSearch: true,
        optionFilterProp: 'label',
      },
      fieldName: 'parentId',
      label: $t('page.iot.twin.parentScene'),
    },
    {
      component: 'Select',
      componentProps: {
        options: sceneTypeOptions(),
      },
      defaultValue: 'building',
      fieldName: 'sceneType',
      label: $t('page.iot.twin.sceneTypeLabel'),
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: {
        min: 0,
        step: 0.1,
      },
      fieldName: 'anchorX',
      label: $t('page.iot.twin.anchorX'),
    },
    {
      component: 'InputNumber',
      componentProps: {
        step: 0.1,
      },
      fieldName: 'anchorY',
      label: $t('page.iot.twin.anchorY'),
    },
    {
      component: 'InputNumber',
      componentProps: {
        step: 0.1,
      },
      fieldName: 'anchorZ',
      label: $t('page.iot.twin.anchorZ'),
    },
    {
      component: 'InputNumber',
      componentProps: {
        step: 1,
      },
      fieldName: 'anchorRy',
      help: $t('page.iot.twin.anchorRotationHelp'),
      label: $t('page.iot.twin.anchorRy'),
    },
    {
      component: 'InputNumber',
      componentProps: {
        min: 0.000_001,
        step: 0.1,
      },
      defaultValue: 1,
      fieldName: 'anchorScale',
      help: $t('page.iot.twin.anchorScaleHelp'),
      label: $t('page.iot.twin.anchorScale'),
    },
    {
      component: 'InputNumber',
      componentProps: {
        min: 0,
      },
      defaultValue: 0,
      fieldName: 'sort',
      label: $t('page.iot.twin.sort'),
    },
    {
      component: 'Textarea',
      componentProps: {
        maxlength: IotTwinApi.TWIN_LIMITS.maxSceneRemarkLength,
        rows: 3,
      },
      fieldName: 'remark',
      label: $t('page.iot.device.remark'),
    },
  ];
}
```

---

## 15. 视图：`views/iot/twin/index.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';
import type { AnchorRuntimeState } from '#/three/twin/types';

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Alert, Empty, Spin } from 'ant-design-vue';

import { getTwinPointList } from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import ListErrorAlert from '../shared/list-error-alert.vue';
import { useTwinEditor } from './composables/use-twin-editor';
import { useTwinRuntime } from './composables/use-twin-runtime';
import { useTwinScene } from './composables/use-twin-scene';
import ModelPanel from './modules/model-panel.vue';
import PointPanel from './modules/point-panel.vue';
import RuntimeLegend from './modules/runtime-legend.vue';
import SceneTree from './modules/scene-tree.vue';
import TwinCanvas from './modules/twin-canvas.vue';

/**
 * 数字孪生页（宿主：只做编排 + 布局）。
 *
 * 布局三段式：左场景树 / 中 3D 画布 / 右点位与模型资产。
 *
 * ## 深链约定（与设备台账 `?deviceId=` 同口径）
 *
 * - 进入场景 ⇒ `router.replace({ query: { sceneId } })`（replace 不产生历史栈垃圾）；
 * - 带 `?sceneId=` 刷新/前进后退 ⇒ 自动进入对应场景；
 * - `sceneId` 不存在 ⇒ **仍然尝试加载**，由画布给出「场景不存在」错误态；
 *   不静默跳回根场景（用户会以为自己点错了）。
 *
 * ## 数据流
 *
 * 场景树（`useTwinScene`）→ 当前场景 → 点位（一次拉取）→ 运行态快照（`useTwinRuntime`）
 * → SSE 增量按帧合并 → 画布 `updateAnchorStates`。
 */
const route = useRoute();
const router = useRouter();

const {
  ancestryOf,
  canAddChild,
  depthOf,
  error: sceneError,
  findScene,
  load: loadScenes,
  loading: sceneLoading,
  parentOptions,
  scenes,
  tree,
} = useTwinScene();

const editor = useTwinEditor();

/** 画布组件引用（用于把运行态状态推给画布）。 */
const canvasRef = ref<InstanceType<typeof TwinCanvas> | null>(null);

const runtime = useTwinRuntime({
  onFlush: (states: Map<string, AnchorRuntimeState>) => {
    canvasRef.value?.updateAnchorStates(states);
  },
});

/** 当前场景 id（来自 URL）。 */
const currentSceneId = computed(() => {
  const value = route.query.sceneId;
  return typeof value === 'string' && value !== '' ? value : '';
});

/** 当前场景（找不到时为 undefined，画布会给出错误态）。 */
const currentScene = computed(() => findScene(currentSceneId.value));

/**
 * 从根到当前场景的祖先链（含自身）。
 *
 * `broken=true` 表示父场景已被删除：链被截断，模型位置会与预期不符，
 * 必须在页面上明确提示（不静默渲染一个位置偏了的模型）。
 */
const ancestry = computed(() =>
  currentScene.value
    ? ancestryOf(currentScene.value.id)
    : { broken: false, chain: [] },
);

const points = ref<IotTwinApi.PointResp[]>([]);
const pointsLoading = ref(false);
const pointsError = ref('');

/**
 * 模型资产列表（由 `ModelPanel` 加载后共享给画布）。
 *
 * 为什么放在父组件：画布需要 `fileSize`（判断飞行模式是否允许）与 `contentUrl`
 * （区分外链/平台内模型），而 `ModelPanel` 本来就要拉这份列表 ⇒ 上提一份，
 * 两边共用，避免同一份数据请求两次。
 */
const assets = ref<IotTwinApi.ModelAssetResp[]>([]);

/**
 * 画布拾取到的待保存锚点（模型表面交点，已转本场景局部坐标）。
 *
 * 非空 ⇒ 点位表单以「新建 + 预填坐标」打开；表单关闭/保存后清空。
 */
const pickedAnchor = ref<{
  local: { x: number; y: number; z: number };
  normal: { x: number; y: number; z: number };
} | null>(null);
/** 点位表单显隐（新建/编辑共用；由 PointPanel 内部管理编辑，这里只管「拾取后新建」）。 */
const placeFormOpen = ref(false);

/** 点位 → 绑定（运行态快照据此决定主展示属性与单位/精度）。 */
const bindingsByPointId = computed(() => {
  const map = new Map<string, IotTwinApi.BindingResp[]>();
  for (const point of points.value) {
    map.set(point.id, point.bindings);
  }
  return map;
});

const canvasState = ref<string>('disposed');
const canvasError = ref('');
const fallbackReason = ref('');

/** 进入某场景（只写 URL；数据加载由 watch 统一处理）。 */
async function enterScene(sceneId: string): Promise<void> {
  if (sceneId === currentSceneId.value) {
    return;
  }
  await router.replace({ query: { ...route.query, sceneId } });
}

/** 返回上级场景（面包屑）。 */
async function goParent(): Promise<void> {
  const parent = ancestry.value.chain.at(-2);
  if (parent) {
    await enterScene(parent.id);
  }
}

/** 加载当前场景的点位。 */
async function loadPoints(sceneId: string): Promise<void> {
  if (!sceneId) {
    points.value = [];
    pointsError.value = '';
    return;
  }
  pointsLoading.value = true;
  try {
    points.value = await getTwinPointList(sceneId);
    pointsError.value = '';
  } catch (loadError) {
    pointsError.value = extractErrorMessage(
      loadError,
      $t('page.iot.twin.pointLoadFailed'),
    );
  } finally {
    pointsLoading.value = false;
  }
}

/** 当前场景切换 ⇒ 拉点位 + 拉运行态快照。 */
watch(
  currentSceneId,
  async (sceneId) => {
    if (!sceneId) {
      points.value = [];
      return;
    }
    await loadPoints(sceneId);
    try {
      await runtime.loadSnapshot(sceneId, bindingsByPointId.value);
    } catch {
      // 失败原因已写入 runtime.error，模板渲染错误态；此处不重复提示
    }
  },
  { immediate: true },
);

onMounted(async () => {
  try {
    await loadScenes();
  } catch {
    // 失败原因在 sceneError，模板渲染错误态
  }
});

/** 点位保存/删除后刷新（点位变 ⇒ 绑定变 ⇒ 运行态的主展示属性变）。 */
async function reloadAfterPointChange(): Promise<void> {
  await loadPoints(currentSceneId.value);
  if (currentSceneId.value) {
    try {
      await runtime.loadSnapshot(currentSceneId.value, bindingsByPointId.value);
    } catch {
      // 同上
    }
  }
}

/**
 * 画布在「放置锚点」模式下拾取到模型表面：记录草稿并打开点位表单。
 *
 * 坐标已是本场景局部坐标（画布侧用世界矩阵的逆换算过），落库口径直接可用。
 */
function onPlaceAnchor(payload: {
  local: { x: number; y: number; z: number };
  normal: { x: number; y: number; z: number };
}): void {
  pickedAnchor.value = payload;
  placeFormOpen.value = true;
}

/** 点击已有锚点：高亮并在右侧面板定位（不直接打开编辑，避免误改）。 */
function onSelectAnchor(pointId: string): void {
  editor.selectPoint(pointId);
}

/** 资产列表加载完成（ModelPanel 上报）。 */
function onAssetsLoaded(list: IotTwinApi.ModelAssetResp[]): void {
  assets.value = list;
}
</script>

<template>
  <Page auto-content-height>
    <ListErrorAlert
      v-if="sceneError"
      :message="sceneError"
      :title="$t('page.iot.twin.sceneLoadFailed')"
    />
    <div v-else class="flex h-full min-h-0 gap-3">
      <!-- 左：场景树 -->
      <aside class="w-72 shrink-0 overflow-hidden rounded-lg border border-border">
        <SceneTree
          :can-add-child="canAddChild"
          :current-scene-id="currentSceneId"
          :depth-of="depthOf"
          :error="sceneError"
          :loading="sceneLoading"
          :parent-options="parentOptions"
          :scenes="scenes"
          :tree="tree"
          @enter="enterScene"
          @refresh="loadScenes"
        />
      </aside>

      <!-- 中：画布 -->
      <section
        class="relative min-w-0 flex-1 overflow-hidden rounded-lg border border-border"
      >
        <Empty
          v-if="scenes.length === 0 && !sceneLoading"
          :description="$t('page.iot.twin.noScene')"
        />
        <div
          v-else-if="!currentSceneId"
          class="flex h-full items-center justify-center text-sm text-muted-foreground"
        >
          {{ $t('page.iot.twin.selectSceneHint') }}
        </div>
        <TwinCanvas
          v-else
          ref="canvasRef"
          :ancestry="ancestry.chain"
          :ancestry-broken="ancestry.broken"
          :anchors="points"
          :assets="assets"
          :editor-mode="editor.mode"
          :scene="currentScene"
          :scene-id="currentSceneId"
          @fallback="(reason: string) => (fallbackReason = reason)"
          @place="onPlaceAnchor"
          @select="onSelectAnchor"
          @state-change="
            (state: string, error?: string) => {
              canvasState = state;
              canvasError = error ?? '';
            }
          "
        />

        <!-- 顶部状态条：加载中 / 回退原因 / 画布错误 -->
        <div class="pointer-events-none absolute left-3 top-3 space-y-2">
          <div v-if="canvasState === 'loading'" class="pointer-events-auto">
            <Spin />
          </div>
          <Alert
            v-if="fallbackReason"
            class="pointer-events-auto max-w-md"
            closable
            :message="fallbackReason"
            type="info"
            @close="fallbackReason = ''"
          />
          <Alert
            v-if="canvasError"
            class="pointer-events-auto max-w-md"
            :message="canvasError"
            type="error"
          />
          <Alert
            v-if="ancestry.broken && currentScene"
            class="pointer-events-auto max-w-md"
            :message="$t('page.iot.twin.ancestryBroken')"
            type="warning"
          />
        </div>

        <RuntimeLegend class="absolute bottom-3 left-3" />

        <!-- 面包屑 -->
        <nav
          v-if="ancestry.chain.length > 0"
          class="absolute bottom-3 right-3 flex items-center gap-1 rounded bg-background/85 px-2 py-1 text-xs"
        >
          <template v-for="(item, index) in ancestry.chain" :key="item.id">
            <span v-if="index > 0" class="text-muted-foreground">/</span>
            <button
              class="hover:text-primary"
              type="button"
              @click="enterScene(item.id)"
            >
              {{ item.sceneName }}
            </button>
          </template>
          <button
            v-if="ancestry.chain.length > 1"
            class="ml-2 text-primary hover:underline"
            type="button"
            @click="goParent"
          >
            {{ $t('page.iot.twin.backParent') }}
          </button>
        </nav>
      </section>

      <!-- 右：点位 + 模型 -->
      <aside class="flex w-80 shrink-0 flex-col gap-3 overflow-hidden">
        <PointPanel
          class="min-h-0 flex-1"
          :editing-point-id="editor.editingPointId"
          :error="pointsError"
          :loading="pointsLoading"
          :pending="editor.hasDraft"
          :points="points"
          :scene-id="currentSceneId"
          @refresh="reloadAfterPointChange"
          @select="onSelectAnchor"
        />
        <ModelPanel
          class="shrink-0"
          @assets-loaded="onAssetsLoaded"
          @refresh="loadScenes"
        />

        <!-- 画布拾取后的新建点位表单（预填坐标与法线） -->
        <PointForm
          v-if="placeFormOpen && currentSceneId"
          :point="undefined"
          :preset-normal="pickedAnchor?.normal"
          :preset-position="pickedAnchor?.local"
          :scene-id="currentSceneId"
          @close="
            () => {
              placeFormOpen = false;
              pickedAnchor = null;
            }
          "
          @success="
            async () => {
              placeFormOpen = false;
              pickedAnchor = null;
              await reloadAfterPointChange();
            }
          "
        />
      </aside>
    </div>
  </Page>
</template>
```

> **本文件的关键设计取舍（实现时不要改回去）**：
>
> 1. **解构 `useTwinScene()` 的返回值**：`ref` 只有作为 setup 顶层返回值才在模板自动解包；
>    通过对象属性访问（`scene.scenes.value`）时**不会**解包，写成 `scene.scenes` 又会拿到
>    `Ref` 对象。解构是最不绕的写法。
> 2. **运行态回调用 `canvasRef.value?.updateAnchorStates`**：不走 emit 往返。
>    `TwinCanvas` 用 `defineExpose` 暴露该方法，父组件持组件引用直接调用 —— 少一层事件、
>    少一个中间状态，且类型安全。
> 3. **`ListErrorAlert` 只在场景列表整体失败时渲染**（整页不可用）；点位/运行态失败
>    是区块级错误，由各自区块渲染，不互相遮挡。
> 4. **`editor` 只透传 `mode` 与 `editingPointId`**（不把整个对象传进画布）：
>    画布是引擎层封装，不该知道「草稿/保存」这类页面状态。画布只关心「现在是不是要
>    拾取表面」这一个布尔信息。

---

## 16. 视图模块：`views/iot/twin/modules/twin-canvas.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import type { AnchorRuntimeState, TwinSceneState } from '#/three/twin/types';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Alert, Button } from 'ant-design-vue';

import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import { resolveModelUrl } from '#/api/iot/twin';

/**
 * three.js 画布宿主（**唯一直接 import 引擎层的组件**）。
 *
 * ## 为什么引擎层必须在这里动态 import
 *
 * `three` 压缩后约 150KB，且只有进入孪生页才用得到。若在页面顶层 import，
 * 它会被打进主包（首屏多下 150KB + 解析时间）。因此：
 * ```ts
 * const { TwinSceneManager } = await import('#/three/twin/scene-manager');
 * ```
 * 这条规则由 `vite` 的 advancedChunks 配合，`three` 会独立成 `three-core` chunk，
 * 只在进入本组件时才被请求。
 *
 * ## 职责边界
 *
 * 本组件**只做装配**：建 manager → 建锚点层 → 建 picker → 接 props → 转发事件。
 * 任何「什么时候加载哪个场景」的判断都在父组件（`index.vue`），
 * 任何「怎么渲染」的判断都在引擎层（`three/twin/*`）。
 */
const props = defineProps<{
  /** 从根到当前场景的祖先链（含自身，世界矩阵的合成依据）。 */
  ancestry: IotTwinApi.SceneResp[];
  /** 模型资产列表（父组件从 ModelPanel 共享而来）。 */
  assets: IotTwinApi.ModelAssetResp[];
  /** 祖先链是否断裂（父场景已被删除）。 */
  ancestryBroken: boolean;
  /** 当前场景的点位（运行态锚点 + 配置态叠加）。 */
  anchors: IotTwinApi.PointResp[];
  /** 编辑模式：`place` 时点击模型表面会发出 `pick` 事件。 */
  editorMode: 'browse' | 'place';
  /** 当前场景（undefined = 场景不存在）。 */
  scene?: IotTwinApi.SceneResp;
  /** 当前场景 ID。 */
  sceneId: string;
}>();

const emit = defineEmits<{
  /** 飞行模式被回退时的原因（页面要展示，不能静默）。 */
  fallback: [reason: string];
  /**
   * 配置态拾取到模型表面交点（父组件据此打开点位表单）。
   *
   * `local` 已是**本场景局部坐标**（落库口径）；`normal` 是世界坐标法线（父组件提交时
   * 由后端只存值，转换在页面完成，见 `onPlaceAnchor`）。
   */
  place: [
    payload: {
      local: { x: number; y: number; z: number };
      normal: { x: number; y: number; z: number };
    },
  ];
  /** 点击已有锚点（父组件据此高亮该点位并打开编辑）。 */
  select: [pointId: string];
  /** 画布状态变化。 */
  'state-change': [state: TwinSceneState, error?: string];
}>();

const containerRef = ref<HTMLDivElement | null>(null);
const loadError = ref('');
const loadingProgress = ref(-1);
const state = ref<TwinSceneState>('disposed');

/** 引擎层引用（动态 import 后赋值）。 */
let manager: import('#/three/twin/scene-manager').TwinSceneManager | undefined;
let anchorLayer: import('#/three/twin/anchor-layer').AnchorLayerInstance | undefined;
let picker: import('#/three/twin/picker').TwinPicker | undefined;
let cameraRig: import('#/three/twin/camera-rig').CameraRig | undefined;

/**
 * 资产索引（资产 id → 资产详情）。
 *
 * 用途：① `fileSize` 决定飞行模式是否允许；② `external` 模型的 `contentUrl`。
 * 由父组件下传（`ModelPanel` 已拉过一次资产列表，共享避免重复请求）。
 */
const assetById = computed(() => {
  const map = new Map<string, IotTwinApi.ModelAssetResp>();
  for (const asset of props.assets) {
    map.set(asset.id, asset);
  }
  return map;
});

/**
 * 模型地址（由资产决定；场景未绑模型时为 undefined）。
 *
 * **必须按资产类型分流**，不能无条件拼平台下载端点：
 * - `upload` ⇒ `/iot/twin/model-assets/{id}/content`（同源、带鉴权、走浏览器缓存）；
 * - `external` ⇒ 资产登记的外链原文（后端对该 id 调 content 端点会返回 409
 *   「该模型是外链登记，没有平台内文件」）。
 *
 * `contentUrl` 已由后端按类型算好（`ModelAssetResp.contentUrl`），这里优先用它；
 * 拿不到资产详情时（assets 尚未加载）才回退到 upload 端点——并在控制台留痕，
 * 避免「外链模型打不开」被当成后端故障排查。
 */
const modelUrl = computed(() => {
  const assetId = props.scene?.modelAssetId;
  if (!assetId) {
    return undefined;
  }
  const asset = assetById.value.get(assetId);
  if (asset?.contentUrl) {
    return resolveModelUrl(asset.contentUrl);
  }
  console.warn(
    `[iot/twin] 未取到模型资产 ${assetId} 的详情，按平台内上传端点回退；`
      + '若该模型是外链登记，加载将失败（属预期：需先加载资产列表）',
  );
  return resolveModelUrl(`/iot/twin/model-assets/${assetId}/content`);
});


/** 一次性加载引擎并装配。 */
async function mountEngine(): Promise<void> {
  const container = containerRef.value;
  if (!container) {
    return;
  }
  try {
    const THREE = await import('three');
    const { TwinSceneManager } = await import('#/three/twin/scene-manager');
    const { createAnchorLayer } = await import('#/three/twin/anchor-layer');
    const { TwinPicker } = await import('#/three/twin/picker');
    const { CameraRig } = await import('#/three/twin/camera-rig');
    const CSS2D = await import('three/addons/renderers/CSS2DRenderer.js');

    manager = new TwinSceneManager({
      container,
      errorMessages: {
        downloadFailed: (detail) =>
          $t('page.iot.twin.modelDownloadFailed', [detail]),
        parseFailed: (detail) => $t('page.iot.twin.modelParseFailed', [detail]),
      },
      onStateChange: (next, error) => {
        state.value = next;
        loadError.value = error?.message ?? '';
        emit('state-change', next, error?.message);
      },
    });
    await manager.init();

    anchorLayer = createAnchorLayer(THREE, CSS2D, container, {
      // 点锚点：上报给父组件（父组件决定是「打开设备详情」还是「编辑该点位」）
      onClickAnchor: (pointId) => {
        emit('select', pointId);
      },
    });
    manager.attachAnchorLayer(anchorLayer);

    const camera = manager.getCamera();
    const controls = manager.getControls();
    if (!camera || !controls) {
      throw new Error('引擎初始化未产出相机或控制器');
    }
    cameraRig = new CameraRig(THREE, camera, controls);
    picker = new TwinPicker({
      THREE,
      camera,
      controls,
      domElement: container,
      getModelRoot: () => manager?.getModelRoot(),
      getWorldMatrix: () => manager?.getWorldMatrix() ?? new THREE.Matrix4(),
      onClick: (result) => {
        if (props.editorMode !== 'place' || !result) {
          return;
        }
        // 只有命中模型表面才允许放置；命中已有锚点由 anchor-layer 的标签点击处理
        if (result.kind !== 'surface') {
          return;
        }
        const THREE_NS = THREE;
        const local = new THREE_NS.Vector3(
          result.point.x,
          result.point.y,
          result.point.z,
        ).applyMatrix4(manager!.getWorldMatrix().clone().invert());
        emit('place', {
          local: { x: local.x, y: local.y, z: local.z },
          normal: result.normal,
        });
      },
      useBvh: props.editorMode === 'place',
    });
  } catch (error) {
    loadError.value = extractErrorMessage(
      error,
      $t('page.iot.twin.engineInitFailed'),
    );
  }
}

/** 加载当前场景到引擎。 */
async function loadScene(): Promise<void> {
  if (!manager || !props.scene || !modelUrl.value) {
    return;
  }
  const asset = props.scene.modelAssetId
    ? assetById.value.get(props.scene.modelAssetId)
    : undefined;
  const ancestryTransform = props.ancestry.map((item) => ({
    rx: Number(item.anchorRx ?? 0),
    ry: Number(item.anchorRy ?? 0),
    rz: Number(item.anchorRz ?? 0),
    scale: Number(item.anchorScale ?? 1),
    x: Number(item.anchorX ?? 0),
    y: Number(item.anchorY ?? 0),
    z: Number(item.anchorZ ?? 0),
  }));
  const own = ancestryTransform.at(-1) ?? {
    rx: 0,
    ry: 0,
    rz: 0,
    scale: 1,
    x: 0,
    y: 0,
    z: 0,
  };
  try {
    loadingProgress.value = 0;
    const result = await manager.enterScene({
      anchor: own,
      ancestry: ancestryTransform,
      anchors: props.anchors.map((point) => ({
        deviceId: point.deviceId,
        deviceName: point.deviceIdName ?? point.deviceId,
        icon: point.icon ?? null,
        labelVisible: point.labelVisible,
        local: {
          x: Number(point.localX),
          y: Number(point.localY),
          z: Number(point.localZ),
        },
        normal: {
          x: Number(point.normalX),
          y: Number(point.normalY),
          z: Number(point.normalZ),
        },
        pointId: point.id,
        sort: point.sort ?? 0,
      })),
      basePath: modelUrl.value.slice(0, modelUrl.value.lastIndexOf('/') + 1),
      modelBytes: asset?.fileSize ?? undefined,
      modelUrl: modelUrl.value,
      sceneId: props.sceneId,
      view: parseViewJson(props.scene.viewJson),
    });
    if (result.fallbackReason) {
      emit('fallback', result.fallbackReason);
    }
    cameraRig?.captureHome();
    picker?.resetForNewModel();
    picker?.buildBvh();
    loadingProgress.value = -1;
  } catch {
    // 失败原因已通过 onStateChange 上报（画布状态变 failed）
    loadingProgress.value = -1;
  }
}

/** 解析 `view_json`（非法 JSON 不阻塞渲染：用默认视角并留痕）。 */
function parseViewJson(
  raw?: null | string,
): import('#/three/twin/types').TwinViewConfig | null {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as import('#/three/twin/types').TwinViewConfig;
  } catch (error) {
    console.warn('[iot/twin] 场景视角配置不是合法 JSON，已使用默认视角：', error);
    return null;
  }
}

/**
 * 把运行态状态推给锚点层（父组件通过组件引用调用）。
 *
 * @param states 点位 ID → 运行态
 */
function updateAnchorStates(states: Map<string, AnchorRuntimeState>): void {
  manager?.updateAnchorStates(states);
}

/** 重试加载（错误态下的按钮）。 */
async function retry(): Promise<void> {
  loadError.value = '';
  await loadScene();
}

onMounted(async () => {
  await mountEngine();
  await loadScene();
});

/** 场景切换（ancestry 变化说明场景变了）。 */
watch(
  () => props.sceneId,
  async () => {
    await loadScene();
  },
);

/** 锚点变化（点位增删改）时重建锚点。 */
watch(
  () => props.anchors,
  async () => {
    // 锚点是模型子节点，最稳的重建方式是重新 enterScene（引擎内部会 dispose 旧模型）。
    // 代价是一次模型重载；点位编辑不频繁，这个代价可接受，且能保证显存干净。
    await loadScene();
  },
  { deep: false },
);

onBeforeUnmount(() => {
  picker?.dispose();
  picker = undefined;
  anchorLayer?.dispose();
  anchorLayer = undefined;
  cameraRig?.cancelFly();
  cameraRig = undefined;
  manager?.dispose();
  manager = undefined;
});

defineExpose({ retry, updateAnchorStates });
</script>

<template>
  <div class="relative h-full w-full">
    <div ref="containerRef" class="h-full w-full" />

    <!-- 加载中 -->
    <div
      v-if="state === 'loading'"
      class="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-background/70"
    >
      <div class="text-sm text-muted-foreground">
        {{ $t('page.iot.twin.loadingModel') }}
      </div>
      <div v-if="loadingProgress >= 0" class="text-xs text-muted-foreground">
        {{ loadingProgress }}%
      </div>
    </div>

    <!-- 失败 -->
    <div
      v-if="state === 'failed' && loadError"
      class="absolute inset-0 flex items-center justify-center bg-background/85 p-6"
    >
      <Alert :message="loadError" type="error">
        <template #action>
          <Button size="small" type="primary" @click="retry">
            {{ $t('page.iot.twin.retry') }}
          </Button>
        </template>
      </Alert>
    </div>

    <!-- 场景不存在 -->
    <div
      v-if="!scene"
      class="absolute inset-0 flex items-center justify-center text-sm text-muted-foreground"
    >
      {{ $t('page.iot.twin.sceneNotFound') }}
    </div>
  </div>
</template>
```

---

## 17. 视图模块：`views/iot/twin/modules/scene-tree.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import type { SceneTreeNode } from '../composables/use-twin-scene';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { Button, Empty, message, Popconfirm, Spin, Tree } from 'ant-design-vue';

import { deleteTwinScene } from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import SceneForm from './scene-form.vue';

/**
 * 场景树（含新增/编辑/删除/下钻）。
 *
 * 用 antd `Tree` 而不是 vben 表格/树：本区块只做「展开 + 点选 + 行内操作」，
 * 属纯展示/导航用途（`vben-ui-dev` 铁律 2 的例外条款允许直接用 antd 展示类组件）。
 *
 * `treeData` 里的 `key`/`title` 是 **antd Tree 的协议字段**，不是「字段改名」：
 * 后端字段 `id`/`sceneName` 原样保留在节点对象上（`sceneCode`/`sceneType` 一并带出供操作使用）。
 */
const props = defineProps<{
  /** 是否还能给该场景加子场景（深度上限）。 */
  canAddChild: (sceneId: string) => boolean;
  /** 当前场景 ID。 */
  currentSceneId: string;
  /** 计算场景深度（用于展示层级）。 */
  depthOf: (sceneId: string) => number;
  /** 加载失败原因（空串 = 无错）。 */
  error: string;
  /** 加载中。 */
  loading: boolean;
  /** 父场景候选（按当前编辑行排除自身与后代）。 */
  parentOptions: (excludeSceneId?: string) => Array<{
    label: string;
    value: string;
  }>;
  /** 平铺场景列表（表单回填用）。 */
  scenes: IotTwinApi.SceneResp[];
  /** 组树后的数据。 */
  tree: SceneTreeNode[];
}>();

const emit = defineEmits<{
  /** 进入场景。 */
  enter: [sceneId: string];
  /** 数据已变更，需要刷新。 */
  refresh: [];
}>();

/** 展开的节点 key（受控：新增子场景后自动展开父节点）。 */
const expandedKeys = ref<string[]>([]);

/** 选中节点由 `currentSceneId` 驱动（URL 是唯一事实来源）。 */
const selectedKeys = computed(() => [props.currentSceneId]);

const [FormDrawer, formDrawerApi] = useVbenDrawer({
  connectedComponent: SceneForm,
});

/** antd Tree 的节点数据。 */
const treeData = computed<Record<string, unknown>[]>(() =>
  props.tree.map(function toNode(node): Record<string, unknown> {
    return {
      children: node.children.map(toNode),
      key: node.id,
      modelAssetId: node.modelAssetId,
      sceneCode: node.sceneCode,
      sceneName: node.sceneName,
      sceneType: node.sceneType,
      title: node.sceneName,
    };
  }),
);

/** 新增根场景。 */
function onCreateRoot(): void {
  formDrawerApi
    .setData({ parentOptions: props.parentOptions(), scenes: props.scenes })
    .open();
}

/** 新增子场景（深度到顶时明确拒绝并说明原因）。 */
function onCreateChild(node: SceneTreeNode): void {
  if (!props.canAddChild(node.id)) {
    message.warning($t('page.iot.twin.depthLimit'));
    return;
  }
  expandNode(node.id);
  formDrawerApi
    .setData({
      parentId: node.id,
      parentOptions: props.parentOptions(),
      scenes: props.scenes,
    })
    .open();
}

/** 编辑场景。 */
function onEdit(node: SceneTreeNode): void {
  formDrawerApi
    .setData({
      parentOptions: props.parentOptions(node.id),
      row: props.scenes.find((item) => item.id === node.id),
      scenes: props.scenes,
    })
    .open();
}

/** 删除场景（失败原因原样展示：后端会说明「还有子场景 / 还有点位」）。 */
async function onDelete(node: SceneTreeNode): Promise<void> {
  try {
    await deleteTwinScene(node.id);
    message.success($t('common.success'));
    emit('refresh');
  } catch (deleteError) {
    message.error(
      extractErrorMessage(deleteError, $t('page.iot.twin.deleteFailed')),
    );
  }
}

/** 树节点点选：进入场景（不是展开）。 */
function onSelect(
  _keys: string[],
  info: { node: { key: string } },
): void {
  emit('enter', String(info.node.key));
}

/** 展开某节点。 */
function expandNode(key: string): void {
  if (!expandedKeys.value.includes(key)) {
    expandedKeys.value = [...expandedKeys.value, key];
  }
}

defineExpose({ expandNode });
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex items-center justify-between border-b border-border px-3 py-2">
      <span class="text-sm font-medium">{{ $t('page.iot.twin.sceneTree') }}</span>
      <Button size="small" type="primary" @click="onCreateRoot">
        <Plus class="size-4" />
        {{ $t('common.create') }}
      </Button>
    </div>

    <div class="min-h-0 flex-1 overflow-auto p-2">
      <Spin v-if="loading && tree.length === 0" />
      <Empty
        v-else-if="tree.length === 0"
        :description="$t('page.iot.twin.noScene')"
      />
      <Tree
        v-else
        v-model:expanded-keys="expandedKeys"
        :selected-keys="selectedKeys"
        :tree-data="treeData"
        block-node
        @select="onSelect"
      >
        <template #title="{ sceneCode, sceneName, ...node }">
          <div class="group flex items-center justify-between gap-1">
            <span class="truncate">{{ sceneName }}（{{ sceneCode }}）</span>
            <span class="hidden shrink-0 gap-1 group-hover:flex">
              <Button
                size="small"
                type="link"
                @click.stop="onCreateChild(node as unknown as SceneTreeNode)"
              >
                {{ $t('common.create') }}
              </Button>
              <Button
                size="small"
                type="link"
                @click.stop="onEdit(node as unknown as SceneTreeNode)"
              >
                {{ $t('common.edit') }}
              </Button>
              <Popconfirm
                :title="
                  $t('ui.actionMessage.deleteConfirm', [String(sceneName ?? '')])
                "
                @confirm="onDelete(node as unknown as SceneTreeNode)"
              >
                <Button danger size="small" type="link">
                  {{ $t('common.delete') }}
                </Button>
              </Popconfirm>
            </span>
          </div>
        </template>
      </Tree>
    </div>

    <FormDrawer @success="emit('refresh')" />
  </div>
</template>
```

---

## 18. 视图模块：`views/iot/twin/modules/scene-form.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import { nextTick, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { message } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import { createTwinScene, updateTwinScene } from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import { useSceneFormSchema } from '../data';

/**
 * 场景新增/编辑抽屉。
 *
 * `getData()` 是**上下文包**（`row` / `parentId` / `parentOptions` / `scenes`）而不是单个
 * `SceneResp`：新建子场景要预填 `parentId`，父场景候选又要按「排除自身与后代」计算，
 * 两者都依赖树结构 ⇒ 由父组件算好传入，本组件不重复实现树逻辑。
 */
interface SceneFormData {
  /** 父场景候选（已按编辑行排除自身与后代）。 */
  parentOptions: Array<{ label: string; value: string }>;
  /** 新建子场景时预填的父场景 id。 */
  parentId?: string;
  /** 编辑时的原始行（undefined = 新建）。 */
  row?: IotTwinApi.SceneResp;
  /** 平铺场景列表。 */
  scenes: IotTwinApi.SceneResp[];
}

const emit = defineEmits<{ success: [] }>();

const isUpdate = ref(false);
const rowId = ref('');

const [Form, formApi] = useVbenForm({
  schema: useSceneFormSchema([]),
  showDefaultActions: false,
});

const [Drawer, drawerApi] = useVbenDrawer<SceneFormData | null>({
  onCancel() {
    drawerApi.close();
  },
  async onConfirm() {
    try {
      drawerApi.setState({ confirmLoading: true });
      const { valid } = await formApi.validate();
      if (!valid) {
        return;
      }
      const values = await formApi.getValues<IotTwinApi.SceneSaveReq>();
      // 根场景：父场景留空时**不传 parentId 字段**（后端把 null 视为根场景；
      // 传空串会被后端当成非法 id 而拒绝）
      const payload: IotTwinApi.SceneSaveReq = {
        ...values,
        parentId: values.parentId ? String(values.parentId) : undefined,
      };
      if (isUpdate.value) {
        await updateTwinScene(rowId.value, payload);
      } else {
        await createTwinScene(payload);
      }
      message.success($t('common.success'));
      drawerApi.close();
      emit('success');
    } catch (error) {
      message.error(extractErrorMessage(error, $t('page.iot.twin.saveFailed')));
    } finally {
      drawerApi.setState({ confirmLoading: false });
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      return;
    }
    const data = drawerApi.getData();
    isUpdate.value = Boolean(data?.row?.id);
    rowId.value = data?.row?.id ?? '';
    formApi.reset();
    // 父场景候选随「当前编辑行」变化（要排除自身与后代）⇒ 整体替换 schema。
    // 与 `batch-tags.vue` 的 `formApi.setState({ schema })` 同款做法：
    // useVbenForm 的 schema 在创建时求值，不替换就会一直用第一次的候选。
    formApi.setState({ schema: useSceneFormSchema(data?.parentOptions ?? []) });
    drawerApi.setState({
      title: isUpdate.value
        ? $t('ui.actionTitle.edit', [$t('page.iot.twin.sceneName')])
        : $t('ui.actionTitle.create', [$t('page.iot.twin.sceneName')]),
    });
    await nextTick();
    if (data?.row) {
      formApi.setValues({
        ...data.row,
        // 根场景的 parentId 是 "0"：表单里必须表现为「未选择」，
        // 否则会去匹配一个 id 为 0 的假选项（下拉里不存在，显示为空但值是 "0"）
        parentId: data.row.parentId === '0' ? undefined : data.row.parentId,
      });
    } else if (data?.parentId) {
      formApi.setValues({ parentId: data.parentId });
    }
  },
});

defineExpose({ drawerApi });
</script>

<template>
  <Drawer>
    <Form />
  </Drawer>
</template>
```

---

## 19. 视图模块：`views/iot/twin/modules/point-panel.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Plus } from '@vben/icons';

import { Button, Empty, message, Popconfirm, Spin, Tag } from 'ant-design-vue';

import { deleteTwinPoint } from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import ListErrorAlert from '../../shared/list-error-alert.vue';
import PointForm from './point-form.vue';

/**
 * 当前场景的点位列表（含失效绑定提示）。
 *
 * ## 为什么列表里必须显示 `stale` 绑定
 *
 * 绑定指向的属性会被 TSL 重新导入**物理删除**。若列表静默隐藏失效绑定，用户看到的是
 * 「这个点位配了 3 个属性」但运行态只显示 2 个值，且无从知道第 3 个去哪了。
 * 这里把它标红并在 tooltip 里说明「请重新选择属性」。
 */
const props = defineProps<{
  /** 当前正在编辑的点位 id（高亮用）。 */
  editingPointId: null | string;
  /** 加载失败原因（空串 = 无错）。 */
  error: string;
  /** 加载中。 */
  loading: boolean;
  /** 是否有未保存的草稿。 */
  pending: boolean;
  /** 点位列表。 */
  points: IotTwinApi.PointResp[];
  /** 当前场景 id。 */
  sceneId: string;
}>();

const emit = defineEmits<{
  /** 点位变更（保存/删除后刷新）。 */
  refresh: [];
  /** 选中某个点位。 */
  select: [pointId: string];
}>();

const { hasAccessByCodes } = useAccess();
const canCreate = computed(() => hasAccessByCodes(['iot:twin:create']));
const canUpdate = computed(() => hasAccessByCodes(['iot:twin:update']));
const canDelete = computed(() => hasAccessByCodes(['iot:twin:delete']));

/** 表单显隐与编辑对象。 */
const formOpen = ref(false);
const formPoint = ref<IotTwinApi.PointResp | undefined>(undefined);

/** 每个点位的失效绑定数（> 0 时标红提示）。 */
const staleCountByPoint = computed(() => {
  const map = new Map<string, number>();
  for (const point of props.points) {
    const count = point.bindings.filter((binding) => binding.stale).length;
    if (count > 0) {
      map.set(point.id, count);
    }
  }
  return map;
});

/** 新增点位。 */
function onCreate(): void {
  formPoint.value = undefined;
  formOpen.value = true;
}

/** 编辑点位。 */
function onEdit(point: IotTwinApi.PointResp): void {
  formPoint.value = point;
  formOpen.value = true;
}

/** 删除点位（连同其绑定）。 */
async function onDelete(point: IotTwinApi.PointResp): Promise<void> {
  try {
    await deleteTwinPoint(props.sceneId, point.id);
    message.success($t('common.success'));
    emit('refresh');
  } catch (deleteError) {
    message.error(
      extractErrorMessage(deleteError, $t('page.iot.twin.pointDeleteFailed')),
    );
  }
}

/** 表单保存成功。 */
function onFormSuccess(): void {
  formOpen.value = false;
  formPoint.value = undefined;
  emit('refresh');
}

/** 主展示属性的展示文本（`温度 ℃` 形式；无绑定时给明确文案而不是空白）。 */
function primaryText(point: IotTwinApi.PointResp): string {
  const primary =
    point.bindings.find((binding) => binding.displayRole === 'primary') ??
    point.bindings[0];
  if (!primary) {
    return $t('page.iot.twin.noBinding');
  }
  const unit = primary.unit ? ` ${primary.unit}` : '';
  return `${primary.propertyName}${unit}`;
}
</script>

<template>
  <div class="flex h-full flex-col rounded-lg border border-border">
    <div class="flex items-center justify-between border-b border-border px-3 py-2">
      <span class="text-sm font-medium">
        {{ $t('page.iot.twin.pointList') }}
        <span class="text-muted-foreground">（{{ points.length }}）</span>
      </span>
      <Button
        v-if="canCreate"
        :disabled="!sceneId"
        size="small"
        type="primary"
        @click="onCreate"
      >
        <Plus class="size-4" />
        {{ $t('common.create') }}
      </Button>
    </div>

    <ListErrorAlert
      v-if="error"
      :message="error"
      :title="$t('page.iot.twin.pointLoadFailed')"
    />

    <div class="min-h-0 flex-1 overflow-auto p-2">
      <Spin v-if="loading" />
      <Empty
        v-else-if="points.length === 0"
        :description="$t('page.iot.twin.noPoint')"
      />
      <ul v-else class="space-y-2">
        <li
          v-for="point in points"
          :key="point.id"
          class="cursor-pointer rounded border border-border p-2 text-xs hover:border-primary"
          :class="{ 'border-primary': point.id === editingPointId }"
          @click="emit('select', point.id)"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0 flex-1">
              <div class="truncate font-medium">
                {{ point.deviceIdName ?? point.deviceId }}
              </div>
              <div class="mt-1 truncate text-muted-foreground">
                {{ primaryText(point) }}
              </div>
              <div class="mt-1 flex flex-wrap gap-1">
                <Tag v-if="point.bindings.length > 1">
                  +{{ point.bindings.length - 1 }}
                </Tag>
                <Tag v-if="point.nodeName" color="blue">
                  {{ point.nodeName }}
                </Tag>
                <Tag
                  v-if="staleCountByPoint.get(point.id)"
                  color="red"
                  :title="$t('page.iot.twin.staleBindingHint')"
                >
                  {{
                    $t('page.iot.twin.staleCount', [
                      staleCountByPoint.get(point.id) ?? 0,
                    ])
                  }}
                </Tag>
              </div>
            </div>
            <div class="flex shrink-0 flex-col gap-1">
              <Button
                v-if="canUpdate"
                size="small"
                type="link"
                @click.stop="onEdit(point)"
              >
                {{ $t('common.edit') }}
              </Button>
              <Popconfirm
                v-if="canDelete"
                :title="
                  $t('ui.actionMessage.deleteConfirm', [
                    point.deviceIdName ?? point.deviceId,
                  ])
                "
                @confirm="onDelete(point)"
              >
                <Button danger size="small" type="link" @click.stop>
                  {{ $t('common.delete') }}
                </Button>
              </Popconfirm>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <PointForm
      v-if="formOpen"
      :point="formPoint"
      :scene-id="sceneId"
      @close="formOpen = false"
      @success="onFormSuccess"
    />
  </div>
</template>
```

---

## 20. 视图模块：`views/iot/twin/modules/device-select.vue`

```vue
<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { Select, Spin } from 'ant-design-vue';

import { getDevicePage } from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

/**
 * 设备选择器（点位表单用）。
 *
 * 用 `a-select` + 远程搜索而不是 `ApiSelect`：本选择器需要
 * 「**已选设备不在当前搜索结果里也要能显示名字**」——编辑已有点位时，设备可能是几百页
 * 之前的某台，若只靠搜索结果做 options，回显会变成裸 ID。因此这里显式维护
 * `selectedLabel` 状态，在回显时按 id 反查一次名字。
 *
 * 数据源是服务端分页接口（`pageSize: 100` + `keyword` 模糊搜索），
 * 不拉全量设备（租户可能有上万台）。
 */
const props = withDefaults(
  defineProps<{
    /** 禁用（编辑既有绑定时设备不可改：改了就是另一台设备）。 */
    disabled?: boolean;
    /** 占位文案。 */
    placeholder?: string;
    /** 选中值（设备 id 字符串）。 */
    value?: string;
  }>(),
  {
    disabled: false,
    placeholder: '',
    value: '',
  },
);

const emit = defineEmits<{ 'update:value': [value: string] }>();

const options = ref<Array<{ label: string; value: string }>>([]);
const loading = ref(false);
const error = ref('');
/** 已选设备的展示名（回显用：不在 options 里也能显示）。 */
const selectedLabel = ref('');

/** 当前值（受控）。 */
const innerValue = computed({
  get: () => props.value || undefined,
  set: (value?: string) => {
    emit('update:value', value ?? '');
  },
});

/**
 * 远程搜索设备。
 *
 * @param keyword 关键字（设备名/编码）
 */
async function search(keyword: string): Promise<void> {
  loading.value = true;
  try {
    const result = await getDevicePage({
      keyword: keyword || undefined,
      page: 1,
      pageSize: 100,
    });
    options.value = result.items.map((device) => ({
      label: `${device.deviceName}（${device.deviceCode}）`,
      value: device.id,
    }));
    error.value = '';
  } catch (searchError) {
    error.value = extractErrorMessage(
      searchError,
      $t('page.iot.twin.deviceSearchFailed'),
    );
  } finally {
    loading.value = false;
  }
}

/**
 * 按 id 反查设备名（回显用）。
 *
 * 只在「有值但 options 里没有」时触发一次，避免每次渲染都查。
 */
async function resolveLabel(id: string): Promise<void> {
  if (!id || options.value.some((option) => option.value === id)) {
    return;
  }
  try {
    const result = await getDevicePage({ page: 1, pageSize: 1, keyword: id });
    const matched = result.items.find((device) => device.id === id);
    if (matched) {
      selectedLabel.value = `${matched.deviceName}（${matched.deviceCode}）`;
    }
  } catch {
    // 反查失败不阻塞表单：显示裸 ID 比报错更有用（用户至少知道选了哪台）
    selectedLabel.value = id;
  }
}

watch(
  () => props.value,
  (value) => {
    void resolveLabel(value);
  },
  { immediate: true },
);

/** 首次聚焦时拉一页（避免打开空下拉）。 */
function onFocus(): void {
  if (options.value.length === 0) {
    void search('');
  }
}
</script>

<template>
  <Select
    v-model:value="innerValue"
    :disabled="disabled"
    :filter-option="false"
    :loading="loading"
    :not-found-content="loading ? $t('page.iot.twin.loading') : undefined"
    :options="
      selectedLabel && !options.some((item) => item.value === props.value)
        ? [{ label: selectedLabel, value: props.value }, ...options]
        : options
    "
    :placeholder="placeholder"
    allow-clear
    show-search
    @focus="onFocus"
    @search="search"
  >
    <template #notFoundContent>
      <Spin v-if="loading" size="small" />
      <span v-else-if="error" class="text-xs text-red-500">{{ error }}</span>
      <span v-else class="text-xs">{{ $t('common.noData') }}</span>
    </template>
  </Select>
</template>
```

---

## 21. 视图模块：`views/iot/twin/modules/binding-editor.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import { computed } from 'vue';

import { Plus, Trash2 } from '@vben/icons';

import { Alert, Button, Empty, Select, Tag, Tooltip } from 'ant-design-vue';

import { $t } from '#/locales';

import { displayRoleOptions, displayTypeOptions } from '../data';

/**
 * 属性绑定编辑器（变长列表子表单）。
 *
 * ## 为什么独立成组件而不是塞进 `useVbenForm` 的 schema
 *
 * 绑定列表是**变长**的（0~12 条），每条还有 6 个字段。`useVbenForm` 的 schema 是扁平
 * 字段模型，表达列表需要「`fieldName: 'bindings[0].xxx'` + 手工维护增删」——那比手写
 * 更绕且易错。按 `vben-ui-dev` 铁律 2 的例外条款（「不是表单录入控件就不走 schema」，
 * 这里是列表容器而非标量录入），本组件用原生控件组织。
 *
 * 标量字段（设备/坐标/备注）仍在 `point-form.vue` 里走 `useVbenForm`。
 */
const props = defineProps<{
  /** 已绑定的属性（受控）。 */
  bindings: IotTwinApi.BindingSaveReq[];
  /** 设备可绑定的全部属性。 */
  properties: IotTwinApi.DevicePropertyResp[];
}>();

const emit = defineEmits<{
  'update:bindings': [value: IotTwinApi.BindingSaveReq[]];
}>();

/** 属性候选（`value` 是 `serviceIdentifier + \u0000 + propertyIdentifier` 的复合键）。 */
const propertyOptions = computed(() =>
  props.properties.map((property) => ({
    label: buildPropertyLabel(property),
    value: buildPropertyKey(property.serviceIdentifier, property.propertyIdentifier),
    // 供 `option` 渲染用
    mapped: property.mapped,
    propertyIdentifier: property.propertyIdentifier,
    serviceIdentifier: property.serviceIdentifier,
    unit: property.unit,
  })),
);

/** 复合键 → 属性（回显与展示用）。 */
const propertyByKey = computed(() => {
  const map = new Map<string, IotTwinApi.DevicePropertyResp>();
  for (const property of props.properties) {
    map.set(
      buildPropertyKey(property.serviceIdentifier, property.propertyIdentifier),
      property,
    );
  }
  return map;
});

/** 是否有属性未配置采集点位（顶部统一提示一次，避免每条重复提示）。 */
const hasUnmappedBinding = computed(() =>
  props.bindings.some((binding) => {
    const property = propertyByKey.value.get(
      buildPropertyKey(binding.serviceIdentifier, binding.propertyIdentifier),
    );
    return property ? !property.mapped : false;
  }),
);

/** 属性展示名。 */
function buildPropertyLabel(property: IotTwinApi.DevicePropertyResp): string {
  const unit = property.unit ? `（${property.unit}）` : '';
  return `${property.serviceName} / ${property.propertyName}${unit}`;
}

/** 复合键（与后端 `TwinBindingResolver.key` 同构：两个标识唯一确定一次绑定）。 */
function buildPropertyKey(
  serviceIdentifier: string,
  propertyIdentifier: string,
): string {
  return `${serviceIdentifier}\u0000${propertyIdentifier}`;
}

/** 拆回复合键。 */
function splitKey(key: string): {
  propertyIdentifier: string;
  serviceIdentifier: string;
} {
  const [serviceIdentifier = '', propertyIdentifier = ''] = key.split(
    '\u0000',
  );
  return { propertyIdentifier, serviceIdentifier };
}

/** 当前某条绑定选中的复合键。 */
function selectedKey(binding: IotTwinApi.BindingSaveReq): string {
  return buildPropertyKey(
    binding.serviceIdentifier,
    binding.propertyIdentifier,
  );
}

/** 已选中的属性（用于在下拉里禁用重复项）。 */
const selectedKeys = computed(
  () => new Set(props.bindings.map((binding) => selectedKey(binding))),
);

/** 新增一条绑定（上限 12 条）。 */
function onAdd(): void {
  if (props.bindings.length >= 12) {
    return;
  }
  // 默认值：第一条为主展示，其余为次展示 —— 与后端「primary 至多一条」的约束一致，
  // 用户不需要理解这条规则就能配出合法配置。
  const nextRole =
    props.bindings.some((binding) => binding.displayRole === 'primary')
      ? 'secondary'
      : 'primary';
  emit('update:bindings', [
    ...props.bindings,
    {
      decimals: undefined,
      displayRole: nextRole,
      displayType: 'value',
      propertyIdentifier: '',
      serviceIdentifier: '',
      sort: props.bindings.length,
      unitOverride: undefined,
    },
  ]);
}

/** 删除一条绑定。 */
function onRemove(index: number): void {
  const next = props.bindings.filter((_, itemIndex) => itemIndex !== index);
  // 删除后若主展示不在了，把第一条提升为主展示（保证始终有且仅有一条 primary）
  const hasPrimary = next.some((binding) => binding.displayRole === 'primary');
  const normalized = hasPrimary
    ? next
    : next.map((binding, itemIndex) => ({
        ...binding,
        displayRole: itemIndex === 0 ? 'primary' : 'secondary',
      }));
  emit(
    'update:bindings',
    normalized.map((binding, itemIndex) => ({ ...binding, sort: itemIndex })),
  );
}

/** 修改某条的属性（同时回填 service/property 标识）。 */
function onPropertyChange(index: number, key: string): void {
  const { propertyIdentifier, serviceIdentifier } = splitKey(key);
  updateBinding(index, { propertyIdentifier, serviceIdentifier });
}

/** 修改某条的某个字段。 */
function updateBinding(
  index: number,
  patch: Partial<IotTwinApi.BindingSaveReq>,
): void {
  emit(
    'update:bindings',
    props.bindings.map((binding, itemIndex) =>
      itemIndex === index ? { ...binding, ...patch } : binding,
    ),
  );
}

/** 修改展示角色（选 primary 时把其它条降级为 secondary）。 */
function onRoleChange(index: number, role: string): void {
  const next = props.bindings.map((binding, itemIndex) => {
    if (itemIndex === index) {
      return {
        ...binding,
        displayRole: role as IotTwinApi.DisplayRole,
      };
    }
    if (role === 'primary') {
      return { ...binding, displayRole: 'secondary' as const };
    }
    return binding;
  });
  emit('update:bindings', next);
}
</script>

<template>
  <!-- vben-ui-dev-exempt: R2 绑定列表是变长列表容器，每行的紧凑控件无法用单份 schema 表达 -->
  <div class="space-y-2">
    <Alert
      v-if="hasUnmappedBinding"
      :message="$t('page.iot.twin.unmappedBindingHint')"
      type="warning"
    />

    <Empty
      v-if="bindings.length === 0"
      :description="$t('page.iot.twin.noBindingHint')"
    />

    <div
      v-for="(binding, index) in bindings"
      :key="`${index}-${selectedKey(binding)}`"
      class="rounded border border-border p-2"
    >
      <div class="flex items-start gap-2">
        <div class="min-w-0 flex-1 space-y-2">
          <Select
            :options="
              propertyOptions.filter(
                (option) =>
                  option.value === selectedKey(binding) ||
                  !selectedKeys.has(option.value),
              )
            "
            :placeholder="$t('page.iot.twin.selectProperty')"
            :value="selectedKey(binding) || undefined"
            option-filter-prop="label"
            show-search
            @change="(value: unknown) => onPropertyChange(index, String(value))"
          >
            <template #option="{ label, mapped }">
              <div class="flex items-center justify-between gap-2">
                <span class="truncate">{{ label }}</span>
                <Tag v-if="mapped === false" color="orange">
                  {{ $t('page.iot.twin.unmapped') }}
                </Tag>
              </div>
            </template>
          </Select>

          <div class="grid grid-cols-4 gap-2">
            <Select
              :options="displayTypeOptions()"
              :value="binding.displayType"
              size="small"
              @change="
                (value: unknown) =>
                  updateBinding(index, {
                    displayType: String(value) as IotTwinApi.DisplayType,
                  })
              "
            />
            <Select
              :options="displayRoleOptions()"
              :value="binding.displayRole ?? 'secondary'"
              size="small"
              @change="(value: unknown) => onRoleChange(index, String(value))"
            />
            <a-input-number
              :max="6"
              :min="0"
              :placeholder="$t('page.iot.twin.decimals')"
              :value="binding.decimals ?? undefined"
              class="w-full"
              size="small"
              @change="
                (value: number | string | null) =>
                  updateBinding(index, {
                    decimals:
                      value === null || value === '' ? undefined : Number(value),
                  })
              "
            />
            <a-input
              :maxlength="32"
              :placeholder="$t('page.iot.twin.unitOverride')"
              :value="binding.unitOverride ?? undefined"
              size="small"
              @change="
                (event: Event) =>
                  updateBinding(index, {
                    unitOverride:
                      (event.target as HTMLInputElement).value || undefined,
                  })
              "
            />
          </div>
        </div>

        <Tooltip :title="$t('common.delete')">
          <Button danger size="small" type="text" @click="onRemove(index)">
            <Trash2 class="size-4" />
          </Button>
        </Tooltip>
      </div>
    </div>

    <Button
      :disabled="bindings.length >= 12"
      block
      type="dashed"
      @click="onAdd"
    >
      <Plus class="size-4" />
      {{ $t('page.iot.twin.addBinding') }}
    </Button>
  </div>
</template>
```

---

## 22. 视图模块：`views/iot/twin/modules/point-form.vue`

```vue
<script lang="ts" setup>
import type { IotTwinApi } from '#/api/iot';

import { computed, onMounted, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Button, message, Spin } from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import {
  createTwinPoint,
  getTwinDeviceProperties,
  updateTwinPoint,
} from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import BindingEditor from './binding-editor.vue';
import DeviceSelect from './device-select.vue';

/**
 * 点位新增/编辑弹窗：标量字段走 `useVbenForm`，属性绑定走 `BindingEditor`。
 *
 * ## 结构为什么是「form + 独立区块」
 *
 * `useVbenForm` 承载标量字段（设备/坐标/法线/节点名/图标/标签/备注），它们的校验是
 * 单字段的（必填、范围）。属性绑定是**变长列表**（0~12 条 × 6 字段），
 * 用 schema 表达需要为每一行建独立 form 实例，反而更绕。两者共用一个弹窗与一个保存按钮：
 * 保存时 `formApi.getValues()` 与 `bindings.value` 合并成 `PointSaveReq`。
 */
const props = defineProps<{
  /** 编辑时的原始点位（undefined = 新建）。 */
  point?: IotTwinApi.PointResp;
  /**
   * 新建时的预填法线（来自画布拾取）。
   *
   * 与 `preset-position` 一起由 `index.vue` 在「画布拾取后新建」时传入；
   * 编辑既有点位时两者都不传（坐标以 `point` 为准）。
   */
  presetNormal?: { x: number; y: number; z: number };
  /** 新建时的预填坐标（本场景局部坐标，来自画布拾取）。 */
  presetPosition?: { x: number; y: number; z: number };
  /** 当前场景 id。 */
  sceneId: string;
}>();

const emit = defineEmits<{
  close: [];
  success: [];
}>();

const isUpdate = computed(() => Boolean(props.point?.id));
const saving = ref(false);
const formError = ref('');

const bindings = ref<IotTwinApi.BindingSaveReq[]>(
  (props.point?.bindings ?? []).map((binding) => ({
    decimals: binding.decimals ?? undefined,
    displayRole: binding.displayRole,
    displayType: binding.displayType,
    propertyIdentifier: binding.propertyIdentifier,
    serviceIdentifier: binding.serviceIdentifier,
    sort: binding.sort,
    unitOverride: binding.unitOverride ?? undefined,
  })),
);

const properties = ref<IotTwinApi.DevicePropertyResp[]>([]);
const propertiesLoading = ref(false);
const propertiesError = ref('');
/** 设备未绑定产品（**正常空态**，不是错误）。 */
const deviceWithoutProduct = ref(false);

/** 标量字段表单。 */
const [Form, formApi] = useVbenForm({
  schema: [
    {
      component: 'Input',
      fieldName: 'deviceId',
      label: $t('page.iot.twin.device'),
      rules: 'required',
      // 设备选择器是自定义组件：用 slot 承载（schema 只负责字段与校验）
      slot: 'deviceSelect',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'localX',
      label: 'X',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'localY',
      label: 'Y',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'localZ',
      label: 'Z',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'normalX',
      label: 'Nx',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'normalY',
      label: 'Ny',
    },
    {
      component: 'InputNumber',
      componentProps: { step: 0.01 },
      fieldName: 'normalZ',
      label: 'Nz',
    },
    {
      component: 'Input',
      componentProps: { maxlength: 255 },
      fieldName: 'nodeName',
      label: $t('page.iot.twin.nodeName'),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 64 },
      fieldName: 'icon',
      label: $t('page.iot.twin.icon'),
    },
    {
      component: 'Switch',
      defaultValue: true,
      fieldName: 'labelVisible',
      label: $t('page.iot.twin.labelVisible'),
    },
    {
      component: 'Textarea',
      componentProps: { maxlength: 500, rows: 2 },
      fieldName: 'remark',
      label: $t('page.iot.twin.remark'),
    },
  ],
  showDefaultActions: false,
});

/**
 * 已选设备 ID（**独立 ref**，不依赖 `formApi.form.values` 的内部对象）。
 *
 * 为什么单独一个 ref：vben 5 的 `formApi.form` 是内部对象、不保证响应式，
 * 从它读实时值会在值变更时拿不到新值。`DeviceSelect` 用 `v-model:value` 绑这个 ref，
 * 保存时以它为准；schema 里的 `deviceId` 字段只承载 label 与必填校验。
 */
const selectedDeviceId = ref<string>(props.point?.deviceId ?? '');

/** 加载设备可绑定属性。 */
async function loadProperties(deviceId: string): Promise<void> {
  if (!deviceId) {
    properties.value = [];
    deviceWithoutProduct.value = false;
    return;
  }
  propertiesLoading.value = true;
  try {
    properties.value = await getTwinDeviceProperties(deviceId);
    deviceWithoutProduct.value = properties.value.length === 0;
    propertiesError.value = '';
  } catch (loadError) {
    propertiesError.value = extractErrorMessage(
      loadError,
      $t('page.iot.twin.propertyLoadFailed'),
    );
    properties.value = [];
  } finally {
    propertiesLoading.value = false;
  }
}

/** 设备变化 ⇒ 重拉属性（并清空已选绑定：旧绑定在新设备上大概率不存在）。 */
watch(selectedDeviceId, async (deviceId, previous) => {
  if (deviceId && deviceId !== previous) {
    // 换设备 ⇒ 旧绑定大概率在新设备上不存在，清掉（保留编辑态：编辑时设备不可改）
    if (!isUpdate.value) {
      bindings.value = [];
    }
    await loadProperties(deviceId);
  }
});

/** 检出重复绑定（同服务同属性）。 */
function findDuplicateBinding(
  list: IotTwinApi.BindingSaveReq[],
): string | undefined {
  const seen = new Set<string>();
  for (const item of list) {
    if (!item.serviceIdentifier || !item.propertyIdentifier) {
      // 未选属性的空行由保存前的完整性校验拦
      continue;
    }
    const key = `${item.serviceIdentifier}\u0000${item.propertyIdentifier}`;
    if (seen.has(key)) {
      return `${item.serviceIdentifier}.${item.propertyIdentifier}`;
    }
    seen.add(key);
  }
  return undefined;
}

/** 保存。 */
async function onSave(): Promise<void> {
  formError.value = '';
  const { valid } = await formApi.validate();
  if (!valid) {
    return;
  }
  if (!selectedDeviceId.value) {
    message.warning($t('page.iot.twin.deviceRequired'));
    return;
  }
  const values = await formApi.getValues<{
    icon?: string;
    labelVisible?: boolean;
    localX: number;
    localY: number;
    localZ: number;
    nodeName?: string;
    normalX?: number;
    normalY?: number;
    normalZ?: number;
    remark?: string;
  }>();

  // 绑定完整性：不允许存在「选了行但没选属性」的空行
  const incomplete = bindings.value.some(
    (binding) => !binding.serviceIdentifier || !binding.propertyIdentifier,
  );
  if (incomplete) {
    message.warning($t('page.iot.twin.incompleteBinding'));
    return;
  }
  const duplicate = findDuplicateBinding(bindings.value);
  if (duplicate) {
    message.warning($t('page.iot.twin.duplicateBinding', [duplicate]));
    return;
  }
  if (
    bindings.value.filter((item) => item.displayRole === 'primary').length > 1
  ) {
    message.warning($t('page.iot.twin.multiplePrimary'));
    return;
  }

  saving.value = true;
  try {
    const payload: IotTwinApi.PointSaveReq = {
      bindings: bindings.value,
      deviceId: selectedDeviceId.value,
      icon: values.icon || undefined,
      labelVisible: values.labelVisible ?? true,
      localX: values.localX,
      localY: values.localY,
      localZ: values.localZ,
      nodeName: values.nodeName || undefined,
      normalX: values.normalX ?? 0,
      normalY: values.normalY ?? 1,
      normalZ: values.normalZ ?? 0,
      remark: values.remark || undefined,
    };
    if (isUpdate.value && props.point) {
      await updateTwinPoint(props.sceneId, props.point.id, payload);
    } else {
      await createTwinPoint(props.sceneId, payload);
    }
    message.success($t('common.success'));
    modalApi.close();
    emit('success');
  } catch (saveError) {
    formError.value = extractErrorMessage(
      saveError,
      $t('page.iot.twin.saveFailed'),
    );
  } finally {
    saving.value = false;
  }
}

const [Modal, modalApi] = useVbenModal({
  onClosed() {
    emit('close');
  },
});

onMounted(async () => {
  modalApi.setState({
    title: isUpdate.value
      ? $t('ui.actionTitle.edit', [$t('page.iot.twin.point')])
      : $t('ui.actionTitle.create', [$t('page.iot.twin.point')]),
  });
  if (!props.point && props.presetPosition) {
    // 画布拾取后新建：坐标与法线直接填入，用户只需选设备与属性
    formApi.setValues({
      localX: props.presetPosition.x,
      localY: props.presetPosition.y,
      localZ: props.presetPosition.z,
      normalX: props.presetNormal?.x ?? 0,
      normalY: props.presetNormal?.y ?? 1,
      normalZ: props.presetNormal?.z ?? 0,
    });
  }
  if (props.point) {
    selectedDeviceId.value = props.point.deviceId;
    formApi.setValues({
      deviceId: props.point.deviceId,
      icon: props.point.icon ?? undefined,
      labelVisible: props.point.labelVisible,
      localX: Number(props.point.localX),
      localY: Number(props.point.localY),
      localZ: Number(props.point.localZ),
      nodeName: props.point.nodeName ?? undefined,
      normalX: Number(props.point.normalX),
      normalY: Number(props.point.normalY),
      normalZ: Number(props.point.normalZ),
      remark: props.point.remark ?? undefined,
    });
    await loadProperties(props.point.deviceId);
  }
  modalApi.open();
});

/** 设备选择器的值变更（slot 内组件用）：同步 ref 与 schema 字段。 */
function onDeviceChange(deviceId: string): void {
  selectedDeviceId.value = deviceId;
  formApi.setValues({ deviceId });
}
</script>

<template>
  <Modal :confirm-loading="saving" class="w-[760px]" @confirm="onSave">
    <Alert
      v-if="formError"
      class="mb-3"
      closable
      :message="formError"
      type="error"
      @close="formError = ''"
    />
    <Alert
      v-if="deviceWithoutProduct"
      class="mb-3"
      :message="$t('page.iot.twin.deviceWithoutProduct')"
      type="warning"
    />
    <Alert
      v-if="propertiesError"
      class="mb-3"
      :message="propertiesError"
      type="error"
    />

    <Form>
      <template #deviceSelect>
        <DeviceSelect
          v-model:value="selectedDeviceId"
          :disabled="isUpdate"
          :placeholder="$t('page.iot.twin.devicePlaceholder')"
          @update:value="onDeviceChange"
        />
      </template>
    </Form>

    <div class="mt-4 border-t border-border pt-3">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-sm font-medium">
          {{ $t('page.iot.twin.bindings') }}
        </span>
        <span class="text-xs text-muted-foreground">
          {{ $t('page.iot.twin.bindingLimit', [12]) }}
        </span>
      </div>
      <Spin v-if="propertiesLoading" />
      <BindingEditor
        v-else
        v-model:bindings="bindings"
        :properties="properties"
      />
    </div>

    <template #footer>
      <Button @click="modalApi.close()">{{ $t('common.cancel') }}</Button>
      <Button :loading="saving" type="primary" @click="onSave">
        {{ $t('page.iot.twin.save') }}
      </Button>
    </template>
  </Modal>
</template>
```

---

## 23. 视图模块：`views/iot/twin/modules/model-panel.vue`

```vue
<script lang="ts" setup>
import type { UploadProps } from 'ant-design-vue';

import type { IotTwinApi } from '#/api/iot';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Upload } from '@vben/icons';

import {
  Alert,
  Button,
  Empty,
  message,
  Popconfirm,
  Progress,
  Spin,
  Tag,
  Tooltip,
  Upload as AntUpload,
} from 'ant-design-vue';

import {
  createModelAssetExternal,
  deleteModelAsset,
  getModelAssetList,
  uploadModelAsset,
} from '#/api/iot';
import { $t } from '#/locales';
import { extractErrorMessage } from '#/utils/error';

import ListErrorAlert from '../../shared/list-error-alert.vue';

/**
 * 模型资产管理面板（上传 / 外链登记 / 删除 / 引用数）。
 *
 * ## 上传通道为什么是独立的
 *
 * 不复用 `/system/file/upload`：该端点标了 `@PlatformAccess`（租户用户 403），
 * 且依赖未配置的 `ypbin.storage`。孪生模型走自建的 `/iot/twin/model-assets/upload`，
 * 支持 64MB、带进度、带魔数校验（详见设计文档 §6.7）。
 */
const emit = defineEmits<{
  /** 资产列表已加载（父组件共享给画布，避免重复请求）。 */
  'assets-loaded': [assets: IotTwinApi.ModelAssetResp[]];
  /** 资产变更（场景可能引用了它，需要刷新场景树）。 */
  refresh: [];
}>();

const { hasAccessByCodes } = useAccess();
const canCreate = computed(() => hasAccessByCodes(['iot:twin:create']));
const canDelete = computed(() => hasAccessByCodes(['iot:twin:delete']));

const assets = ref<IotTwinApi.ModelAssetResp[]>([]);
const loading = ref(false);
const error = ref('');
const uploadPercent = ref(-1);
const uploading = ref(false);
const externalOpen = ref(false);
const externalName = ref('');
const externalUrl = ref('');
const externalSaving = ref(false);

/** 拉取资产列表。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    assets.value = await getModelAssetList();
    emit('assets-loaded', assets.value);
    error.value = '';
  } catch (loadError) {
    error.value = extractErrorMessage(
      loadError,
      $t('page.iot.twin.assetLoadFailed'),
    );
  } finally {
    loading.value = false;
  }
}

/** 上传前拦截（扩展名 + 大小，与后端 `TwinLimits` 同口径）。 */
const beforeUpload: UploadProps['beforeUpload'] = (file) => {
  const name = file.name.toLowerCase();
  const allowed = ['.glb', '.gltf'];
  if (!allowed.some((extension) => name.endsWith(extension))) {
    message.error($t('page.iot.twin.modelFormatInvalid'));
    return false;
  }
  if (file.size > 64 * 1024 * 1024) {
    message.error($t('page.iot.twin.modelTooLarge'));
    return false;
  }
  void doUpload(file);
  // 返回 false 阻止 antd 的默认上传行为（我们自己发请求）
  return false;
};

/** 执行上传。 */
async function doUpload(file: File): Promise<void> {
  uploading.value = true;
  uploadPercent.value = 0;
  try {
    await uploadModelAsset(file, {
      onProgress: (percent) => {
        uploadPercent.value = percent;
      },
    });
    message.success($t('common.success'));
    await load();
    emit('refresh');
  } catch (uploadError) {
    message.error(
      extractErrorMessage(uploadError, $t('page.iot.twin.uploadFailed')),
    );
  } finally {
    uploading.value = false;
    uploadPercent.value = -1;
  }
}

/** 登记外链模型。 */
async function onExternalSave(): Promise<void> {
  if (!externalName.value.trim()) {
    message.warning($t('page.iot.twin.assetNameRequired'));
    return;
  }
  if (!/^https?:\/\//i.test(externalUrl.value.trim())) {
    message.warning($t('page.iot.twin.externalUrlInvalid'));
    return;
  }
  externalSaving.value = true;
  try {
    await createModelAssetExternal({
      assetName: externalName.value.trim(),
      externalUrl: externalUrl.value.trim(),
    });
    message.success($t('common.success'));
    externalOpen.value = false;
    externalName.value = '';
    externalUrl.value = '';
    await load();
    emit('refresh');
  } catch (saveError) {
    message.error(
      extractErrorMessage(saveError, $t('page.iot.twin.saveFailed')),
    );
  } finally {
    externalSaving.value = false;
  }
}

/** 删除资产（被场景引用时后端拒绝并说明引用数）。 */
async function onDelete(asset: IotTwinApi.ModelAssetResp): Promise<void> {
  try {
    await deleteModelAsset(asset.id);
    message.success($t('common.success'));
    await load();
    emit('refresh');
  } catch (deleteError) {
    message.error(
      extractErrorMessage(deleteError, $t('page.iot.twin.deleteFailed')),
    );
  }
}

/** 文件体积展示。 */
function formatSize(bytes?: null | number): string {
  if (!bytes) {
    return '-';
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

onMounted(async () => {
  await load();
});
</script>

<template>
  <!-- vben-ui-dev-exempt: R2 外链登记是内联双字段小表单，单独建 form 实例成本大于收益 -->
  <div class="rounded-lg border border-border">
    <div class="flex items-center justify-between border-b border-border px-3 py-2">
      <span class="text-sm font-medium">{{ $t('page.iot.twin.modelAssets') }}</span>
      <div v-if="canCreate" class="flex items-center gap-1">
        <AntUpload :before-upload="beforeUpload" :show-upload-list="false">
          <Button :loading="uploading" size="small" type="primary">
            <Upload class="size-4" />
            {{ $t('page.iot.twin.uploadModel') }}
          </Button>
        </AntUpload>
        <Button size="small" @click="externalOpen = true">
          {{ $t('page.iot.twin.registerExternal') }}
        </Button>
      </div>
    </div>

    <Progress
      v-if="uploadPercent >= 0"
      :percent="uploadPercent"
      class="px-3 pt-2"
      size="small"
    />

    <ListErrorAlert
      v-if="error"
      :message="error"
      :title="$t('page.iot.twin.assetLoadFailed')"
    />

    <div class="max-h-48 overflow-auto p-2">
      <Spin v-if="loading && assets.length === 0" />
      <Empty
        v-else-if="assets.length === 0"
        :description="$t('page.iot.twin.noAsset')"
      />
      <ul v-else class="space-y-1">
        <li
          v-for="asset in assets"
          :key="asset.id"
          class="flex items-center justify-between gap-2 rounded px-1 py-1 text-xs hover:bg-muted/40"
        >
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-1">
              <span class="truncate font-medium">{{ asset.assetName }}</span>
              <Tag>{{ asset.modelFormat }}</Tag>
              <Tag v-if="asset.assetType === 'external'" color="blue">
                {{ $t('page.iot.twin.external') }}
              </Tag>
            </div>
            <div class="text-muted-foreground">
              {{ formatSize(asset.fileSize) }}
              <span v-if="asset.sceneCount > 0">
                · {{ $t('page.iot.twin.sceneRefCount', [asset.sceneCount]) }}
              </span>
            </div>
          </div>
          <Tooltip
            v-if="asset.sceneCount > 0"
            :title="$t('page.iot.twin.assetInUseHint')"
          >
            <Button disabled size="small" type="link">
              {{ $t('common.delete') }}
            </Button>
          </Tooltip>
          <Popconfirm
            v-else-if="canDelete"
            :title="
              $t('ui.actionMessage.deleteConfirm', [asset.assetName])
            "
            @confirm="onDelete(asset)"
          >
            <Button danger size="small" type="link">
              {{ $t('common.delete') }}
            </Button>
          </Popconfirm>
        </li>
      </ul>
    </div>

    <!-- 外链登记 -->
    <div v-if="externalOpen" class="border-t border-border p-3">
      <Alert
        class="mb-2"
        :message="$t('page.iot.twin.externalHint')"
        type="info"
      />
      <a-input
        v-model:value="externalName"
        :maxlength="128"
        :placeholder="$t('page.iot.twin.assetName')"
        class="mb-2"
      />
      <a-input
        v-model:value="externalUrl"
        :maxlength="1024"
        :placeholder="$t('page.iot.twin.externalUrl')"
        class="mb-2"
      />
      <div class="flex justify-end gap-2">
        <Button size="small" @click="externalOpen = false">
          {{ $t('common.cancel') }}
        </Button>
        <Button
          :loading="externalSaving"
          size="small"
          type="primary"
          @click="onExternalSave"
        >
          {{ $t('page.iot.twin.save') }}
        </Button>
      </div>
    </div>
  </div>
</template>
```


---

## 24. 视图模块：`views/iot/twin/modules/runtime-legend.vue`

```vue
<script lang="ts" setup>
import { computed } from 'vue';

import { $t } from '#/locales';

/**
 * 运行态图例（在线 / 离线 / 未知 / 停用 / 告警）。
 *
 * 图例必须存在：颜色语义若没有说明，运维会把「黄色」理解成「警告」而不是「平台还没拿到
 * 连接事件」。三色 + 停用色与 `anchor-layer.ts` 的常量**必须逐一对应**。
 */
interface LegendItem {
  color: string;
  label: string;
  /** 是否闪烁（告警项用呼吸动画示意）。 */
  blink?: boolean;
}

const items = computed<LegendItem[]>(() => [
  { color: '#22c55e', label: $t('page.iot.twin.legendOnline') },
  { color: '#94a3b8', label: $t('page.iot.twin.legendOffline') },
  { color: '#f59e0b', label: $t('page.iot.twin.legendUnknown') },
  { color: '#475569', label: $t('page.iot.twin.legendDisabled') },
  { blink: true, color: '#ef4444', label: $t('page.iot.twin.legendAlert') },
]);
</script>

<template>
  <ul
    class="flex items-center gap-3 rounded bg-background/80 px-2 py-1 text-xs backdrop-blur"
  >
    <li v-for="item in items" :key="item.label" class="flex items-center gap-1">
      <span
        class="inline-block size-2.5 rounded-full"
        :class="{ 'animate-pulse': item.blink }"
        :style="{ backgroundColor: item.color }"
      />
      <span class="text-muted-foreground">{{ item.label }}</span>
    </li>
  </ul>
</template>
```

---

## 25. i18n（`locales/langs/zh-CN/page.json` 的 `iot.twin` 段）

```jsonc
{
  "iot": {
    // ...既有...
    "twin": {
      "title": "数字孪生",
      "sceneTree": "场景树",
      "sceneCode": "场景编码",
      "sceneCodePlaceholder": "字母、数字、下划线、中划线，租户内唯一",
      "sceneName": "场景名称",
      "parentScene": "父场景",
      "parentPlaceholder": "留空表示根场景",
      "sceneTypeLabel": "场景类型",
      "sceneType": {
        "park": "园区",
        "building": "楼栋",
        "floor": "楼层",
        "room": "房间"
      },
      "anchorX": "锚点 X",
      "anchorY": "锚点 Y",
      "anchorZ": "锚点 Z",
      "anchorRy": "锚点旋转 Y（度）",
      "anchorRotationHelp": "子场景相对父场景的朝向；整栋楼平移/旋转只需改这一处",
      "anchorScale": "锚点缩放",
      "anchorScaleHelp": "必须大于 0；用于不同建模单位的对齐（如米与毫米）",
      "sort": "排序",
      "create": "新增",
      "save": "保存",
      "retry": "重试",
      "loading": "加载中…",
      "sceneLoadFailed": "场景加载失败",
      "sceneNotFound": "场景不存在（可能已被删除）",
      "noScene": "还没有任何场景，先新建一个园区场景",
      "selectSceneHint": "从左侧选择一个场景开始",
      "backParent": "返回上级",
      "depthLimit": "场景层级已达上限（4 层）：房间级展示请在楼层场景内做区域标注",
      "ancestryBroken": "父场景已不存在，当前场景的模型位置可能不准确，请重新设置父场景",
      "saveFailed": "保存失败",
      "deleteFailed": "删除失败",
      "loadingModel": "正在加载模型…",
      "modelDownloadFailed": "模型下载失败：{0}",
      "modelParseFailed": "模型解析失败：{0}",
      "engineInitFailed": "3D 引擎初始化失败（浏览器可能不支持 WebGL）",
      "pointList": "设备点位",
      "point": "点位",
      "noPoint": "本场景还没有设备点位",
      "pointLoadFailed": "点位加载失败",
      "pointDeleteFailed": "点位删除失败",
      "device": "设备",
      "devicePlaceholder": "搜索设备名称或编码",
      "deviceRequired": "请选择设备",
      "deviceSearchFailed": "设备搜索失败",
      "deviceWithoutProduct": "该设备未绑定产品，无法配置属性：请先在设备台账里为其选择产品",
      "nodeName": "模型节点名",
      "nodeNamePlaceholder": "可选：用于高亮对应模型节点",
      "icon": "图标标识",
      "labelVisible": "常显标签",
      "remark": "备注",
      "bindings": "属性绑定",
      "bindingLimit": "最多 {0} 个",
      "noBinding": "未绑定属性",
      "noBindingHint": "尚未绑定任何属性：该点位只会显示设备状态色",
      "addBinding": "添加属性",
      "selectProperty": "选择属性",
      "unmapped": "未配置采集",
      "unmappedBindingHint": "部分属性尚未配置采集点位，绑定后不会有实时数据（请到设备详情「属性与点位」里补齐）",
      "staleCount": "{0} 个属性已失效",
      "staleBindingHint": "属性在物模型里已不存在（可能是 TSL 重新导入导致），请删除后重新选择",
      "duplicateBinding": "属性绑定重复：{0}",
      "multiplePrimary": "每个点位只能有一个主展示属性",
      "incompleteBinding": "有尚未选择属性的绑定行，请先选择或删除",
      "decimals": "小数位",
      "unitOverride": "单位覆盖",
      "displayType": {
        "value": "数值",
        "gauge": "仪表盘",
        "switch": "开关",
        "color": "颜色",
        "sparkline": "迷你折线"
      },
      "displayRole": {
        "primary": "主展示",
        "secondary": "次展示"
      },
      "propertyLoadFailed": "属性加载失败",
      "runtimeLoadFailed": "运行数据加载失败",
      "alertCountFailed": "告警数刷新失败（设备状态不受影响）",
      "modelAssets": "模型资产",
      "noAsset": "还没有上传任何模型",
      "assetLoadFailed": "模型资产加载失败",
      "uploadModel": "上传模型",
      "registerExternal": "登记外链",
      "modelFormatInvalid": "只支持 .glb 与 .gltf 模型文件",
      "modelTooLarge": "模型文件不能超过 64MB，请先压缩",
      "uploadFailed": "模型上传失败",
      "external": "外链",
      "externalHint": "服务端不主动拉取外链地址（避免内网探测），连通性由页面加载时暴露",
      "assetName": "模型名称",
      "assetNameRequired": "请填写模型名称",
      "externalUrl": "外链地址（http/https）",
      "externalUrlInvalid": "外链地址必须是 http:// 或 https:// 开头",
      "sceneRefCount": "被 {0} 个场景引用",
      "assetInUseHint": "该模型被场景引用，需先换绑才能删除",
      "legendOnline": "在线",
      "legendOffline": "离线",
      "legendUnknown": "未知（未收到连接事件）",
      "legendDisabled": "停用",
      "legendAlert": "告警"
    }
  }
}
```

`locales/langs/en-US/page.json` 的 `iot.twin` 段（键集合**必须与 zh-CN 完全一致**）：

```jsonc
{
  "iot": {
    "twin": {
      "title": "Digital Twin",
      "sceneTree": "Scenes",
      "sceneCode": "Scene code",
      "sceneCodePlaceholder": "Letters, digits, underscore, hyphen; unique within tenant",
      "sceneName": "Scene name",
      "parentScene": "Parent scene",
      "parentPlaceholder": "Leave empty for a root scene",
      "sceneTypeLabel": "Scene type",
      "sceneType": {
        "park": "Park",
        "building": "Building",
        "floor": "Floor",
        "room": "Room"
      },
      "anchorX": "Anchor X",
      "anchorY": "Anchor Y",
      "anchorZ": "Anchor Z",
      "anchorRy": "Anchor rotation Y (deg)",
      "anchorRotationHelp": "Orientation relative to the parent scene; moving or rotating a whole building only needs this one change",
      "anchorScale": "Anchor scale",
      "anchorScaleHelp": "Must be greater than 0; used to align different modeling units (e.g. meters vs millimeters)",
      "sort": "Sort",
      "create": "Create",
      "save": "Save",
      "retry": "Retry",
      "loading": "Loading…",
      "sceneLoadFailed": "Failed to load scenes",
      "sceneNotFound": "Scene not found (it may have been deleted)",
      "noScene": "No scenes yet. Create a park scene to start",
      "selectSceneHint": "Select a scene on the left to start",
      "backParent": "Back to parent",
      "depthLimit": "Maximum scene depth (4) reached: represent rooms as zones inside a floor scene",
      "ancestryBroken": "The parent scene no longer exists; this scene's model position may be inaccurate. Please set its parent again",
      "saveFailed": "Failed to save",
      "deleteFailed": "Failed to delete",
      "loadingModel": "Loading model…",
      "modelDownloadFailed": "Failed to download model: {0}",
      "modelParseFailed": "Failed to parse model: {0}",
      "engineInitFailed": "Failed to initialize the 3D engine (WebGL may be unsupported)",
      "pointList": "Device points",
      "point": "Point",
      "noPoint": "No device points in this scene",
      "pointLoadFailed": "Failed to load points",
      "pointDeleteFailed": "Failed to delete point",
      "device": "Device",
      "devicePlaceholder": "Search by device name or code",
      "deviceRequired": "Please select a device",
      "deviceSearchFailed": "Failed to search devices",
      "deviceWithoutProduct": "This device has no product bound, so properties cannot be configured. Bind a product in the device ledger first",
      "nodeName": "Model node name",
      "nodeNamePlaceholder": "Optional: used to highlight the corresponding model node",
      "icon": "Icon key",
      "labelVisible": "Always show label",
      "remark": "Remark",
      "bindings": "Property bindings",
      "bindingLimit": "Up to {0}",
      "noBinding": "No property bound",
      "noBindingHint": "No properties bound: this point only shows device status color",
      "addBinding": "Add property",
      "selectProperty": "Select property",
      "unmapped": "Not collected",
      "unmappedBindingHint": "Some properties have no collection point configured, so they will never produce live data. Configure them under the device's Properties & Points tab",
      "staleCount": "{0} property binding(s) invalidated",
      "staleBindingHint": "The property no longer exists in the thing model (possibly due to a TSL re-import). Remove and select it again",
      "duplicateBinding": "Duplicate property binding: {0}",
      "multiplePrimary": "A point can have only one primary property",
      "incompleteBinding": "Some binding rows have no property selected. Please select or remove them",
      "decimals": "Decimals",
      "unitOverride": "Unit override",
      "displayType": {
        "value": "Value",
        "gauge": "Gauge",
        "switch": "Switch",
        "color": "Color",
        "sparkline": "Sparkline"
      },
      "displayRole": {
        "primary": "Primary",
        "secondary": "Secondary"
      },
      "propertyLoadFailed": "Failed to load properties",
      "runtimeLoadFailed": "Failed to load runtime data",
      "alertCountFailed": "Failed to refresh alert counts (device status is unaffected)",
      "modelAssets": "Model assets",
      "noAsset": "No model uploaded yet",
      "assetLoadFailed": "Failed to load model assets",
      "uploadModel": "Upload model",
      "registerExternal": "Register external",
      "modelFormatInvalid": "Only .glb and .gltf files are supported",
      "modelTooLarge": "Model file must not exceed 64MB. Please compress it first",
      "uploadFailed": "Failed to upload model",
      "external": "External",
      "externalHint": "The server does not fetch external URLs (to avoid internal network probing); connectivity issues surface when the page loads the model",
      "assetName": "Model name",
      "assetNameRequired": "Please enter a model name",
      "externalUrl": "External URL (http/https)",
      "externalUrlInvalid": "External URL must start with http:// or https://",
      "sceneRefCount": "Referenced by {0} scene(s)",
      "assetInUseHint": "This model is referenced by scenes; rebind them before deleting",
      "legendOnline": "Online",
      "legendOffline": "Offline",
      "legendUnknown": "Unknown (no connection event yet)",
      "legendDisabled": "Disabled",
      "legendAlert": "Alert"
    }
  }
}
```

> **i18n 铁律提醒**：`$t` 的消息编译器会把文本里的 `{` `}` 当成占位符 —— 本段的文案里
> **严禁出现裸花括号**（2026-09-27 曾因此整页空白，见 `panel-error-boundary.vue` 的类注释）。
> 上面的文案已全部规避；新增文案时必须复查。

---

## 26. 深链测试：`views/iot/twin/twin-deeplink.test.ts`

```ts
import { createApp, defineComponent, h, nextTick } from 'vue';

import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

/**
 * 数字孪生页的**深链**行为（与设备详情 `?deviceId=` 同口径）。
 *
 * 行为约定：
 * 1. 进入场景 ⇒ `router.replace({ query: { sceneId } })`（replace 不产生历史栈垃圾）；
 * 2. 带 `?sceneId=` 挂载 ⇒ 自动进入该场景；
 * 3. `sceneId` 不存在 ⇒ **仍然尝试加载**（画布给出「场景不存在」错误态），不静默跳回根场景；
 * 4. 浏览器前进后退改变 `sceneId` ⇒ 场景跟着切换。
 *
 * 打桩口径：`vue-router` 的 `useRoute`/`useRouter` 用最小桩模拟（与
 * `devices/detail-deeplink.test.ts` 同款思路），API 全部 mock。
 */
const { mockApi, routeRef, routerCalls } = vi.hoisted(() => ({
  mockApi: {
    getTwinPointList: vi.fn(),
    getTwinRuntime: vi.fn(),
    getTwinSceneList: vi.fn(),
  },
  routeRef: { state: null as unknown },
  routerCalls: { replace: [] as unknown[] },
}));

vi.mock('vue-router', () => ({
  useRoute: () => routeRef.state,
  useRouter: () => ({
    replace: (payload: unknown) => {
      routerCalls.replace.push(payload);
      return Promise.resolve();
    },
  }),
}));

vi.mock('#/api/iot', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('#/api/iot');
  return {
    ...actual,
    getTwinPointList: mockApi.getTwinPointList,
    getTwinRuntime: mockApi.getTwinRuntime,
    getTwinSceneList: mockApi.getTwinSceneList,
  };
});

// 画布组件依赖 WebGL，测试里替换成空壳（深链测试只关心「进入哪个场景」）
vi.mock('./modules/twin-canvas.vue', () => ({
  default: defineComponent({
    name: 'TwinCanvasStub',
    props: {
      sceneId: { default: '', type: String },
    },
    setup(props) {
      return () =>
        h('div', { class: 'canvas-stub', 'data-scene-id': props.sceneId });
    },
    methods: {
      updateAnchorStates() {
        // 桩：运行态推送在深链测试里不参与断言
      },
    },
  }),
}));

vi.mock('../shared/use-iot-realtime', () => ({
  useIotRealtime: () => ({
    authError: { value: '' },
    error: { value: '' },
    onEvent: () => () => undefined,
    state: { value: 'live' },
  }),
}));

describe('数字孪生页深链', () => {
  beforeAll(async () => {
    await import('#/locales').then((module) => module.setupI18n?.({
      app: createApp({}),
      locale: 'zh-CN',
    } as never));
  });

  beforeEach(() => {
    routerCalls.replace = [];
    mockApi.getTwinSceneList.mockResolvedValue([
      {
        anchorRx: '0',
        anchorRy: '0',
        anchorRz: '0',
        anchorScale: '1',
        anchorX: '0',
        anchorY: '0',
        anchorZ: '0',
        id: '5001',
        parentId: '0',
        pointCount: 0,
        childCount: 0,
        sceneCode: 'PARK',
        sceneName: '园区',
        sceneType: 'park',
        sort: 0,
      },
      {
        anchorRx: '0',
        anchorRy: '0',
        anchorRz: '0',
        anchorScale: '1',
        anchorX: '0',
        anchorY: '3',
        anchorZ: '0',
        id: '5002',
        parentId: '5001',
        pointCount: 0,
        childCount: 0,
        sceneCode: 'B1',
        sceneName: '1 号楼',
        sceneType: 'building',
        sort: 0,
      },
    ]);
    mockApi.getTwinPointList.mockResolvedValue([]);
    mockApi.getTwinRuntime.mockResolvedValue({
      pointCount: 0,
      points: [],
      sceneId: '5001',
      serverTime: '2026-10-21 10:30:00',
      truncated: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('★ 带 ?sceneId= 挂载 ⇒ 画布收到该场景 id', async () => {
    routeRef.state = { query: { sceneId: '5002' } };
    const Page = (await import('./index.vue')).default;
    const host = document.createElement('div');
    const app = createApp(Page);
    app.mount(host);
    await nextTick();
    await nextTick();
    await vi.waitFor(() => {
      const canvas = host.querySelector('.canvas-stub');
      expect(canvas?.getAttribute('data-scene-id')).toBe('5002');
    });
    app.unmount();
  });

  it('★ sceneId 不存在 ⇒ 仍然按该 id 尝试加载（不静默跳回根场景）', async () => {
    routeRef.state = { query: { sceneId: '9999' } };
    const Page = (await import('./index.vue')).default;
    const host = document.createElement('div');
    const app = createApp(Page);
    app.mount(host);
    await nextTick();
    await nextTick();
    // 没有自动 replace 到根场景（"静默跳回"会让用户以为自己点错了）
    expect(routerCalls.replace).toHaveLength(0);
    // 画布拿到的是请求的 id，由画布内部展示「场景不存在」
    await vi.waitFor(() => {
      const canvas = host.querySelector('.canvas-stub');
      expect(canvas?.getAttribute('data-scene-id')).toBe('9999');
    });
    app.unmount();
  });

  it('无 sceneId ⇒ 画布不渲染（展示「请选择场景」引导）', async () => {
    routeRef.state = { query: {} };
    const Page = (await import('./index.vue')).default;
    const host = document.createElement('div');
    const app = createApp(Page);
    app.mount(host);
    await nextTick();
    await nextTick();
    expect(host.querySelector('.canvas-stub')).toBeNull();
    app.unmount();
  });
});
```

---

## 27. 校验测试：`views/iot/twin/twin-validate.test.ts`

```ts
import { describe, expect, it } from 'vitest';

import { IotTwinApi } from '#/api/iot';

/**
 * 前端校验与后端 `TwinLimits` 的**同口径**。
 *
 * 这些常量必须与后端逐一相等：漂移会导致「前端放过、后端拒绝」（用户白填一遍表单）
 * 或反过来（前端拦住后端本可接受的输入）。与 `DEVICE_IMPORT_FRONTEND_LIMITS`
 * ←→ `DeviceImportLimits` 的既有模式一致。
 */
describe('TwinLimits 与后端同口径', () => {
  it('场景与点位上限', () => {
    expect(IotTwinApi.TWIN_LIMITS.maxSceneDepth).toBe(4);
    expect(IotTwinApi.TWIN_LIMITS.maxPointsPerScene).toBe(500);
    expect(IotTwinApi.TWIN_LIMITS.maxBindingsPerPoint).toBe(12);
  });

  it('文本长度上限', () => {
    expect(IotTwinApi.TWIN_LIMITS.maxSceneCodeLength).toBe(64);
    expect(IotTwinApi.TWIN_LIMITS.maxSceneNameLength).toBe(128);
    expect(IotTwinApi.TWIN_LIMITS.maxSceneRemarkLength).toBe(500);
    expect(IotTwinApi.TWIN_LIMITS.maxNodeNameLength).toBe(255);
    expect(IotTwinApi.TWIN_LIMITS.maxIconLength).toBe(64);
    expect(IotTwinApi.TWIN_LIMITS.maxPointRemarkLength).toBe(500);
    expect(IotTwinApi.TWIN_LIMITS.maxUnitLength).toBe(32);
    expect(IotTwinApi.TWIN_LIMITS.maxAssetNameLength).toBe(128);
    expect(IotTwinApi.TWIN_LIMITS.maxExternalUrlLength).toBe(1024);
  });

  it('数值与体积上限', () => {
    expect(IotTwinApi.TWIN_LIMITS.maxDecimals).toBe(6);
    expect(IotTwinApi.TWIN_LIMITS.maxViewJsonBytes).toBe(16 * 1024);
    expect(IotTwinApi.TWIN_LIMITS.maxModelBytes).toBe(64 * 1024 * 1024);
  });

  it('场景编码正则与后端 TwinLimits.SCENE_CODE_PATTERN 等价', () => {
    expect(IotTwinApi.TWIN_LIMITS.sceneCodePattern.test('B1-F3')).toBe(true);
    expect(IotTwinApi.TWIN_LIMITS.sceneCodePattern.test('b1_f3')).toBe(true);
    expect(IotTwinApi.TWIN_LIMITS.sceneCodePattern.test('b1 f3')).toBe(false);
    expect(IotTwinApi.TWIN_LIMITS.sceneCodePattern.test('')).toBe(false);
    expect(IotTwinApi.TWIN_LIMITS.sceneCodePattern.test('a'.repeat(65))).toBe(
      false,
    );
  });

  it('允许的模型扩展名', () => {
    expect([...IotTwinApi.TWIN_LIMITS.allowedModelExtensions]).toEqual([
      '.glb',
      '.gltf',
    ]);
  });
});
```

---

## 28. 实施顺序与验证

### 28.1 实施顺序（与后端 P1–P6 对齐）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **F1 依赖与 API 层** | catalog + package.json + `api/iot/twin.ts` + `api/iot/index.ts` | `pnpm -F @vben/web-antd typecheck` 通过 |
| **F2 引擎层（无 UI）** | `three/twin/{types,disposal,model-loader,scene-manager,camera-rig,anchor-layer,picker,runtime-store}.ts` + 4 个引擎单测 | `pnpm vitest run src/three/twin` 全绿 |
| **F3 页面骨架** | `views/iot/twin/{index.vue,data.ts,composables/*}` + 5 个模块组件 | typecheck 通过；页面能打开（无数据空态） |
| **F4 i18n 与深链** | zh-CN/en-US 两份 `iot.twin` 段 + `twin-deeplink.test.ts` + `twin-validate.test.ts` | `pnpm vitest run src/views/iot/twin` 全绿 |
| **F5 手工验收** | 按设计文档 §9.4 的 8 项逐条实测 | 逐条留证（截图/内存数） |

### 28.2 验证命令

```bash
# ① 类型检查（唯一必跑的前端门禁；本机不跑全量 build，见设计文档 §1.3）
pnpm -F @vben/web-antd typecheck

# ② 引擎层单测（不依赖 WebGL：disposal / picker 的纯数学部分可跑）
pnpm vitest run src/three/twin

# ③ 页面层单测
pnpm vitest run src/views/iot/twin

# ④ lint（仓库既有门禁）
pnpm -F @vben/web-antd exec oxlint src/views/iot/twin src/three/twin src/api/iot/twin.ts
```

**必须在汇报里附上的证据**：①②③ 的命令与输出摘要。
若 ① 因本机内存失败，如实说明并给出被跳过的范围（不得声称通过）。

### 28.3 部署注意（three 的解码器资源）

`three` 的 Draco / KTX2 解码器是**独立 wasm/JS 文件**，不打进 bundle：

| 资源 | 默认路径 | 来源 |
|---|---|---|
| Draco 解码器 | `/draco/draco_decoder.wasm` 等 | `three/examples/jsm/libs/draco/gltf/` |
| KTX2 转码器 | `/basis/basis_transcoder.js` + `.wasm` | `three/examples/jsm/libs/basis/` |

必须把它们放到前端静态目录（`apps/web-antd/public/draco/`、`public/basis/`），
否则 `GLTFLoader` 遇到 Draco/KTX2 压缩模型时会 404 并报「加载失败」。

**做法**：在 `apps/web-antd/vite.config.ts` 里加静态复制插件（或直接把两个目录
从 `node_modules/three/examples/jsm/libs/` 复制到 `apps/web-antd/public/` 并入库）。
推荐**复制入库**：解码器文件体积小（Draco wasm ~200KB、KTX2 ~100KB），入库后
不依赖构建时读 node_modules，且部署（nginx 静态托管）天然可用。

---

## 29. 前端未做 / 待决（如实登记）

1. **无移动端适配**：页面按 1280px+ 桌面设计；窄屏时画布会显示「请使用大屏或桌面浏览器」。
2. **无管线/连线渲染**：`iot_twin_link` 未做（后端也没这张表）。
3. **无历史回放**：本期只有实时；回放需要接 `/iot/devices/{id}/series` + 时间轴。
4. **无锚点自动节点名匹配**：后端 `nodeNames` 快照与点位的 `nodeName` 已具备，
   但「按名字自动把设备批量挂到节点上」的一键操作未做（属配置效率优化，不是功能缺口）。
5. **飞行下钻的过渡动画是简化版**：当前 `fly` 模式仍走「加载新场景 + 淡入淡出」，
   父子同屏的相机飞行（真正的 `fly`）未实现——原因：两场景同时驻留的显存风险在
   20MB+ 模型上不可控，且收益（观感）小于风险。`canFly` 现只用于**判断是否提示用户**
   「因模型过大/低显存而不做飞行过渡」。若将来要做真飞行，需先解决「双场景驻留时的
   显存预算与降级策略」。
6. **无截图/导出**：`renderer.domElement.toDataURL()` 可直接支持，但未做（非必需）。
7. **`three-mesh-bvh` 的 BVH 只在配置态构建**：运行态不建（用户不拾取模型表面）。
   若将来运行态也要点击模型表面（如「点墙看该区域设备」），需要重新评估构建成本。

---

## 30. 与后端契约的最终对照表

| 前端 | 后端 | 一致性要求 |
|---|---|---|
| `TWIN_LIMITS.maxSceneDepth = 4` | `TwinLimits.MAX_SCENE_DEPTH = 4` | `twin-validate.test.ts` 断言 |
| `TWIN_LIMITS.maxBindingsPerPoint = 12` | `TwinLimits.MAX_BINDINGS_PER_POINT = 12` | 同上 |
| `TWIN_LIMITS.maxModelBytes = 64MB` | `TwinLimits.MAX_MODEL_BYTES = 64MB` | 同上；另需与 nginx `64m`、`ypbin.storage.max-file-size`、Spring `multipart.max-file-size` 四处同值 |
| `sceneCodePattern` | `TwinLimits.SCENE_CODE_PATTERN` | 正则字符串逐字一致 |
| `SceneResp.parentId: string`（根为 `"0"`） | `parentId` Long（根为 0） | 前端**不**把 `"0"` 当 null |
| `SceneResp.anchorX: string` | `BigDecimal`（全局序列化为字符串） | 展示前 `Number()`，落库前转 number |
| `BindingResp.stale: boolean` | `TwinBindingResp.stale` | 后端保证非 null |
| `RuntimePointResp.values: Record<string, RuntimeValueResp>` | `Map<String, TwinRuntimeValueResp>` | 后端保证非 null（空 Map） |
| `ModelAssetResp.contentUrl` | 后端按 assetType 给出 | 外链模型直接用该字段 |
| `PointSaveReq.bindings` 必填 | `IotTwinPointReq.bindings` `@NotNull` | 前端传 `[]` 而不是 null |
| 权限码 `iot:twin:list/create/update/delete` | 同 | 菜单 SQL 与后端注解一致 |
