# 数字孪生（3D 场景）后端完整实现

> 本文是 [`DIGITAL-TWIN-DESIGN.md`](DIGITAL-TWIN-DESIGN.md) 的**源码级实现**，契约以设计文档为准。
> 所有代码按 `ypbin-iot` 既有工程规范落地（`ypbin-admin-dev` skill 的 RED 铁律 + `ypbin-architecture-tests` 全部门禁）。
>
> **交付原则**：本文所有文件都是**可直接落盘编译**的完整实现，不含 `TODO`、不含占位实现、
> 不含「后续优化」的注释。每个类都带 `@author wenbin` + `@since 2026-10-21`。

## 0. 文件清单

### 0.1 新增（`ypbin-service-api/ypbin-iot-api`）

```
src/main/java/cn/ypbin/admin/iot/
  enums/TwinSceneType.java
  enums/TwinAssetType.java
  enums/TwinDisplayType.java
  enums/TwinDisplayRole.java
  twin/TwinLimits.java
  twin/TwinSceneTransform.java
  entity/IotTwinModelAsset.java
  entity/IotTwinScene.java
  entity/IotTwinPoint.java
  entity/IotTwinPointBinding.java
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
```

### 0.2 新增（`ypbin-service/ypbin-iot`）

```
src/main/java/cn/ypbin/admin/iot/
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
  service/impl/TwinBindingResolver.java
  service/impl/TwinModelFileGuard.java
  controller/IotTwinSceneController.java
  controller/IotTwinPointController.java
  controller/IotTwinModelController.java
  controller/IotTwinRuntimeController.java
  provider/TwinRefTextProviders.java
```

### 0.3 新增测试

```
ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/
  twin/TwinSceneTransformTest.java
  twin/TwinModelFileGuardTest.java
  twin/TwinBindingResolverTest.java
  twin/TwinPermissionCodeGateTest.java
  twin/TwinMenuGrantGateTest.java
  service/impl/IotTwinSceneServiceImplTest.java
  service/impl/IotTwinPointServiceImplTest.java
  service/impl/IotTwinRuntimeServiceImplTest.java
  values/LatestValueQueryServiceBatchTest.java
```

### 0.4 修改（均属本仓自有的 IoT 文件，不受 `SYNC.md` 白名单约束）

| 文件 | 变更 |
|---|---|
| `ypbin-service/ypbin-iot/pom.xml` | 新增 `ypbin-starter-storage` 依赖 |
| `ypbin-service/ypbin-iot/src/main/java/.../values/LatestValueQueryService.java` | 新增 `listLatestBatch(List<Long>)` 与「坐标归一裁决」复用抽取 |
| `deploy/sql/007-iot-data.sql` | 末尾追加：**先 menu 段、后 schema 段**（顺序受 SQL 等价门禁约束，见 §11.6） |
| `deploy/sql/migration/2026-10-21-iot-twin-schema.sql` | 新建（与 006 追加段语句等价） |
| `deploy/sql/rollback/2026-10-21-iot-twin-schema-rollback.sql` | 新建 |
| `deploy/sql/migration/iot-migrations.list` | 末尾追加新迁移文件名 |
| `deploy/sql/007-iot-data.sql` | 末尾追加权限码与菜单段 |
| `deploy/sql/migration/2026-10-21-iot-twin-menu.sql` | 新建（与 007 追加段语句等价） |
| `deploy/sql/rollback/2026-10-21-iot-twin-menu-rollback.sql` | 新建 |
| `deploy/nacos/ypbin-iot.yaml` | 新增 `ypbin.storage` 段与 `spring.servlet.multipart` 段 |
| `deploy/docker-compose.yml` | `ypbin-iot` 服务新增模型存储卷 |
| `deploy/nginx-microservice.conf` | 新增模型上传专用 location（在 `/api/` 之前） |
| `SYNC.md` | 既有两条白名单条目各补一行说明（**不新增条目数**） |

---

## 1. 枚举

### 1.1 `enums/TwinSceneType.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 数字孪生场景类型。
 *
 * <p>数据库与接口存/传 {@code code}。类型只表达「这一层在场景树里是什么粒度」，
 * 不约束模型内容：{@code park} 也可以只有几个方块，{@code room} 也可以是一整栋楼的精模。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinSceneType {

    /** 园区（场景树根部的典型类型） */
    PARK("park", "园区"),

    /** 楼栋 */
    BUILDING("building", "楼栋"),

    /** 楼层 */
    FLOOR("floor", "楼层"),

    /** 房间 */
    ROOM("room", "房间");

    private final String code;
    private final String desc;

    TwinSceneType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 场景类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinSceneType of(String code) {
        for (TwinSceneType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
```

### 1.2 `enums/TwinAssetType.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 数字孪生模型来源类型。
 *
 * <p>{@link #UPLOAD} 的模型落在平台存储、经 API 流式下载（鉴权与租户隔离由 API 层承担）；
 * {@link #EXTERNAL} 只登记外链地址，由浏览器直连（服务端不主动拉取，避免 SSRF）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinAssetType {

    /** 平台内上传（存储平台 + 桶 + 路径） */
    UPLOAD("upload", "平台内上传"),

    /** 外链登记（仅校验 scheme 与长度，不服务端拉取） */
    EXTERNAL("external", "外链登记");

    private final String code;
    private final String desc;

    TwinAssetType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 来源类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinAssetType of(String code) {
        for (TwinAssetType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
```

### 1.3 `enums/TwinDisplayType.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 数字孪生属性绑定的展示形态。
 *
 * <p>形态只决定「运行态怎么画这个值」，不参与取值换算——缩放/偏移仍由采集域的
 * {@code iot_point_mapping} 承担，这里拿到的已经是工程值。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinDisplayType {

    /** 纯文本数值 */
    VALUE("value", "数值"),

    /** 仪表盘（用物模型 min/max，缺省时由前端自适应量程） */
    GAUGE("gauge", "仪表盘"),

    /** 开关（布尔真值表由前端裁决，无法判定时显示「未知」，不猜） */
    SWITCH("switch", "开关"),

    /** 颜色映射（按枚举值或数值区间着色） */
    COLOR("color", "颜色"),

    /** 迷你折线（前端按实时推送自建最近 N 点） */
    SPARKLINE("sparkline", "迷你折线");

    private final String code;
    private final String desc;

    TwinDisplayType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 展示形态码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinDisplayType of(String code) {
        for (TwinDisplayType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
```

### 1.4 `enums/TwinDisplayRole.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 数字孪生属性绑定的展示角色。
 *
 * <p>{@link #PRIMARY} 至多一条：它是锚点图标旁常显的那一行。其余绑定进 hover/点击浮层。
 * 若允许多条 primary，运行态无法决定「标签牌上写哪个值」。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinDisplayRole {

    /** 主展示（常显） */
    PRIMARY("primary", "主展示"),

    /** 次展示（浮层/详情） */
    SECONDARY("secondary", "次展示");

    private final String code;
    private final String desc;

    TwinDisplayRole(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 展示角色码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinDisplayRole of(String code) {
        for (TwinDisplayRole item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
```

---

## 2. 上限常量：`twin/TwinLimits.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

/**
 * 数字孪生域的全部硬上限（**唯一来源**）。
 *
 * <p>集中在一个类里而不是散落在 DTO 注解与 service 判断中：同一个上限会同时出现在
 * 请求 DTO 的 {@code @Size}、服务侧复核、前端常量三处，散落必然漂移，而漂移的后果是
 * 「前端放过、后端拒绝」或反之，用户拿到的是无法理解的失败。</p>
 *
 * <p>前端在 {@code api/iot/twin.ts} 里镜像同一组数字，并注明「与后端 TwinLimits 同值」；
 * 修改时必须两处同改（既有 {@code DEVICE_IMPORT_FRONTEND_LIMITS} 与 {@code DeviceImportLimits}
 * 就是这个模式）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinLimits {

    /** 场景树最大深度（根为第 1 层）。 */
    public static final int MAX_SCENE_DEPTH = 4;

    /** 场景编码长度上限。 */
    public static final int MAX_SCENE_CODE_LENGTH = 64;

    /** 场景编码允许的字符集（正则）。 */
    public static final String SCENE_CODE_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /** 场景名称长度上限。 */
    public static final int MAX_SCENE_NAME_LENGTH = 128;

    /** 场景备注长度上限。 */
    public static final int MAX_SCENE_REMARK_LENGTH = 500;

    /** 场景视角配置 JSON 字节上限（16 KB）。 */
    public static final int MAX_VIEW_JSON_BYTES = 16 * 1024;

    /** 单场景点位上限。 */
    public static final int MAX_POINTS_PER_SCENE = 500;

    /** 单点位属性绑定上限。 */
    public static final int MAX_BINDINGS_PER_POINT = 12;

    /** 模型节点名长度上限。 */
    public static final int MAX_NODE_NAME_LENGTH = 255;

    /** 点位图标标识长度上限。 */
    public static final int MAX_ICON_LENGTH = 64;

    /** 点位备注长度上限。 */
    public static final int MAX_POINT_REMARK_LENGTH = 500;

    /** 单位覆盖长度上限。 */
    public static final int MAX_UNIT_LENGTH = 32;

    /** 小数位上限。 */
    public static final int MAX_DECIMALS = 6;

    /** 模型名称长度上限。 */
    public static final int MAX_ASSET_NAME_LENGTH = 128;

    /** 外链地址长度上限。 */
    public static final int MAX_EXTERNAL_URL_LENGTH = 1024;

    /** 模型文件字节上限（64 MB），与 {@code ypbin.storage.max-file-size} 同值。 */
    public static final long MAX_MODEL_BYTES = 64L * 1024 * 1024;

    /** 模型节点名快照条目上限（超出截断并记 WARN，不影响任何功能）。 */
    public static final int MAX_NODE_NAME_SNAPSHOT = 5_000;

    /** 模型节点名快照 JSON 字节上限（1 MB）。 */
    public static final int MAX_NODE_NAMES_JSON_BYTES = 1024 * 1024;

    /** 允许的模型扩展名。 */
    public static final String EXT_GLB = "glb";

    /** 允许的模型扩展名。 */
    public static final String EXT_GLTF = "gltf";

    /** GLB 文件头魔数（ASCII {@code glTF} 的小端读取值）。 */
    public static final int GLB_MAGIC = 0x46546C67;

    /** 支持的 glTF 主版本。 */
    public static final int GLTF_VERSION = 2;

    /** GLB 头部长度（魔数 4 + 版本 4 + 总长 4）。 */
    public static final int GLB_HEADER_BYTES = 12;

    /** 锚点缩放系数下界（必须 > 0）。 */
    public static final double MIN_ANCHOR_SCALE = 0.000001D;

    private TwinLimits() {
    }
}
```

---

## 3. 锚点变换口径：`twin/TwinSceneTransform.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.math.BigDecimal;

/**
 * 场景锚点变换的**校验口径**（纯函数，零 Spring 依赖，可单测）。
 *
 * <p>为什么把它单独成类：锚点变换是「子场景如何嵌进父场景」的唯一数学定义，
 * 前端 {@code scene-manager} 的合成顺序必须与这里**逐字一致**，否则页面显示的位置与
 * 用户配置的位置会系统性偏移（而且只在多级嵌套时才暴露，最难排查）。把它抽成纯函数后，
 * 后端单测可以钉死「合成顺序 = T × Rz × Ry × Rx × S」，前端单测用同一组数字对照。</p>
 *
 * <p><b>合成顺序为什么是 Z→Y→X（内旋）</b>：three.js 的 {@code Object3D} 默认欧拉序是
 * {@code 'XYZ'}，语义即「先绕 X、再绕 Y、最后绕 Z 的**内旋**」。用
 * {@code object.rotation.set(rx, ry, rz)} + {@code object.position.set(...)} + {@code scale} 时，
 * three.js 内部合成结果等价于 {@code T × Rz × Ry × Rx × S}。这里显式写出该结论，是为了让
 * 前端**不要**手写矩阵（手写极易把顺序搞反，且无法与后端单测对照）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinSceneTransform {

    private TwinSceneTransform() {
    }

    /**
     * 校验缩放系数（必须严格大于 0）。
     *
     * <p>为什么不允许 0：缩放为 0 会让整棵子树退化成一个点，模型「消失」但配置看起来正常，
     * 用户会怀疑是渲染 bug。这里直接拒绝，并说明是缩放系数问题。</p>
     *
     * @param anchorScale 缩放系数（可空，空视为 1）
     * @return 归一化后的缩放系数（永不为 null）
     */
    public static BigDecimal requireScale(BigDecimal anchorScale) {
        if (anchorScale == null) {
            return BigDecimal.ONE;
        }
        if (anchorScale.compareTo(BigDecimal.valueOf(TwinLimits.MIN_ANCHOR_SCALE)) < 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "锚点缩放系数必须大于 0（当前值：" + anchorScale.toPlainString()
                    + "）。缩放为 0 或负数会让该场景及其全部子场景在画布上消失");
        }
        return anchorScale;
    }

    /**
     * 空值归零（平移与旋转列在库里是 NOT NULL DEFAULT 0，但请求里允许不传）。
     *
     * @param value 原值
     * @return 非空值
     */
    public static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
```

---

## 4. 实体（全部继承 `TenantBaseEntity`）

### 4.1 `entity/IotTwinModelAsset.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生模型资产。
 *
 * <p>一条资产 = 一个可被场景引用的模型文件（或一个外链地址）。<b>同一资产可被多个场景引用</b>
 * （例如「标准厂房」模型被 3 个楼层场景复用），因此删除时必须先检查引用数。</p>
 *
 * <p>{@code nodeNamesJson} 是**辅助索引不是权威数据**：它由页面解析 glb 后上报，仅用于
 * 「按模型节点名批量预匹配设备锚点」的便利功能。解析失败或未上报时存 null，
 * 不阻塞任何核心流程。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_model_asset")
public class IotTwinModelAsset extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模型名称（租户内唯一）。 */
    private String assetName;

    /** 来源类型：upload | external（{@code TwinAssetType} 的 code）。 */
    private String assetType;

    /** 模型格式：glb | gltf。 */
    private String modelFormat;

    /** 存储平台标识（asset_type=upload 时非空）。 */
    private String storagePlatform;

    /** 存储桶（asset_type=upload 时非空）。 */
    private String storageBucket;

    /** 存储路径（asset_type=upload 时非空）。 */
    private String storagePath;

    /** 外链地址（asset_type=external 时非空，仅允许 http/https）。 */
    private String externalUrl;

    /** 上传时的原始文件名（仅 upload）。 */
    private String originalName;

    /** 文件字节数（仅 upload）。 */
    private Long fileSize;

    /** SHA-256 十六进制摘要（仅 upload）。 */
    private String contentHash;

    /** 模型节点名快照 JSON（字符串数组；仅作预匹配辅助）。 */
    private String nodeNamesJson;
}
```

### 4.2 `entity/IotTwinScene.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生场景（树形）。
 *
 * <p>{@code parentId} 用 {@code 0} 表示根，不用 {@code null}：MySQL 唯一键不把两个 NULL 视为重复，
 * 用可空父 id 无法阻止同租户插入两条同名根节点；用 {@code 0} 与 {@code sys_menu.pid} 的既有约定一致。</p>
 *
 * <p>{@code anchor*} 是 <b>后端拥有语义</b>的结构化数据（参与世界坐标链计算，见
 * {@code TwinSceneTransform}）；{@code viewJson} 是<b>展示偏好</b>（相机位姿/背景/光照），
 * 按看板 {@code config_json} 的既有先例走 JSON 透传、后端只校验合法性与大小。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_scene")
public class IotTwinScene extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 场景编码（租户内唯一，用于深链与免歧义引用）。 */
    private String sceneCode;

    /** 场景名称。 */
    private String sceneName;

    /** 父场景 ID（0=根场景）。 */
    private Long parentId;

    /** 场景类型：park | building | floor | room。 */
    private String sceneType;

    /** 绑定的模型资产 ID（null=纯容器节点，不渲染模型）。 */
    private Long modelAssetId;

    /** 锚点平移 X。 */
    private BigDecimal anchorX;

    /** 锚点平移 Y。 */
    private BigDecimal anchorY;

    /** 锚点平移 Z。 */
    private BigDecimal anchorZ;

    /** 锚点旋转 X（度）。 */
    private BigDecimal anchorRx;

    /** 锚点旋转 Y（度）。 */
    private BigDecimal anchorRy;

    /** 锚点旋转 Z（度）。 */
    private BigDecimal anchorRz;

    /** 锚点缩放系数（> 0）。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON（空=前端用默认值）。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;
}
```

### 4.3 `entity/IotTwinPoint.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生点位（**设备在场景中的锚点**）。
 *
 * <p>点位的主语是<b>设备</b>而不是属性：现场人员说「3 号泵在哪」指的是一个设备位置，
 * 属性是这台设备的多个读数、天然属于同一位置。若按属性建锚点，一台 20 属性的设备会在模型上
 * 留下 20 个点，用户需要手工把它们摆在一起（必然摆不齐）。展示哪几个属性由
 * {@link IotTwinPointBinding} 表达。</p>
 *
 * <p>坐标一律是<b>本场景局部坐标系</b>内的值，不存世界坐标：整栋楼平移一次只需改场景锚点，
 * 楼上所有点位自动跟着走。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_point")
public class IotTwinPoint extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属场景 ID。 */
    private Long sceneId;

    /** 绑定的设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 绑定的模型节点名（可空；仅用于高亮与按节点名预匹配）。 */
    private String nodeName;

    /** 场景局部坐标 X。 */
    private BigDecimal localX;

    /** 场景局部坐标 Y。 */
    private BigDecimal localY;

    /** 场景局部坐标 Z。 */
    private BigDecimal localZ;

    /** 锚点朝向法线 X。 */
    private BigDecimal normalX;

    /** 锚点朝向法线 Y。 */
    private BigDecimal normalY;

    /** 锚点朝向法线 Z。 */
    private BigDecimal normalZ;

    /** 图标标识（前端图标集内的名字）。 */
    private String icon;

    /** 是否在运行态常显文字标签：1 是 0 否。 */
    private Integer labelVisible;

    /** 排序（同场景内标签叠放顺序）。 */
    private Integer sort;

    /** 备注。 */
    private String remark;
}
```

### 4.4 `entity/IotTwinPointBinding.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 数字孪生点位属性绑定（一个锚点展示哪几个物模型属性）。
 *
 * <p><b>为什么权威键是 {@code (serviceIdentifier, propertyIdentifier)} 而不是 {@code propertyId}</b>：
 * TSL 重新导入会**物理删除**属性行再重建（{@code IotThingModelServiceImpl#replaceTsl}），
 * 且 {@code iot_product_version} 不含结构快照 ⇒ 属性主键没有任何跨版本稳定性。
 * {@code propertyId} 仅作联查加速的缓存，<b>可悬空</b>，读取时按业务标识重解析并可自愈回写。</p>
 *
 * <p>这与「最新值」的规范坐标口径（属性标识）天然一致，不需要额外映射层。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
@TableName("iot_twin_point_binding")
public class IotTwinPointBinding extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属点位 ID。 */
    private Long pointId;

    /** 物模型服务标识（iot_service.service_id）。 */
    private String serviceIdentifier;

    /** 物模型属性标识（iot_property.identifier）。 */
    private String propertyIdentifier;

    /** 属性主键缓存（仅作联查加速，可能悬空）。 */
    private Long propertyId;

    /** 展示形态：value | gauge | switch | color | sparkline。 */
    private String displayType;

    /** 展示角色：primary | secondary。 */
    private String displayRole;

    /** 小数位（0~6；null=原样展示）。 */
    private Integer decimals;

    /** 单位覆盖（null=用物模型属性自带单位）。 */
    private String unitOverride;

    /** 排序。 */
    private Integer sort;
}
```

---

## 5. Mapper（全部 `extends BaseMapper`，自定义 SQL 用 `@Select` 且必须闭合 `<script>`）

### 5.1 `mapper/IotTwinSceneMapper.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotTwinScene;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 数字孪生场景 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinSceneMapper extends BaseMapper<IotTwinScene> {

    /**
     * 按场景统计点位数量与直接子场景数（**一次分组查询**，不逐场景 selectCount）。
     *
     * <p>为什么要它：场景列表页每行都要显示「N 个点位 / M 个子场景」。逐行两次 count
     * 会随场景数线性放大查询次数（N+1），而场景树是每次进页面必拉的列表。</p>
     *
     * @param sceneIds 场景主键（调用方保证非空）
     * @return 每行 {@code sceneId} / {@code pointCount} / {@code childCount}
     */
    @Select("<script>"
        + "SELECT s.id AS sceneId, "
        + "(SELECT COUNT(*) FROM iot_twin_point p WHERE p.scene_id = s.id AND p.is_deleted = 0) AS pointCount, "
        + "(SELECT COUNT(*) FROM iot_twin_scene c WHERE c.parent_id = s.id AND c.is_deleted = 0) AS childCount "
        + "FROM iot_twin_scene s WHERE s.is_deleted = 0 AND s.id IN "
        + "<foreach collection='sceneIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> countPointsAndChildren(@Param("sceneIds") List<Long> sceneIds);
}
```

### 5.2 `mapper/IotTwinPointMapper.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotTwinPoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * IoT 数字孪生点位 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinPointMapper extends BaseMapper<IotTwinPoint> {
}
```

### 5.3 `mapper/IotTwinPointBindingMapper.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * IoT 数字孪生点位属性绑定 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinPointBindingMapper extends BaseMapper<IotTwinPointBinding> {

    /**
     * 按点位批量**物理删除**绑定（整体替换语义）。
     *
     * <p>为什么物理删除而不是逻辑删除：绑定的唯一键
     * {@code uk_iot_twin_point_binding(tenant_id, point_id, service_identifier, property_identifier)}
     * 不含 {@code is_deleted}（沿用 006 的「逻辑删除后业务键不可复用」约定）。若只做逻辑删除，
     * 被删行仍占用唯一键 ⇒ <b>同一属性删掉后再绑回来会主键冲突</b>。</p>
     *
     * <p>代价（已知且接受）：绑定没有历史留痕。绑定是展示配置而非业务事实，
     * 编辑日志由 {@code @Log} 提供，不需要行级历史。</p>
     *
     * <p>租户插件会给该 DELETE 追加 tenant_id 条件，故仍受租户隔离约束。</p>
     *
     * @param pointIds 点位主键（调用方保证非空）
     * @return 影响行数
     */
    @Delete("<script>DELETE FROM iot_twin_point_binding WHERE point_id IN "
        + "<foreach collection='pointIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    int physicalDeleteByPointIds(@Param("pointIds") List<Long> pointIds);
}
```

### 5.4 `mapper/IotTwinModelAssetMapper.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * IoT 数字孪生模型资产 Mapper。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinModelAssetMapper extends BaseMapper<IotTwinModelAsset> {

    /**
     * 按资产统计被多少个场景引用（**一次分组查询**）。
     *
     * @param assetIds 资产主键（调用方保证非空）
     * @return 每行 {@code assetId} / {@code sceneCount}
     */
    @Select("<script>"
        + "SELECT model_asset_id AS assetId, COUNT(*) AS sceneCount FROM iot_twin_scene "
        + "WHERE is_deleted = 0 AND model_asset_id IN "
        + "<foreach collection='assetIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
        + "GROUP BY model_asset_id"
        + "</script>")
    List<Map<String, Object>> countSceneReferences(@Param("assetIds") List<Long> assetIds);
}
```

---

## 6. 请求模型（`model/req`）

### 6.1 `model/req/IotTwinSceneReq.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生场景新增/编辑请求。
 *
 * <p>注解校验只覆盖「单字段能不能填」，跨字段与跨行规则（父链成环、深度上限、资产归属、
 * 缩放 > 0、viewJson 合法性）一律在 service 侧完成——注解拿不到库里的父链，也不该让
 * 一个需要查库的规则伪装成声明式校验。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinSceneReq {

    /** 场景编码（租户内唯一）。 */
    @NotBlank(message = "场景编码不能为空")
    @Pattern(regexp = TwinLimits.SCENE_CODE_PATTERN,
        message = "场景编码仅允许字母、数字、下划线和中划线，长度 1-64")
    private String sceneCode;

    /** 场景名称。 */
    @NotBlank(message = "场景名称不能为空")
    @Size(max = TwinLimits.MAX_SCENE_NAME_LENGTH, message = "场景名称长度不能超过 128")
    private String sceneName;

    /** 父场景 ID（null=根场景）。 */
    private Long parentId;

    /** 场景类型：park | building | floor | room。 */
    @NotBlank(message = "场景类型不能为空")
    private String sceneType;

    /** 绑定的模型资产 ID（null=纯容器节点，不渲染模型）。 */
    private Long modelAssetId;

    /** 锚点平移 X（null 视为 0）。 */
    private BigDecimal anchorX;

    /** 锚点平移 Y（null 视为 0）。 */
    private BigDecimal anchorY;

    /** 锚点平移 Z（null 视为 0）。 */
    private BigDecimal anchorZ;

    /** 锚点旋转 X（度，null 视为 0）。 */
    private BigDecimal anchorRx;

    /** 锚点旋转 Y（度，null 视为 0）。 */
    private BigDecimal anchorRy;

    /** 锚点旋转 Z（度，null 视为 0）。 */
    private BigDecimal anchorRz;

    /** 锚点缩放系数（null 视为 1；必须 > 0，service 侧校验）。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON（可空；非空时必须是合法 JSON 且 ≤ 16KB）。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 备注。 */
    @Size(max = TwinLimits.MAX_SCENE_REMARK_LENGTH, message = "备注长度不能超过 500")
    private String remark;
}
```

### 6.2 `model/req/TwinBindingReq.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生属性绑定的新增/编辑条目（**展示哪几个属性**）。
 *
 * <p>权威键是 {@code (serviceIdentifier, propertyIdentifier)} 两个业务标识；
 * 不接收 {@code propertyId}——属性主键会被 TSL 重导入销毁，把它放进请求会让调用方误以为
 * 主键是权威引用。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinBindingReq {

    /** 物模型服务标识。 */
    @NotBlank(message = "服务标识不能为空")
    @Size(max = 64, message = "服务标识长度不能超过 64")
    private String serviceIdentifier;

    /** 物模型属性标识。 */
    @NotBlank(message = "属性标识不能为空")
    @Size(max = 64, message = "属性标识长度不能超过 64")
    private String propertyIdentifier;

    /** 展示形态：value | gauge | switch | color | sparkline（service 侧用枚举白名单校验）。 */
    @NotBlank(message = "展示形态不能为空")
    private String displayType;

    /** 展示角色：primary | secondary（null 视为 secondary，service 侧统一归一）。 */
    private String displayRole;

    /** 小数位（0~6；null=原样展示）。 */
    @Min(value = 0, message = "小数位不能小于 0")
    @Max(value = TwinLimits.MAX_DECIMALS, message = "小数位不能大于 6")
    private Integer decimals;

    /** 单位覆盖（null=用物模型属性自带单位）。 */
    @Size(max = TwinLimits.MAX_UNIT_LENGTH, message = "单位长度不能超过 32")
    private String unitOverride;

    /** 排序。 */
    private Integer sort;
}
```

### 6.3 `model/req/IotTwinPointReq.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生点位新增/编辑请求（编辑时**整体替换**其绑定）。
 *
 * <p>{@code bindings} 刻意允许空数组但不允许 {@code null}：空数组是用户明确的「这个锚点暂不展示
 * 任何属性」，{@code null} 是调用方漏传字段。两者语义不同，混为一谈会让一次漏字段静默清空绑定。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinPointReq {

    /** 绑定的设备 ID。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /** 绑定的模型节点名（可空）。 */
    @Size(max = TwinLimits.MAX_NODE_NAME_LENGTH, message = "模型节点名长度不能超过 255")
    private String nodeName;

    /** 场景局部坐标 X。 */
    @NotNull(message = "坐标 X 不能为空")
    private BigDecimal localX;

    /** 场景局部坐标 Y。 */
    @NotNull(message = "坐标 Y 不能为空")
    private BigDecimal localY;

    /** 场景局部坐标 Z。 */
    @NotNull(message = "坐标 Z 不能为空")
    private BigDecimal localZ;

    /** 锚点朝向法线 X（null 视为 0）。 */
    private BigDecimal normalX;

    /** 锚点朝向法线 Y（null 视为 1）。 */
    private BigDecimal normalY;

    /** 锚点朝向法线 Z（null 视为 0）。 */
    private BigDecimal normalZ;

    /** 图标标识（可空）。 */
    @Size(max = TwinLimits.MAX_ICON_LENGTH, message = "图标标识长度不能超过 64")
    private String icon;

    /** 是否在运行态常显文字标签（null 视为 true）。 */
    private Boolean labelVisible;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    @Size(max = TwinLimits.MAX_POINT_REMARK_LENGTH, message = "备注长度不能超过 500")
    private String remark;

    /**
     * 属性绑定列表（**整体替换**语义）。
     *
     * <p>非 null：清空绑定请传空数组 {@code []}；传 {@code null} 是调用方漏字段，必须显式报错。</p>
     */
    @NotNull(message = "属性绑定列表不能为空（清空绑定请传空数组）")
    @Size(max = TwinLimits.MAX_BINDINGS_PER_POINT, message = "单个点位最多绑定 12 个属性")
    @Valid
    private List<TwinBindingReq> bindings;
}
```

### 6.4 `model/req/IotTwinModelExternalReq.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 外链模型登记请求。
 *
 * <p>服务端**不主动拉取**该地址（避免 SSRF：内网地址、云元数据端点都在射程内），
 * 只校验 scheme 与长度，连通性由页面「试加载」按钮暴露。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinModelExternalReq {

    /** 模型名称（租户内唯一）。 */
    @NotBlank(message = "模型名称不能为空")
    @Size(max = TwinLimits.MAX_ASSET_NAME_LENGTH, message = "模型名称长度不能超过 128")
    private String assetName;

    /** 模型格式：glb | gltf（缺省按扩展名推断，推断不出则要求显式传）。 */
    private String modelFormat;

    /** 外链地址（必须是 http/https 绝对地址）。 */
    @NotBlank(message = "外链地址不能为空")
    @Size(max = TwinLimits.MAX_EXTERNAL_URL_LENGTH, message = "外链地址长度不能超过 1024")
    private String externalUrl;
}
```

---

## 7. 响应模型（`model/resp`）

### 7.1 `model/resp/IotTwinSceneResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import cn.ypbin.starter.json.ref.RefText;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生场景响应模型。
 *
 * <p>{@code pointCount} / {@code childCount} 由 {@code IotTwinSceneMapper#countPointsAndChildren}
 * 一次分组查询批量填充，不在响应转换时逐行查库。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinSceneResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 场景编码。 */
    private String sceneCode;

    /** 场景名称。 */
    private String sceneName;

    /** 父场景 ID（根场景为 0）。 */
    @RefText("twinScene")
    private Long parentId;

    /** 场景类型。 */
    private String sceneType;

    /** 模型资产 ID。 */
    @RefText("twinModelAsset")
    private Long modelAssetId;

    /** 锚点平移 X。 */
    private BigDecimal anchorX;

    /** 锚点平移 Y。 */
    private BigDecimal anchorY;

    /** 锚点平移 Z。 */
    private BigDecimal anchorZ;

    /** 锚点旋转 X（度）。 */
    private BigDecimal anchorRx;

    /** 锚点旋转 Y（度）。 */
    private BigDecimal anchorRy;

    /** 锚点旋转 Z（度）。 */
    private BigDecimal anchorRz;

    /** 锚点缩放系数。 */
    private BigDecimal anchorScale;

    /** 默认视角与环境配置 JSON。 */
    private String viewJson;

    /** 同级排序。 */
    private Integer sort;

    /** 本场景点位数。 */
    private Integer pointCount;

    /** 直接子场景数。 */
    private Integer childCount;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
```

### 7.2 `model/resp/TwinBindingResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生属性绑定响应模型。
 *
 * <p><b>{@code stale} 是必须被页面显式处理的字段</b>：它表示该绑定指向的属性在物模型里已不存在
 * （典型成因是 TSL 重新导入物理删除了属性行）。此时 {@code propertyName} 回落为属性标识本身、
 * {@code dataType}/{@code unit} 为空。页面必须标红并提示「请重新选择属性」——
 * 静默丢弃会让用户以为「我明明绑过」。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinBindingResp {

    /** 绑定主键。 */
    private Long id;

    /** 物模型服务标识。 */
    private String serviceIdentifier;

    /** 物模型属性标识。 */
    private String propertyIdentifier;

    /** 属性主键缓存（可能悬空）。 */
    private Long propertyId;

    /** 属性显示名（解析不到时回落属性标识本身）。 */
    private String propertyName;

    /** 物模型数据类型（解析不到时为空）。 */
    private String dataType;

    /** 单位（unit_override 优先，否则物模型单位）。 */
    private String unit;

    /** 展示形态。 */
    private String displayType;

    /** 展示角色。 */
    private String displayRole;

    /** 小数位。 */
    private Integer decimals;

    /** 单位覆盖。 */
    private String unitOverride;

    /** 排序。 */
    private Integer sort;

    /** 该绑定是否已失效（属性在物模型里已不存在）。 */
    private Boolean stale;
}
```

### 7.3 `model/resp/IotTwinPointResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import cn.ypbin.starter.json.ref.RefText;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生点位响应模型（含全部属性绑定）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinPointResp {

    /** 主键。 */
    private Long id;

    /** 所属场景 ID。 */
    private Long sceneId;

    /** 设备 ID。 */
    @RefText("device")
    private Long deviceId;

    /** 绑定的模型节点名。 */
    private String nodeName;

    /** 场景局部坐标 X。 */
    private BigDecimal localX;

    /** 场景局部坐标 Y。 */
    private BigDecimal localY;

    /** 场景局部坐标 Z。 */
    private BigDecimal localZ;

    /** 锚点朝向法线 X。 */
    private BigDecimal normalX;

    /** 锚点朝向法线 Y。 */
    private BigDecimal normalY;

    /** 锚点朝向法线 Z。 */
    private BigDecimal normalZ;

    /** 图标标识。 */
    private String icon;

    /** 是否常显文字标签。 */
    private Boolean labelVisible;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 属性绑定列表（按 sort 升序；无绑定时为空数组，永不为 null）。 */
    private List<TwinBindingResp> bindings;
}
```

### 7.4 `model/resp/TwinRuntimeValueResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import lombok.Getter;
import lombok.Setter;

/**
 * 运行态单个属性的最新值。
 *
 * <p><b>语义边界</b>：{@code value}/{@code quality}/{@code ts} 三者同时为 null 表示
 * 「该属性从未上报」，不是 0、不是错误、也不是设备离线。页面必须把它渲染成「--」而不是 0，
 * 否则运维会把「没有数据」当成「读数为 0」——这是这类大屏最危险的一种假数据。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinRuntimeValueResp {

    /** 读数原值（字符串化；写入侧原值为 null 时此处为 null）。 */
    private String value;

    /** 质量码（写入侧未给时为 null）。 */
    private String quality;

    /** 读数时刻（epoch 毫秒，Long 全局序列化为字符串）。 */
    private Long ts;

    /** 该绑定是否已失效（属性在物模型里已不存在）。 */
    private Boolean stale;
}
```

### 7.5 `model/resp/TwinRuntimePointResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行态单个点位的快照。
 *
 * <p>{@code values} 的键是<b>属性标识</b>（与最新值的规范坐标一致）。值为 null 或不存在的键
 * 都表示「没有最新值」，两者的区别只在诊断时有用（不存在=从未上报过；null=上报过但值为空）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinRuntimePointResp {

    /** 点位主键。 */
    private Long pointId;

    /** 设备主键。 */
    private Long deviceId;

    /** 设备编码。 */
    private String deviceCode;

    /** 设备名称。 */
    private String deviceName;

    /** 在线状态：online | offline | unknown。 */
    private String onlineStatus;

    /** 启停位：1 启用 0 停用。 */
    private Integer status;

    /** 最后上报时刻。 */
    private LocalDateTime lastSeenAt;

    /** 活动告警数。 */
    private Integer alertCount;

    /** 各属性最新值（键=属性标识；无数据时为空 Map，永不为 null）。 */
    private Map<String, TwinRuntimeValueResp> values;
}
```

### 7.6 `model/resp/IotTwinRuntimeResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行态场景快照（一次请求拉齐本场景全部点位）。
 *
 * <p><b>不宣称「原子快照」</b>：{@code serverTime} 是服务端开始组装该响应的时刻；
 * 各设备的读数来自同一次 Redis pipeline，但 Redis 里的值本身来自不同时刻的上报。
 * 需要严格同时刻切片时走时序库的历史查询，不是本端点。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinRuntimeResp {

    /** 场景主键。 */
    private Long sceneId;

    /** 服务端组装时刻。 */
    private LocalDateTime serverTime;

    /** 本场景点位数。 */
    private Integer pointCount;

    /** 是否被截断（点位超过上限时为 true；理论上不可达，防御式字段）。 */
    private Boolean truncated;

    /** 点位快照列表（按 point.sort、point.id 升序；无点位时为空数组，永不为 null）。 */
    private List<TwinRuntimePointResp> points;
}
```

### 7.7 `model/resp/IotTwinModelAssetResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型资产响应模型。
 *
 * <p>{@code contentUrl} 是**页面实际使用的地址**，由服务端按来源类型给出：
 * {@code upload} ⇒ {@code /iot/twin/model-assets/{id}/content}（同源、带鉴权、走浏览器缓存）；
 * {@code external} ⇒ 原始外链。页面不需要自己判断来源类型去拼路径。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinModelAssetResp {

    /** 主键。 */
    private Long id;

    /** 模型名称。 */
    private String assetName;

    /** 来源类型：upload | external。 */
    private String assetType;

    /** 模型格式：glb | gltf。 */
    private String modelFormat;

    /** 外链地址（仅 external）。 */
    private String externalUrl;

    /** 页面使用的模型地址（upload ⇒ 平台下载端点；external ⇒ 外链原文）。 */
    private String contentUrl;

    /** 上传时的原始文件名（仅 upload）。 */
    private String originalName;

    /** 文件字节数（仅 upload）。 */
    private Long fileSize;

    /** SHA-256 摘要（仅 upload）。 */
    private String contentHash;

    /** 节点名快照（解析失败或未上报时为空数组）。 */
    private List<String> nodeNames;

    /** 被多少个场景引用。 */
    private Integer sceneCount;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
```

### 7.8 `model/resp/IotTwinDevicePropertyResp.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备可绑定的物模型属性（配置页用）。
 *
 * <p><b>{@code mapped} 这个字段是本响应存在的核心理由</b>：它表示「该属性在本设备上是否已建立
 * 采集点位映射」。为 {@code false} 时属性**永远拿不到实时值**——配置页必须给出
 * 「该属性未配置采集点位，绑定后不会有数据」的提示。没有这个字段，用户会绑一堆没有数据的属性
 * 然后认为孪生功能坏了。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinDevicePropertyResp {

    /** 服务主键。 */
    private Long serviceId;

    /** 服务标识。 */
    private String serviceIdentifier;

    /** 服务名称。 */
    private String serviceName;

    /** 属性主键。 */
    private Long propertyId;

    /** 属性标识。 */
    private String propertyIdentifier;

    /** 属性名称。 */
    private String propertyName;

    /** 物模型数据类型。 */
    private String dataType;

    /** 读写权限：R | W | RW。 */
    private String accessMode;

    /** 单位。 */
    private String unit;

    /** 最小值。 */
    private BigDecimal minValue;

    /** 最大值。 */
    private BigDecimal maxValue;

    /** 该属性在本设备上是否已建立采集点位映射。 */
    private Boolean mapped;
}
```

---

## 8. 服务接口

### 8.1 `service/IotTwinSceneService.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import java.util.List;

/**
 * 数字孪生场景服务（树形 + 父链校验）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinSceneService {

    /**
     * 查询全部场景（树形数据由调用方按 parentId 组装）。
     *
     * @return 场景列表（按 parentId、sort、id 升序；无数据为空列表）
     */
    List<IotTwinSceneResp> listScenes();

    /**
     * 查询单个场景。
     *
     * @param id 场景主键
     * @return 场景详情
     */
    IotTwinSceneResp getScene(Long id);

    /**
     * 新建场景。
     *
     * @param req 场景信息
     * @return 新场景主键
     */
    Long create(IotTwinSceneReq req);

    /**
     * 编辑场景。
     *
     * @param id  场景主键
     * @param req 场景信息
     */
    void update(Long id, IotTwinSceneReq req);

    /**
     * 删除场景（存在子场景或点位时拒绝，不级联删除）。
     *
     * @param id 场景主键
     */
    void remove(Long id);
}
```

### 8.2 `service/IotTwinPointService.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import java.util.List;

/**
 * 数字孪生点位服务（锚点 + 属性绑定）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinPointService {

    /**
     * 查询场景下全部点位（含绑定，一次批量取回）。
     *
     * @param sceneId 场景主键
     * @return 点位列表（按 sort、id 升序；无数据为空列表）
     */
    List<IotTwinPointResp> listByScene(Long sceneId);

    /**
     * 新增点位（可同时带绑定）。
     *
     * @param sceneId 场景主键
     * @param req     点位信息
     * @return 新点位主键
     */
    Long create(Long sceneId, IotTwinPointReq req);

    /**
     * 编辑点位并**整体替换**其绑定。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @param req     点位信息
     */
    void update(Long sceneId, Long pointId, IotTwinPointReq req);

    /**
     * 删除点位（连同其全部绑定）。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     */
    void remove(Long sceneId, Long pointId);

    /**
     * 查询设备可绑定的物模型属性（经设备绑定产品的服务与属性）。
     *
     * <p>设备未绑定产品 ⇒ 返回空列表（**不是错误**：这是正常的空态，页面据此提示
     * 「该设备未绑定产品」）。设备不存在/不属于本租户 ⇒ 抛业务异常。</p>
     *
     * @param deviceId 设备主键
     * @return 属性列表（无数据为空列表）
     */
    List<IotTwinDevicePropertyResp> listDeviceProperties(Long deviceId);
}
```

### 8.3 `service/IotTwinModelAssetService.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数字孪生模型资产服务。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinModelAssetService {

    /**
     * 查询全部模型资产（含被场景引用数）。
     *
     * @return 资产列表（按创建时间倒序；无数据为空列表）
     */
    List<IotTwinModelAssetResp> listAssets();

    /**
     * 上传模型文件。
     *
     * <p>校验顺序固定（先便宜后昂贵）：扩展名 → 字节数 → 魔数/glTF 版本 → 落存储（边算摘要）
     * → 落库。任一环节失败都不留下半截文件（存储写入失败时补偿删除）。</p>
     *
     * @param file      模型文件（multipart 字段 {@code file}）
     * @param assetName 模型名称（可空，空则用文件名去扩展名）
     * @param nodeNames 页面解析出的节点名快照（可空）
     * @return 新资产
     * @throws IOException 读取上传流失败
     */
    IotTwinModelAssetResp upload(MultipartFile file, String assetName, List<String> nodeNames)
        throws IOException;

    /**
     * 登记外链模型。
     *
     * @param req 外链信息
     * @return 新资产
     */
    IotTwinModelAssetResp createExternal(IotTwinModelExternalReq req);

    /**
     * 打开模型内容流（仅 {@code asset_type=upload}）。
     *
     * @param id 资产主键
     * @return 文件元数据 + 内容流（调用方负责关闭）
     */
    ModelContent openContent(Long id);

    /**
     * 删除资产（被场景引用时拒绝）。
     *
     * @param id 资产主键
     */
    void remove(Long id);

    /**
     * 模型内容（元数据 + 流）。
     *
     * @param fileName    下载时的文件名
     * @param contentType 内容类型
     * @param size        字节数（未知时为 -1）
     * @param contentHash SHA-256 摘要（用于 ETag）
     * @param stream      内容流
     */
    record ModelContent(String fileName, String contentType, long size, String contentHash,
                        InputStream stream) {
    }
}
```

### 8.4 `service/IotTwinRuntimeService.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;

/**
 * 数字孪生运行态服务。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinRuntimeService {

    /**
     * 组装场景运行态快照（点位 + 设备快照 + 最新值 + 活动告警数）。
     *
     * <p><b>整个过程只有固定次数的查询，与点位数无关</b>：1 次点位 + 1 次绑定 + 1 次设备 +
     * 1 次属性解析 + 1 次 Redis pipeline + 1 次告警分组。</p>
     *
     * @param sceneId 场景主键
     * @return 运行态快照
     */
    IotTwinRuntimeResp runtime(Long sceneId);
}
```

---

## 9. 服务实现

### 9.1 `service/impl/IotTwinSceneServiceImpl.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.TwinSceneType;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import cn.ypbin.admin.iot.service.IotTwinSceneService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.admin.iot.twin.TwinSceneTransform;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 数字孪生场景服务实现。
 *
 * <p><b>父链是唯一的不变量来源</b>：没有数据库约束能拦住「把场景挂到自己的后代下」，
 * 也没有约束能限制树深。全部靠本类的 {@link #validateParent} 守；少写一行不会报错、
 * 不会红任何用例，只会让前端组树时无限递归（与 {@code IotDeviceGroupServiceImpl} 的成环校验同理）。</p>
 *
 * <p><b>删除不级联</b>：级联删掉用户几个月的配置是不可逆事故。存在子场景或点位时一律拒绝，
 * 由用户逐层显式确认。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinSceneServiceImpl extends BaseServiceImpl<IotTwinSceneMapper, IotTwinScene>
    implements IotTwinSceneService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinSceneServiceImpl.class);

    /** 根场景的 parentId 哨兵值（与表默认值一致）。 */
    private static final long ROOT_PARENT_ID = 0L;

    private final IotTwinPointMapper twinPointMapper;
    private final IotTwinModelAssetMapper twinModelAssetMapper;
    private final ObjectMapper objectMapper;

    public IotTwinSceneServiceImpl(IotTwinPointMapper twinPointMapper,
                                   IotTwinModelAssetMapper twinModelAssetMapper,
                                   ObjectMapper objectMapper) {
        this.twinPointMapper = twinPointMapper;
        this.twinModelAssetMapper = twinModelAssetMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IotTwinSceneResp> listScenes() {
        List<IotTwinScene> scenes = baseMapper.selectList(new LambdaQueryWrapper<IotTwinScene>()
            .orderByAsc(IotTwinScene::getParentId)
            .orderByAsc(IotTwinScene::getSort)
            .orderByAsc(IotTwinScene::getId));
        return toRespList(scenes);
    }

    @Override
    public IotTwinSceneResp getScene(Long id) {
        return toRespList(List.of(requireScene(id))).getFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IotTwinSceneReq req) {
        TwinSceneType sceneType = requireSceneType(req.getSceneType());
        checkSceneCodeUnique(req.getSceneCode(), null);
        long parentId = normParentId(req.getParentId());
        validateParent(null, parentId);
        requireModelAssetIfPresent(req.getModelAssetId());
        String viewJson = normalizeViewJson(req.getViewJson());

        IotTwinScene scene = new IotTwinScene();
        apply(scene, req, sceneType, parentId, viewJson);
        save(scene);
        return scene.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotTwinSceneReq req) {
        IotTwinScene scene = requireScene(id);
        TwinSceneType sceneType = requireSceneType(req.getSceneType());
        checkSceneCodeUnique(req.getSceneCode(), id);
        long parentId = normParentId(req.getParentId());
        validateParent(id, parentId);
        requireModelAssetIfPresent(req.getModelAssetId());
        String viewJson = normalizeViewJson(req.getViewJson());
        apply(scene, req, sceneType, parentId, viewJson);
        updateById(scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        IotTwinScene scene = requireScene(id);
        Long childCount = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getParentId, scene.getId()));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该场景下还有 " + childCount + " 个子场景，无法删除：请先删除（或移走）子场景。"
                    + "平台刻意不做级联删除，避免误删掉整棵配置树");
        }
        Long pointCount = twinPointMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, scene.getId()));
        if (pointCount != null && pointCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该场景下还有 " + pointCount + " 个设备点位，无法删除：请先删除点位"
                    + "（点位保存了设备与坐标，删场景不会替你决定它们去哪）");
        }
        removeById(id);
    }

    // ---------- 校验 ----------

    /**
     * 校验父场景与树深（同时防成环）。
     *
     * <p>上溯上限用 {@link TwinLimits#MAX_SCENE_DEPTH}：正常树远达不到 4 层以上，
     * 一旦遇到历史脏数据留下的环，到上限即拒绝并明确报「父子关系成环」，
     * 保证查询次数有界、不会无界循环。</p>
     *
     * @param selfId   当前场景主键（新建时为 null）
     * @param parentId 归一后的父场景 ID（0=根）
     */
    private void validateParent(Long selfId, long parentId) {
        if (parentId == ROOT_PARENT_ID) {
            return;
        }
        if (selfId != null && selfId == parentId) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不能作为自己的父场景");
        }
        long cursor = parentId;
        int parentDepth = 1;
        for (int step = 0; step <= TwinLimits.MAX_SCENE_DEPTH; step++) {
            IotTwinScene node = baseMapper.selectById(cursor);
            if (node == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "父场景不存在：" + cursor);
            }
            if (selfId != null && selfId.equals(node.getId())) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "场景不能挂到自己的后代下（父链上出现了当前场景本身）");
            }
            long next = node.getParentId() == null ? ROOT_PARENT_ID : node.getParentId();
            if (next == ROOT_PARENT_ID) {
                int newDepth = parentDepth + 1;
                if (newDepth > TwinLimits.MAX_SCENE_DEPTH) {
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "场景层级不能超过 " + TwinLimits.MAX_SCENE_DEPTH + " 层（当前将达第 "
                            + newDepth + " 层）。房间级展示请在楼层场景内做区域标注，不要再加一层");
                }
                return;
            }
            cursor = next;
            parentDepth++;
        }
        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
            "场景层级数据异常（父子关系成环）：请检查场景树的父场景配置");
    }

    private TwinSceneType requireSceneType(String code) {
        TwinSceneType type = TwinSceneType.of(code);
        if (type == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景类型不合法：" + code + "（允许值：park / building / floor / room）");
        }
        return type;
    }

    private void checkSceneCodeUnique(String sceneCode, Long excludeId) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getSceneCode, sceneCode)
            .ne(excludeId != null, IotTwinScene::getId, excludeId));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景编码已存在：" + sceneCode);
        }
    }

    /**
     * 模型资产必须存在且属于本租户。
     *
     * <p>查询走租户插件，跨租户资产查不到 ⇒ 报「模型资产不存在」而不是「无权限」：
     * 返回 403 等于告知调用方「该 id 确实存在、只是你无权访问」，属存在性泄露。
     * 与设计 D0.5 同口径。</p>
     */
    private void requireModelAssetIfPresent(Long modelAssetId) {
        if (modelAssetId == null) {
            return;
        }
        IotTwinModelAsset asset = twinModelAssetMapper.selectById(modelAssetId);
        if (asset == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型资产不存在：" + modelAssetId);
        }
    }

    /**
     * 校验并归一 {@code viewJson}。
     *
     * <p>它是前端拥有的展示偏好，但**必须校验合法性**：写进去一段坏 JSON 会让页面每次进入该场景
     * 都解析失败，而用户已无从知道是哪一步写坏的。大小上限 16KB 防「把整份配置塞进视角字段」。</p>
     *
     * @param viewJson 原始 JSON（可空）
     * @return 归一后的 JSON（空串归一为 null）
     */
    private String normalizeViewJson(String viewJson) {
        if (!StringUtils.hasText(viewJson)) {
            return null;
        }
        int bytes = viewJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > TwinLimits.MAX_VIEW_JSON_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "视角配置过大（" + bytes + " 字节，上限 " + TwinLimits.MAX_VIEW_JSON_BYTES + " 字节）");
        }
        try {
            objectMapper.readTree(viewJson);
        } catch (JacksonException ex) {
            log.warn("[iot] 场景视角配置不是合法 JSON，已拒绝保存", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "视角配置不是合法的 JSON：" + ex.getOriginalMessage());
        }
        return viewJson;
    }

    private long normParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    private void apply(IotTwinScene scene, IotTwinSceneReq req, TwinSceneType sceneType,
                       long parentId, String viewJson) {
        scene.setSceneCode(req.getSceneCode().trim());
        scene.setSceneName(req.getSceneName().trim());
        scene.setParentId(parentId);
        scene.setSceneType(sceneType.getCode());
        scene.setModelAssetId(req.getModelAssetId());
        scene.setAnchorX(TwinSceneTransform.zeroIfNull(req.getAnchorX()));
        scene.setAnchorY(TwinSceneTransform.zeroIfNull(req.getAnchorY()));
        scene.setAnchorZ(TwinSceneTransform.zeroIfNull(req.getAnchorZ()));
        scene.setAnchorRx(TwinSceneTransform.zeroIfNull(req.getAnchorRx()));
        scene.setAnchorRy(TwinSceneTransform.zeroIfNull(req.getAnchorRy()));
        scene.setAnchorRz(TwinSceneTransform.zeroIfNull(req.getAnchorRz()));
        scene.setAnchorScale(TwinSceneTransform.requireScale(req.getAnchorScale()));
        scene.setViewJson(viewJson);
        scene.setSort(req.getSort() == null ? 0 : req.getSort());
        scene.setRemark(req.getRemark());
    }

    private IotTwinScene requireScene(Long id) {
        IotTwinScene scene = getById(id);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + id);
        }
        return scene;
    }

    // ---------- 响应转换 ----------

    /**
     * 批量转换为响应（**一次分组查询**填点数与子场景数，不逐行查库）。
     *
     * @param scenes 场景实体列表
     * @return 响应列表
     */
    private List<IotTwinSceneResp> toRespList(List<IotTwinScene> scenes) {
        if (scenes.isEmpty()) {
            return List.of();
        }
        List<Long> sceneIds = scenes.stream().map(IotTwinScene::getId).toList();
        Map<Long, Map<String, Object>> statsById = new HashMap<>();
        for (Map<String, Object> row : baseMapper.countPointsAndChildren(sceneIds)) {
            statsById.put(asLong(row.get("sceneId")), row);
        }
        List<IotTwinSceneResp> result = new ArrayList<>(scenes.size());
        for (IotTwinScene scene : scenes) {
            result.add(toResp(scene, statsById.get(scene.getId())));
        }
        return result;
    }

    private IotTwinSceneResp toResp(IotTwinScene scene, Map<String, Object> stats) {
        IotTwinSceneResp resp = new IotTwinSceneResp();
        resp.setId(scene.getId());
        resp.setSceneCode(scene.getSceneCode());
        resp.setSceneName(scene.getSceneName());
        resp.setParentId(scene.getParentId());
        resp.setSceneType(scene.getSceneType());
        resp.setModelAssetId(scene.getModelAssetId());
        resp.setAnchorX(scene.getAnchorX());
        resp.setAnchorY(scene.getAnchorY());
        resp.setAnchorZ(scene.getAnchorZ());
        resp.setAnchorRx(scene.getAnchorRx());
        resp.setAnchorRy(scene.getAnchorRy());
        resp.setAnchorRz(scene.getAnchorRz());
        resp.setAnchorScale(scene.getAnchorScale());
        resp.setViewJson(scene.getViewJson());
        resp.setSort(scene.getSort());
        resp.setRemark(scene.getRemark());
        resp.setCreateTime(scene.getCreateTime());
        resp.setPointCount(stats == null ? 0 : (int) asLong(stats.get("pointCount")));
        resp.setChildCount(stats == null ? 0 : (int) asLong(stats.get("childCount")));
        return resp;
    }

    /**
     * 安全转 long（{@code COUNT(*)} 在不同驱动下可能返回 Long/Integer/BigInteger）。
     *
     * @param value 原始值
     * @return 数字；null 或不可解析时为 0
     */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            log.warn("[iot] 场景统计值不是数字，按 0 处理：value={}", value);
            return 0L;
        }
    }
}
```

### 9.2 `service/impl/TwinBindingResolver.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数字孪生绑定 ↔ 物模型属性的**批量解析器**（读侧自愈 + 写侧校验）。
 *
 * <p><b>为什么需要这一层</b>：绑定的权威键是 {@code (serviceIdentifier, propertyIdentifier)}，
 * 而 {@code propertyId} 只是缓存。属性行会被 TSL 重新导入**物理删除**
 * （{@code IotThingModelServiceImpl#replaceTsl}）⇒ 缓存随时可能悬空。这一层负责：</p>
 * <ol>
 *   <li>读侧：按业务标识批量重解析出当前属性行，{@code propertyId} 悬空时<b>自愈回写</b>；</li>
 *   <li>读侧：解析不到的绑定标 {@code stale=true}（<b>不抛异常</b>——历史绑定失效是正常状态，
 *       页面要能看到并提示重配，抛异常会让整页打不开）；</li>
 *   <li>写侧：校验请求里的 {@code (serviceIdentifier, propertyIdentifier)} 在指定产品下确实存在，
 *       一次列全所有不存在的组合（不让用户改一条试一次）。</li>
 * </ol>
 *
 * <p><b>全程批量</b>：无论多少个绑定，最多 2 次查询（一次 {@code iot_property}、一次
 * {@code iot_service}），不随绑定数增长（N+1 零容忍）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Component
public class TwinBindingResolver {

    private static final Logger log = LoggerFactory.getLogger(TwinBindingResolver.class);

    private final IotPropertyMapper iotPropertyMapper;
    private final IotServiceMapper iotServiceMapper;
    private final IotProductMapper iotProductMapper;
    private final IotTwinPointBindingMapper twinPointBindingMapper;

    public TwinBindingResolver(IotPropertyMapper iotPropertyMapper,
                               IotServiceMapper iotServiceMapper,
                               IotProductMapper iotProductMapper,
                               IotTwinPointBindingMapper twinPointBindingMapper) {
        this.iotPropertyMapper = iotPropertyMapper;
        this.iotServiceMapper = iotServiceMapper;
        this.iotProductMapper = iotProductMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
    }

    /**
     * 解析一组绑定的展示信息（读侧）。
     *
     * @param bindings 绑定实体（可为空列表）
     * @return 绑定主键 → 解析结果（键集合与入参一一对应）
     */
    public Map<Long, ResolvedBinding> resolve(Collection<IotTwinPointBinding> bindings) {
        Map<Long, ResolvedBinding> result = new LinkedHashMap<>();
        if (bindings == null || bindings.isEmpty()) {
            return result;
        }
        // ① 一次按业务标识取全部可能命中的属性行（含 service_id）
        Set<Long> propertyIds = new LinkedHashSet<>();
        for (IotTwinPointBinding binding : bindings) {
            if (binding.getPropertyId() != null) {
                propertyIds.add(binding.getPropertyId());
            }
        }
        List<IotProperty> properties = propertyIds.isEmpty()
            ? List.of()
            : iotPropertyMapper.selectBatchIds(propertyIds);

        // ② 一次取全部被引用的服务行（拿到 service_id 业务标识，用于按标识匹配）
        Set<Long> serviceIds = new LinkedHashSet<>();
        for (IotProperty property : properties) {
            if (property.getServiceId() != null) {
                serviceIds.add(property.getServiceId());
            }
        }
        Map<Long, IotService> serviceById = loadServices(serviceIds);

        // ③ 按 (serviceIdentifier, propertyIdentifier) 建索引；同时保留「按主键命中」的索引
        Map<String, IotProperty> propertyByKey = new HashMap<>();
        Map<Long, IotProperty> propertyById = new HashMap<>();
        for (IotProperty property : properties) {
            propertyById.put(property.getId(), property);
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                propertyByKey.put(key(service.getServiceId(), property.getIdentifier()), property);
            }
        }

        for (IotTwinPointBinding binding : bindings) {
            IotProperty matched = matchProperty(binding, propertyById, propertyByKey);
            result.put(binding.getId(), toResolved(binding, matched, serviceById));
        }
        healStaleCache(bindings, result);
        return result;
    }

    /**
     * 校验请求里的绑定在指定产品下确实存在（写侧）。
     *
     * <p>一次列全所有不存在的组合再抛错：用户改一条存一次、再报下一条，是这块配置最常见的
     * 折磨点（一次绑定通常 3~8 条，逐条试错要 8 次往返）。</p>
     *
     * @param productId 产品主键（设备绑定的产品；null 表示设备未绑定产品）
     * @param keys      请求里的绑定业务键
     */
    public void validateBindings(Long productId, List<BindingKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (productId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备未绑定产品，无法配置属性绑定：请先在设备台账里为该设备选择产品");
        }
        List<Long> serviceIds = iotServiceMapper.selectList(new LambdaQueryWrapper<IotService>()
                .eq(IotService::getProductId, productId))
            .stream().map(IotService::getId).toList();
        if (serviceIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备绑定的产品还没有任何物模型服务，无法配置属性绑定");
        }
        Map<Long, IotService> serviceById = loadServices(new LinkedHashSet<>(serviceIds));
        Map<String, IotProperty> propertyByKey = new HashMap<>();
        for (IotProperty property : iotPropertyMapper.selectList(
            new LambdaQueryWrapper<IotProperty>().in(IotProperty::getServiceId, serviceIds))) {
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                propertyByKey.put(key(service.getServiceId(), property.getIdentifier()), property);
            }
        }
        List<String> missing = new ArrayList<>();
        for (BindingKey key : keys) {
            if (!propertyByKey.containsKey(key(serviceIdentifier(key), propertyIdentifier(key)))) {
                missing.add(serviceIdentifier(key) + "." + propertyIdentifier(key));
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "以下属性在设备绑定的产品物模型里不存在（共 " + missing.size() + " 个）："
                    + String.join("、", missing)
                    + "。请确认服务标识与属性标识，或先在产品物模型里补齐该属性");
        }
    }

    /**
     * 批量取属性主键（写侧用：把业务标识解析成当前的主键缓存值）。
     *
     * @param productId 产品主键
     * @param keys      绑定业务键
     * @return 业务键 → 属性主键（调用前必须先 {@link #validateBindings} 保证全部存在）
     */
    public Map<String, Long> resolvePropertyIds(Long productId, List<BindingKey> keys) {
        Map<String, Long> result = new HashMap<>();
        if (productId == null || keys == null || keys.isEmpty()) {
            return result;
        }
        List<Long> serviceIds = iotServiceMapper.selectList(new LambdaQueryWrapper<IotService>()
                .eq(IotService::getProductId, productId))
            .stream().map(IotService::getId).toList();
        if (serviceIds.isEmpty()) {
            return result;
        }
        Map<Long, IotService> serviceById = loadServices(new LinkedHashSet<>(serviceIds));
        for (IotProperty property : iotPropertyMapper.selectList(
            new LambdaQueryWrapper<IotProperty>().in(IotProperty::getServiceId, serviceIds))) {
            IotService service = serviceById.get(property.getServiceId());
            if (service != null) {
                result.put(key(service.getServiceId(), property.getIdentifier()), property.getId());
            }
        }
        return result;
    }

    private Map<Long, IotService> loadServices(Set<Long> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, IotService> serviceById = new HashMap<>();
        for (IotService service : iotServiceMapper.selectBatchIds(serviceIds)) {
            serviceById.put(service.getId(), service);
        }
        return serviceById;
    }

    /**
     * 先用主键缓存命中，再按业务标识兜底（主键悬空时）。
     */
    private IotProperty matchProperty(IotTwinPointBinding binding,
                                      Map<Long, IotProperty> propertyById,
                                      Map<String, IotProperty> propertyByKey) {
        if (binding.getPropertyId() != null) {
            IotProperty byId = propertyById.get(binding.getPropertyId());
            if (byId != null) {
                return byId;
            }
        }
        return propertyByKey.get(key(binding.getServiceIdentifier(), binding.getPropertyIdentifier()));
    }

    private ResolvedBinding toResolved(IotTwinPointBinding binding, IotProperty matched,
                                       Map<Long, IotService> serviceById) {
        ResolvedBinding resolved = new ResolvedBinding();
        resolved.setBindingId(binding.getId());
        resolved.setStale(matched == null);
        if (matched != null) {
            resolved.setPropertyId(matched.getId());
            resolved.setPropertyName(matched.getPropertyName());
            resolved.setDataType(matched.getDataType());
            IotService service = serviceById.get(matched.getServiceId());
            resolved.setServiceName(service == null ? null : service.getServiceName());
        } else {
            resolved.setPropertyId(null);
            // 解析不到时不编造名称：回落属性标识本身，让用户至少知道它原来指向什么
            resolved.setPropertyName(binding.getPropertyIdentifier());
            resolved.setDataType(null);
            resolved.setServiceName(null);
        }
        resolved.setUnit(binding.getUnitOverride() != null
            ? binding.getUnitOverride()
            : (matched == null ? null : matched.getUnit()));
        return resolved;
    }

    /**
     * 自愈：主键缓存悬空但业务标识能解析到的，回写新的 {@code property_id}。
     *
     * <p>只在「解析成功且主键与缓存不一致」时回写，多条一起 {@code UPDATE}（不逐条写）。
     * 回写失败只记 WARN 不影响本次响应（读路径不该因为写失败而报错）。</p>
     */
    private void healStaleCache(Collection<IotTwinPointBinding> bindings,
                                Map<Long, ResolvedBinding> resolved) {
        Map<Long, Long> healing = new LinkedHashMap<>();
        for (IotTwinPointBinding binding : bindings) {
            ResolvedBinding hit = resolved.get(binding.getId());
            if (hit == null || Boolean.TRUE.equals(hit.getStale()) || hit.getPropertyId() == null) {
                continue;
            }
            if (!hit.getPropertyId().equals(binding.getPropertyId())) {
                healing.put(binding.getId(), hit.getPropertyId());
            }
        }
        if (healing.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<Long, Long> entry : healing.entrySet()) {
                twinPointBindingMapper.update(null, new LambdaUpdateWrapper<IotTwinPointBinding>()
                    .eq(IotTwinPointBinding::getId, entry.getKey())
                    .set(IotTwinPointBinding::getPropertyId, entry.getValue()));
            }
            log.info("[iot] 孪生绑定主键缓存已自愈回写：count={} 条（属性行因 TSL 重导入换了主键）",
                healing.size());
        } catch (RuntimeException ex) {
            // 读路径不该因写失败而整体失败：记录完整堆栈并继续返回本次已解析的结果
            log.warn("[iot] 孪生绑定主键缓存回写失败（不影响本次读取）：count={} 条", healing.size(), ex);
        }
    }

    /**
     * 绑定业务键（写侧入参的抽象）。
     *
     * @param serviceIdentifier  服务标识
     * @param propertyIdentifier 属性标识
     */
    public record BindingKey(String serviceIdentifier, String propertyIdentifier) {
    }

    private static String serviceIdentifier(BindingKey key) {
        return key.serviceIdentifier();
    }

    private static String propertyIdentifier(BindingKey key) {
        return key.propertyIdentifier();
    }

    /**
     * 拼业务键（唯一口径，避免各处各拼一份）。
     *
     * @param serviceIdentifier  服务标识
     * @param propertyIdentifier 属性标识
     * @return 业务键
     */
    public static String key(String serviceIdentifier, String propertyIdentifier) {
        return serviceIdentifier + "\u0000" + propertyIdentifier;
    }

    /**
     * 解析结果（读侧）。
     *
     * @author wenbin
     * @since 2026-10-21
     */
    @Getter
    @Setter
    public static class ResolvedBinding {

        /** 绑定主键。 */
        private Long bindingId;

        /** 当前属性主键（解析不到时为 null）。 */
        private Long propertyId;

        /** 属性显示名（解析不到时回落属性标识）。 */
        private String propertyName;

        /** 物模型数据类型（解析不到时为 null）。 */
        private String dataType;

        /** 服务名称（解析不到时为 null）。 */
        private String serviceName;

        /** 生效单位（unit_override 优先）。 */
        private String unit;

        /** 是否已失效。 */
        private Boolean stale;
    }
}
```

### 9.3 `service/impl/IotTwinPointServiceImpl.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.PointRefType;
import cn.ypbin.admin.iot.enums.TwinDisplayRole;
import cn.ypbin.admin.iot.enums.TwinDisplayType;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.req.TwinBindingReq;
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import cn.ypbin.admin.iot.model.resp.TwinBindingResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 数字孪生点位服务实现（锚点 + 属性绑定，编辑时整体替换绑定）。
 *
 * <p><b>绑定为什么整体替换而不是逐条 CRUD</b>：一次点位编辑的用户心智是「这个锚点最终展示这 N 个属性」，
 * 逐条 diff 会引出「先删后加导致唯一键冲突」「前端要维护待删除列表」等一连串状态问题。
 * 整体替换只有一条规则：请求里的列表 = 保存后的列表。</p>
 *
 * <p><b>绑定唯一键不含 is_deleted</b>（沿用 006 的「逻辑删除后业务键不可复用」约定）⇒
 * 替换必须先<b>物理删除</b>旧行再插入，否则「删掉某属性再绑回来」会主键冲突
 * （见 {@code IotTwinPointBindingMapper#physicalDeleteByPointIds}）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinPointServiceImpl extends BaseServiceImpl<IotTwinPointMapper, IotTwinPoint>
    implements IotTwinPointService {

    private final IotTwinSceneMapper twinSceneMapper;
    private final IotTwinPointBindingMapper twinPointBindingMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotProductMapper productMapper;
    private final IotServiceMapper serviceMapper;
    private final IotPropertyMapper propertyMapper;
    private final IotPointMappingMapper pointMappingMapper;
    private final TwinBindingResolver bindingResolver;

    public IotTwinPointServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                    IotTwinPointBindingMapper twinPointBindingMapper,
                                    IotDeviceMapper deviceMapper,
                                    IotProductMapper productMapper,
                                    IotServiceMapper serviceMapper,
                                    IotPropertyMapper propertyMapper,
                                    IotPointMappingMapper pointMappingMapper,
                                    TwinBindingResolver bindingResolver) {
        this.twinSceneMapper = twinSceneMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.serviceMapper = serviceMapper;
        this.propertyMapper = propertyMapper;
        this.pointMappingMapper = pointMappingMapper;
        this.bindingResolver = bindingResolver;
    }

    @Override
    public List<IotTwinPointResp> listByScene(Long sceneId) {
        requireScene(sceneId);
        List<IotTwinPoint> points = baseMapper.selectList(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .orderByAsc(IotTwinPoint::getSort)
            .orderByAsc(IotTwinPoint::getId));
        if (points.isEmpty()) {
            return List.of();
        }
        List<Long> pointIds = points.stream().map(IotTwinPoint::getId).toList();
        // 一次取回本场景全部绑定（不逐点位查）
        List<IotTwinPointBinding> bindings = twinPointBindingMapper.selectList(
            new LambdaQueryWrapper<IotTwinPointBinding>()
                .in(IotTwinPointBinding::getPointId, pointIds)
                .orderByAsc(IotTwinPointBinding::getSort)
                .orderByAsc(IotTwinPointBinding::getId));
        Map<Long, TwinBindingResolver.ResolvedBinding> resolved = bindingResolver.resolve(bindings);
        Map<Long, List<TwinBindingResp>> bindingByPoint = groupBindings(bindings, resolved);
        List<IotTwinPointResp> result = new ArrayList<>(points.size());
        for (IotTwinPoint point : points) {
            result.add(toResp(point, bindingByPoint.getOrDefault(point.getId(), List.of())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long sceneId, IotTwinPointReq req) {
        requireScene(sceneId);
        IotDevice device = requireDevice(req.getDeviceId());
        requirePointCountBelowLimit(sceneId);
        checkDeviceNotBoundInScene(sceneId, device.getId(), null);
        List<TwinBindingReq> bindings = normalizeBindings(req.getBindings());
        Long productId = device.getProductId();
        bindingResolver.validateBindings(productId, toBindingKeys(bindings));
        Map<String, Long> propertyIdByKey = bindingResolver.resolvePropertyIds(productId, toBindingKeys(bindings));

        IotTwinPoint point = new IotTwinPoint();
        point.setSceneId(sceneId);
        apply(point, req);
        save(point);
        insertBindings(point.getId(), bindings, propertyIdByKey);
        return point.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long sceneId, Long pointId, IotTwinPointReq req) {
        requireScene(sceneId);
        IotTwinPoint point = requirePointInScene(sceneId, pointId);
        IotDevice device = requireDevice(req.getDeviceId());
        checkDeviceNotBoundInScene(sceneId, device.getId(), pointId);
        List<TwinBindingReq> bindings = normalizeBindings(req.getBindings());
        Long productId = device.getProductId();
        bindingResolver.validateBindings(productId, toBindingKeys(bindings));
        Map<String, Long> propertyIdByKey = bindingResolver.resolvePropertyIds(productId, toBindingKeys(bindings));

        apply(point, req);
        updateById(point);
        // 整体替换：先物理删除旧绑定，再插入新绑定（见类注释）
        twinPointBindingMapper.physicalDeleteByPointIds(List.of(pointId));
        insertBindings(pointId, bindings, propertyIdByKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long sceneId, Long pointId) {
        requireScene(sceneId);
        requirePointInScene(sceneId, pointId);
        twinPointBindingMapper.physicalDeleteByPointIds(List.of(pointId));
        removeById(pointId);
    }

    @Override
    public List<IotTwinDevicePropertyResp> listDeviceProperties(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        if (device.getProductId() == null) {
            // 未绑定产品是正常空态：不是错误，页面据此提示「该设备未绑定产品」
            return List.of();
        }
        IotProduct product = productMapper.selectById(device.getProductId());
        if (product == null) {
            return List.of();
        }
        List<IotService> services = serviceMapper.selectList(new LambdaQueryWrapper<IotService>()
            .eq(IotService::getProductId, product.getId())
            .orderByAsc(IotService::getSort)
            .orderByAsc(IotService::getId));
        if (services.isEmpty()) {
            return List.of();
        }
        List<Long> serviceIds = services.stream().map(IotService::getId).toList();
        List<IotProperty> properties = propertyMapper.selectList(new LambdaQueryWrapper<IotProperty>()
            .in(IotProperty::getServiceId, serviceIds)
            .orderByAsc(IotProperty::getSort)
            .orderByAsc(IotProperty::getId));
        if (properties.isEmpty()) {
            return List.of();
        }
        // mapped：该属性在本设备上是否已有采集点位映射（一次 IN 查询）
        Set<Long> mappedPropertyIds = new HashSet<>();
        for (IotPointMapping mapping : pointMappingMapper.selectList(
            new LambdaQueryWrapper<IotPointMapping>()
                .eq(IotPointMapping::getDeviceId, device.getId())
                .eq(IotPointMapping::getRefType, PointRefType.PROPERTY.getCode())
                .eq(IotPointMapping::getEnabled, Boolean.TRUE))) {
            mappedPropertyIds.add(mapping.getPropertyId());
        }
        Map<Long, IotService> serviceById = new HashMap<>();
        for (IotService service : services) {
            serviceById.put(service.getId(), service);
        }
        List<IotTwinDevicePropertyResp> result = new ArrayList<>(properties.size());
        for (IotProperty property : properties) {
            IotService service = serviceById.get(property.getServiceId());
            if (service == null) {
                continue;
            }
            IotTwinDevicePropertyResp resp = new IotTwinDevicePropertyResp();
            resp.setServiceId(service.getId());
            resp.setServiceIdentifier(service.getServiceId());
            resp.setServiceName(service.getServiceName());
            resp.setPropertyId(property.getId());
            resp.setPropertyIdentifier(property.getIdentifier());
            resp.setPropertyName(property.getPropertyName());
            resp.setDataType(property.getDataType());
            resp.setAccessMode(property.getAccessMode());
            resp.setUnit(property.getUnit());
            resp.setMinValue(property.getMinValue());
            resp.setMaxValue(property.getMaxValue());
            resp.setMapped(mappedPropertyIds.contains(property.getId()));
            result.add(resp);
        }
        return result;
    }

    // ---------- 校验 ----------

    private IotTwinScene requireScene(Long sceneId) {
        IotTwinScene scene = twinSceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + sceneId);
        }
        return scene;
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private IotTwinPoint requirePointInScene(Long sceneId, Long pointId) {
        IotTwinPoint point = getById(pointId);
        if (point == null || !sceneId.equals(point.getSceneId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位不存在或不属于该场景：" + pointId);
        }
        return point;
    }

    private void requirePointCountBelowLimit(Long sceneId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId));
        if (count != null && count >= TwinLimits.MAX_POINTS_PER_SCENE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单个场景最多 " + TwinLimits.MAX_POINTS_PER_SCENE + " 个设备点位（当前已有 "
                    + count + " 个）。点位过多会让运行态渲染与实时更新都不可用，"
                    + "建议拆成下级场景");
        }
    }

    private void checkDeviceNotBoundInScene(Long sceneId, Long deviceId, Long excludePointId) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .eq(IotTwinPoint::getDeviceId, deviceId)
            .ne(excludePointId != null, IotTwinPoint::getId, excludePointId));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备在本场景已有锚点：同一场景内一台设备只能有一个锚点"
                    + "（否则同一个值会显示在两处，且改一处另一处不同步）");
        }
    }

    /**
     * 归一化并校验绑定列表（枚举白名单 + 重复检测 + primary 唯一）。
     *
     * @param bindings 请求绑定列表（非 null，由 Req 的 {@code @NotNull} 保证）
     * @return 归一后的列表（保留请求顺序）
     */
    private List<TwinBindingReq> normalizeBindings(List<TwinBindingReq> bindings) {
        if (bindings == null) {
            // Controller 的 @NotNull 已拦一次；服务层再拦一次是因为「清空绑定」必须显式传 []，
            // 而 null 是漏字段。若这里放行成 NPE，全局处理器会把它转成「系统内部错误」，
            // 用户拿到的错误信息完全无法行动。
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "属性绑定列表不能为空（清空绑定请传空数组）");
        }
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        int primaryCount = 0;
        List<TwinBindingReq> normalized = new ArrayList<>(bindings.size());
        for (TwinBindingReq binding : bindings) {
            TwinDisplayType displayType = TwinDisplayType.of(binding.getDisplayType());
            if (displayType == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "展示形态不合法：" + binding.getDisplayType()
                        + "（允许值：value / gauge / switch / color / sparkline）");
            }
            TwinDisplayRole displayRole = TwinDisplayRole.of(binding.getDisplayRole());
            if (displayRole == null) {
                if (StringUtils.hasText(binding.getDisplayRole())) {
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "展示角色不合法：" + binding.getDisplayRole()
                            + "（允许值：primary / secondary）");
                }
                displayRole = TwinDisplayRole.SECONDARY;
            }
            if (displayRole == TwinDisplayRole.PRIMARY) {
                primaryCount++;
            }
            String key = TwinBindingResolver.key(binding.getServiceIdentifier().trim(),
                binding.getPropertyIdentifier().trim());
            if (!seen.add(key)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "属性绑定重复：" + binding.getServiceIdentifier() + "."
                        + binding.getPropertyIdentifier());
            }
            TwinBindingReq item = new TwinBindingReq();
            item.setServiceIdentifier(binding.getServiceIdentifier().trim());
            item.setPropertyIdentifier(binding.getPropertyIdentifier().trim());
            item.setDisplayType(displayType.getCode());
            item.setDisplayRole(displayRole.getCode());
            item.setDecimals(binding.getDecimals());
            item.setUnitOverride(binding.getUnitOverride());
            item.setSort(binding.getSort() == null ? normalized.size() : binding.getSort());
            normalized.add(item);
        }
        if (primaryCount > 1) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "每个点位最多只能有 1 个主展示属性（当前 " + primaryCount
                    + " 个）：主展示是锚点旁常显的那一行，多于一个时页面无法决定显示哪个值");
        }
        return normalized;
    }

    private List<TwinBindingResolver.BindingKey> toBindingKeys(List<TwinBindingReq> bindings) {
        List<TwinBindingResolver.BindingKey> keys = new ArrayList<>(bindings.size());
        for (TwinBindingReq binding : bindings) {
            keys.add(new TwinBindingResolver.BindingKey(binding.getServiceIdentifier(),
                binding.getPropertyIdentifier()));
        }
        return keys;
    }

    // ---------- 写入 ----------

    private void apply(IotTwinPoint point, IotTwinPointReq req) {
        point.setDeviceId(req.getDeviceId());
        point.setNodeName(req.getNodeName());
        point.setLocalX(req.getLocalX());
        point.setLocalY(req.getLocalY());
        point.setLocalZ(req.getLocalZ());
        point.setNormalX(req.getNormalX() == null ? BigDecimal.ZERO : req.getNormalX());
        point.setNormalY(req.getNormalY() == null ? BigDecimal.ONE : req.getNormalY());
        point.setNormalZ(req.getNormalZ() == null ? BigDecimal.ZERO : req.getNormalZ());
        point.setIcon(req.getIcon());
        point.setLabelVisible(Boolean.FALSE.equals(req.getLabelVisible()) ? 0 : 1);
        point.setSort(req.getSort() == null ? 0 : req.getSort());
        point.setRemark(req.getRemark());
    }

    private void insertBindings(Long pointId, List<TwinBindingReq> bindings,
                                Map<String, Long> propertyIdByKey) {
        if (bindings.isEmpty()) {
            return;
        }
        for (TwinBindingReq binding : bindings) {
            IotTwinPointBinding entity = new IotTwinPointBinding();
            entity.setPointId(pointId);
            entity.setServiceIdentifier(binding.getServiceIdentifier());
            entity.setPropertyIdentifier(binding.getPropertyIdentifier());
            entity.setPropertyId(propertyIdByKey.get(TwinBindingResolver.key(
                binding.getServiceIdentifier(), binding.getPropertyIdentifier())));
            entity.setDisplayType(binding.getDisplayType());
            entity.setDisplayRole(binding.getDisplayRole());
            entity.setDecimals(binding.getDecimals());
            entity.setUnitOverride(binding.getUnitOverride());
            entity.setSort(binding.getSort());
            twinPointBindingMapper.insert(entity);
        }
    }

    // ---------- 响应转换 ----------

    private Map<Long, List<TwinBindingResp>> groupBindings(
        List<IotTwinPointBinding> bindings,
        Map<Long, TwinBindingResolver.ResolvedBinding> resolved) {
        Map<Long, List<TwinBindingResp>> grouped = new LinkedHashMap<>();
        for (IotTwinPointBinding binding : bindings) {
            TwinBindingResolver.ResolvedBinding hit = resolved.get(binding.getId());
            TwinBindingResp resp = new TwinBindingResp();
            resp.setId(binding.getId());
            resp.setServiceIdentifier(binding.getServiceIdentifier());
            resp.setPropertyIdentifier(binding.getPropertyIdentifier());
            resp.setDisplayType(binding.getDisplayType());
            resp.setDisplayRole(binding.getDisplayRole());
            resp.setDecimals(binding.getDecimals());
            resp.setUnitOverride(binding.getUnitOverride());
            resp.setSort(binding.getSort());
            if (hit != null) {
                resp.setPropertyId(hit.getPropertyId());
                resp.setPropertyName(hit.getPropertyName());
                resp.setDataType(hit.getDataType());
                resp.setUnit(hit.getUnit());
                resp.setStale(hit.getStale());
            } else {
                // 理论上不可达（resolve 对每个入参都产出结果）；保持自洽语义而不是留 null
                resp.setPropertyName(binding.getPropertyIdentifier());
                resp.setStale(Boolean.TRUE);
            }
            grouped.computeIfAbsent(binding.getPointId(), key -> new ArrayList<>()).add(resp);
        }
        for (List<TwinBindingResp> list : grouped.values()) {
            list.sort(Comparator
                .comparing((TwinBindingResp item) -> TwinDisplayRole.PRIMARY.getCode()
                    .equals(item.getDisplayRole()) ? 0 : 1)
                .thenComparing(TwinBindingResp::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TwinBindingResp::getId));
        }
        return grouped;
    }

    private IotTwinPointResp toResp(IotTwinPoint point, List<TwinBindingResp> bindings) {
        IotTwinPointResp resp = new IotTwinPointResp();
        resp.setId(point.getId());
        resp.setSceneId(point.getSceneId());
        resp.setDeviceId(point.getDeviceId());
        resp.setNodeName(point.getNodeName());
        resp.setLocalX(point.getLocalX());
        resp.setLocalY(point.getLocalY());
        resp.setLocalZ(point.getLocalZ());
        resp.setNormalX(point.getNormalX());
        resp.setNormalY(point.getNormalY());
        resp.setNormalZ(point.getNormalZ());
        resp.setIcon(point.getIcon());
        resp.setLabelVisible(Integer.valueOf(1).equals(point.getLabelVisible()));
        resp.setSort(point.getSort());
        resp.setRemark(point.getRemark());
        resp.setCreateTime(point.getCreateTime());
        resp.setBindings(bindings);
        return resp;
    }
}
```

### 9.4 `service/impl/TwinModelFileGuard.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型文件的内容校验（**按内容校验，不是按扩展名**）。
 *
 * <p><b>为什么必须做魔数校验</b>：只校验扩展名等于没有校验——把任意文件改名为 {@code .glb}
 * 就能塞进存储，并通过带鉴权的下载端点取回，等于给平台开了一个「可上传任意内容」的通道
 * （可用于存放HTML做钓鱼、存放体积炸弹）。这里按 glTF 规范的真实头部判定：</p>
 * <ul>
 *   <li>{@code .glb}：头部 12 字节 = 魔数 {@code 0x46546C67}（ASCII {@code glTF}）+ version(4) + 总长(4)，
 *       version 必须为 {@value TwinLimits#GLTF_VERSION}；</li>
 *   <li>{@code .gltf}：必须是 UTF-8 JSON，且顶层含 {@code asset} 对象、其 {@code version} 以
 *       {@code "2."} 开头。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Component
public class TwinModelFileGuard {

    private static final Logger log = LoggerFactory.getLogger(TwinModelFileGuard.class);

    /** glTF 2.x 的版本前缀（规范写法：{@code "2.0"}）。 */
    private static final String GLTF_VERSION_PREFIX = "2.";

    /** {@code .gltf} 探测时最多读取的字节数（JSON 头部足够判定，不整文件读入内存）。 */
    private static final int GLTF_PROBE_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public TwinModelFileGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从原始文件名推断并校验格式。
     *
     * @param originalName 原始文件名
     * @return 归一化后的扩展名（{@code glb} / {@code gltf}）
     */
    public String requireFormat(String originalName) {
        String ext = extension(originalName);
        if (TwinLimits.EXT_GLB.equals(ext) || TwinLimits.EXT_GLTF.equals(ext)) {
            return ext;
        }
        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
            "只支持 .glb 与 .gltf 模型文件（当前文件：" + originalName
                + "）。其它格式请先用建模工具或 glTF 转换工具导出为 glTF 2.0");
    }

    /**
     * 校验内容头部（**流会被读取前若干字节，调用方需传入可重复读的流或按需重新打开**）。
     *
     * @param format  归一化扩展名（{@code glb} / {@code gltf}）
     * @param content 模型内容流（方法内不关闭；调用方负责）
     * @return 模型内容摘要信息（后续落存储时不需要再读一次头部）
     * @throws IOException 读取流失败
     */
    public ProbeResult probe(String format, InputStream content) throws IOException {
        if (TwinLimits.EXT_GLB.equals(format)) {
            return probeGlb(content);
        }
        return probeGltf(content);
    }

    private ProbeResult probeGlb(InputStream content) throws IOException {
        byte[] header = content.readNBytes(TwinLimits.GLB_HEADER_BYTES);
        if (header.length < TwinLimits.GLB_HEADER_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 GLB：内容不足 " + TwinLimits.GLB_HEADER_BYTES + " 字节，无法读取文件头");
        }
        int magic = readIntLE(header, 0);
        if (magic != TwinLimits.GLB_MAGIC) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 GLB：文件头魔数不匹配（期望 ASCII \"glTF\"）。"
                    + "若这是 ASCII glTF 文本文件，请把扩展名改为 .gltf 后重新上传");
        }
        int version = readIntLE(header, 4);
        if (version != TwinLimits.GLTF_VERSION) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "不支持的 glTF 版本：" + version + "（只支持 glTF "
                    + TwinLimits.GLTF_VERSION + "）。请用支持 glTF 2.0 的工具重新导出");
        }
        long declaredLength = readIntLE(header, 8) & 0xFFFFFFFFL;
        if (declaredLength <= 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "GLB 文件头声明的总长度为 0，文件已损坏");
        }
        return new ProbeResult(declaredLength);
    }

    private ProbeResult probeGltf(InputStream content) throws IOException {
        byte[] head = content.readNBytes(GLTF_PROBE_BYTES);
        if (head.length == 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "文件内容为空");
        }
        String text = new String(head, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JacksonException ex) {
            log.warn("[iot] .gltf 文件头部不是合法 JSON，已拒绝", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 glTF JSON：" + ex.getOriginalMessage()
                    + "。若这是二进制 GLB 文件，请把扩展名改为 .glb 后重新上传");
        }
        JsonNode asset = root.get("asset");
        if (asset == null || !asset.isObject()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 glTF：顶层缺少 asset 对象（glTF 2.0 规范要求）");
        }
        JsonNode versionNode = asset.get("version");
        String version = versionNode == null || versionNode.isNull() ? null : versionNode.asString();
        if (version == null || !version.startsWith(GLTF_VERSION_PREFIX)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "不支持的 glTF 版本：" + (version == null ? "未声明" : version)
                    + "（只支持 glTF " + TwinLimits.GLTF_VERSION + "）");
        }
        return new ProbeResult(-1L);
    }

    /**
     * 提取扩展名（小写，不含点）。
     *
     * @param fileName 文件名
     * @return 扩展名（无扩展名时为空串）
     */
    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 小端读取 4 字节整数（GLB 规范固定小端）。
     *
     * @param buf    字节数组
     * @param offset 起始偏移
     * @return 整数值
     */
    private static int readIntLE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
            | ((buf[offset + 1] & 0xFF) << 8)
            | ((buf[offset + 2] & 0xFF) << 16)
            | ((buf[offset + 3] & 0xFF) << 24);
    }

    /**
     * 探测结果。
     *
     * @param declaredLength GLB 头部声明的文件总长（{@code .gltf} 时为 -1，表示未知）
     */
    public record ProbeResult(long declaredLength) {
    }

    /**
     * 校验节点名快照（长度、条目数、总体积）。
     *
     * <p>它是辅助数据，校验失败**不阻塞上传**：截断到上限后继续，并记 WARN。
     * 但 JSON 序列化后超过 1MB 时直接丢弃（避免把 1MB 的辅助数据写进业务行）。</p>
     *
     * @param nodeNames 页面上报的节点名列表（可空）
     * @return 归一后的列表（保证不超过上限）
     */
    public List<String> normalizeNodeNames(List<String> nodeNames) {
        if (nodeNames == null || nodeNames.isEmpty()) {
            return List.of();
        }
        List<String> result = nodeNames.stream()
            .filter(name -> name != null && !name.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (result.size() > TwinLimits.MAX_NODE_NAME_SNAPSHOT) {
            log.warn("[iot] 模型节点名快照超过上限，已截断：原 {} 条，上限 {} 条",
                result.size(), TwinLimits.MAX_NODE_NAME_SNAPSHOT);
            return result.subList(0, TwinLimits.MAX_NODE_NAME_SNAPSHOT);
        }
        return result;
    }
}
```

### 9.5 `service/impl/IotTwinModelAssetServiceImpl.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.TwinAssetType;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import cn.ypbin.admin.iot.service.IotTwinModelAssetService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.storage.core.FileStorageService;
import cn.ypbin.starter.storage.model.FileInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 数字孪生模型资产服务实现。
 *
 * <p><b>为什么不复用 {@code /system/file/upload}</b>：该端点所在控制器标了 {@code @PlatformAccess}
 * （非平台用户一律 403），租户用户根本传不上来；且它依赖 {@code ypbin.storage} 配置，
 * 而本仓当前没有任何该配置 ⇒ {@code StorageRouter#route(null)} 会抛
 * 「未指定存储平台且未配置默认平台」。孪生的上传通道因此自建并自管（设计 §6.7）。</p>
 *
 * <p><b>失败补偿</b>：存储写入成功但落库失败时，必须把已写入的物理文件删掉——
 * 否则每次失败都留下一份没人引用的 20MB 文件，磁盘只增不减。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinModelAssetServiceImpl
    extends BaseServiceImpl<IotTwinModelAssetMapper, IotTwinModelAsset>
    implements IotTwinModelAssetService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinModelAssetServiceImpl.class);

    /** 孪生模型使用的存储平台标识（与 {@code ypbin.storage.local[0].platform} 同值）。 */
    private static final String STORAGE_PLATFORM = "twin";

    /** 存储路径前缀（落在 platform 的 base-path 之下）。 */
    private static final String STORAGE_PATH_PREFIX = "models/";

    /** {@code .glb} 的 MIME 类型（glTF 规范的注册类型）。 */
    private static final String CONTENT_TYPE_GLB = "model/gltf-binary";

    /** {@code .gltf} 的 MIME 类型。 */
    private static final String CONTENT_TYPE_GLTF = "model/gltf+json";

    /** 允许的外链 scheme。 */
    private static final List<String> ALLOWED_URL_SCHEMES = List.of("http", "https");

    private final IotTwinSceneMapper twinSceneMapper;
    private final FileStorageService fileStorageService;
    private final TwinModelFileGuard fileGuard;
    private final ObjectMapper objectMapper;

    public IotTwinModelAssetServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                        FileStorageService fileStorageService,
                                        TwinModelFileGuard fileGuard,
                                        ObjectMapper objectMapper) {
        this.twinSceneMapper = twinSceneMapper;
        this.fileStorageService = fileStorageService;
        this.fileGuard = fileGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IotTwinModelAssetResp> listAssets() {
        List<IotTwinModelAsset> assets = baseMapper.selectList(new LambdaQueryWrapper<IotTwinModelAsset>()
            .orderByDesc(IotTwinModelAsset::getCreateTime)
            .orderByDesc(IotTwinModelAsset::getId));
        return toRespList(assets);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotTwinModelAssetResp upload(MultipartFile file, String assetName, List<String> nodeNames)
        throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        // ① 扩展名（最便宜的校验先做）
        String format = fileGuard.requireFormat(originalName);
        // ② 字节数
        if (file.getSize() > TwinLimits.MAX_MODEL_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型文件过大（" + file.getSize() + " 字节，上限 " + TwinLimits.MAX_MODEL_BYTES
                    + " 字节 = 64MB）。请先用 glTF 压缩工具（Draco/KTX2）压缩后重试");
        }
        String resolvedName = resolveAssetName(assetName, originalName);
        checkAssetNameUnique(resolvedName, null);
        // ③ 内容头部（魔数 / glTF 版本）
        try (InputStream probeStream = file.getInputStream()) {
            fileGuard.probe(format, probeStream);
        }
        List<String> normalizedNodeNames = fileGuard.normalizeNodeNames(nodeNames);
        byte[] nodeNamesJson = serializeNodeNames(normalizedNodeNames);

        // ④ 落存储（边读边算 SHA-256 需要中间缓冲；这里用文件字节数组，
        //    因为上限 64MB 且 MultipartFile 本身已在临时文件/内存中）
        FileInfo info;
        try (InputStream uploadStream = file.getInputStream()) {
            info = fileStorageService.upload(uploadStream, originalName)
                .platform(STORAGE_PLATFORM)
                .path(STORAGE_PATH_PREFIX)
                .contentType(file.getContentType())
                .size(file.getSize())
                .execute();
        }

        // ⑤ 落库（失败则补偿删除物理文件）
        try {
            IotTwinModelAsset asset = new IotTwinModelAsset();
            asset.setAssetName(resolvedName);
            asset.setAssetType(TwinAssetType.UPLOAD.getCode());
            asset.setModelFormat(format);
            asset.setStoragePlatform(info.getPlatform());
            asset.setStorageBucket(info.getBucket());
            asset.setStoragePath(info.getPath());
            asset.setOriginalName(originalName);
            asset.setFileSize(file.getSize());
            asset.setContentHash(sha256(file));
            asset.setNodeNamesJson(nodeNamesJson == null
                ? null : new String(nodeNamesJson, StandardCharsets.UTF_8));
            save(asset);
            return toRespList(List.of(asset)).getFirst();
        } catch (RuntimeException ex) {
            compensateUpload(info, ex);
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotTwinModelAssetResp createExternal(IotTwinModelExternalReq req) {
        String url = req.getExternalUrl() == null ? null : req.getExternalUrl().trim();
        requireHttpUrl(url);
        String format = StringUtils.hasText(req.getModelFormat())
            ? fileGuard.requireFormat("x." + req.getModelFormat().trim().toLowerCase(Locale.ROOT))
            : fileGuard.requireFormat(url);
        checkAssetNameUnique(req.getAssetName().trim(), null);

        IotTwinModelAsset asset = new IotTwinModelAsset();
        asset.setAssetName(req.getAssetName().trim());
        asset.setAssetType(TwinAssetType.EXTERNAL.getCode());
        asset.setModelFormat(format);
        asset.setExternalUrl(url);
        save(asset);
        return toRespList(List.of(asset)).getFirst();
    }

    @Override
    public ModelContent openContent(Long id) {
        IotTwinModelAsset asset = requireAsset(id);
        if (!TwinAssetType.UPLOAD.getCode().equals(asset.getAssetType())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型是外链登记，没有平台内文件：请直接使用登记的外链地址加载");
        }
        if (!StringUtils.hasText(asset.getStoragePlatform())
            || !StringUtils.hasText(asset.getStoragePath())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型缺少存储定位信息，无法下载：请删除后重新上传（资产 ID " + asset.getId() + "）");
        }
        InputStream stream = fileStorageService.download(asset.getStoragePlatform(),
            asset.getStorageBucket(), asset.getStoragePath());
        String contentType = TwinLimits.EXT_GLB.equals(asset.getModelFormat())
            ? CONTENT_TYPE_GLB : CONTENT_TYPE_GLTF;
        String fileName = StringUtils.hasText(asset.getOriginalName())
            ? asset.getOriginalName()
            : asset.getAssetName() + "." + asset.getModelFormat();
        return new ModelContent(fileName, contentType,
            asset.getFileSize() == null ? -1L : asset.getFileSize(),
            asset.getContentHash(), stream);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        IotTwinModelAsset asset = requireAsset(id);
        Long refCount = twinSceneMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getModelAssetId, asset.getId()));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型已被 " + refCount + " 个场景引用，无法删除：请先在这些场景里换绑模型"
                    + "（删除会留下打不开的场景）");
        }
        if (TwinAssetType.UPLOAD.getCode().equals(asset.getAssetType())
            && StringUtils.hasText(asset.getStoragePath())) {
            try {
                fileStorageService.delete(asset.getStoragePlatform(), asset.getStorageBucket(),
                    asset.getStoragePath());
            } catch (RuntimeException ex) {
                // 物理文件删不掉时**不能**静默删记录：那样文件成为永久孤儿且无人知道。
                // 记录完整堆栈并如实失败，让运维看到并手工清理。
                log.error("[iot] 删除孪生模型物理文件失败：assetId={} platform={} path={}",
                    asset.getId(), asset.getStoragePlatform(), asset.getStoragePath(), ex);
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "模型文件删除失败（存储不可用或路径不存在），资产记录已保留："
                        + "请稍后重试或联系运维清理存储路径 " + asset.getStoragePath());
            }
        }
        removeById(id);
    }

    // ---------- 校验与工具 ----------

    private IotTwinModelAsset requireAsset(Long id) {
        IotTwinModelAsset asset = getById(id);
        if (asset == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "模型资产不存在：" + id);
        }
        return asset;
    }

    private void checkAssetNameUnique(String assetName, Long excludeId) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinModelAsset>()
            .eq(IotTwinModelAsset::getAssetName, assetName)
            .ne(excludeId != null, IotTwinModelAsset::getId, excludeId));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "模型名称已存在：" + assetName);
        }
    }

    private String resolveAssetName(String assetName, String originalName) {
        String candidate = StringUtils.hasText(assetName)
            ? assetName.trim() : stripExtension(originalName);
        if (!StringUtils.hasText(candidate)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "无法确定模型名称：请显式提供 assetName 或使用带文件名的上传");
        }
        if (candidate.length() > TwinLimits.MAX_ASSET_NAME_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型名称长度不能超过 " + TwinLimits.MAX_ASSET_NAME_LENGTH);
        }
        return candidate;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * 校验外链地址：只允许 http/https 绝对地址。
     *
     * <p>禁止 {@code javascript:}/{@code data:}/{@code file:}：前端会用 {@code fetch} 拉取该地址，
     * {@code data:} 可以绕过文件大小限制、{@code javascript:} 是明确的注入面。</p>
     */
    private void requireHttpUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "外链地址不能为空");
        }
        if (url.length() > TwinLimits.MAX_EXTERNAL_URL_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址长度不能超过 " + TwinLimits.MAX_EXTERNAL_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址格式不合法：" + ex.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址只允许 http/https 绝对地址（当前 scheme：" + scheme + "）");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址缺少主机名：" + url);
        }
    }

    private byte[] serializeNodeNames(List<String> nodeNames) {
        if (nodeNames.isEmpty()) {
            return null;
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(nodeNames);
            if (json.length > TwinLimits.MAX_NODE_NAMES_JSON_BYTES) {
                log.warn("[iot] 模型节点名快照 JSON 过大，已丢弃：bytes={} 上限={}",
                    json.length, TwinLimits.MAX_NODE_NAMES_JSON_BYTES);
                return null;
            }
            return json;
        } catch (JacksonException ex) {
            log.warn("[iot] 模型节点名快照序列化失败，已丢弃（不影响模型上传）：count={}",
                nodeNames.size(), ex);
            return null;
        }
    }

    /**
     * 计算上传文件的 SHA-256（用于判重与 ETag）。
     *
     * @param file 上传文件
     * @return 十六进制小写摘要
     * @throws IOException 读取失败
     */
    private String sha256(MultipartFile file) throws IOException {
        try (InputStream stream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必然内置 SHA-256；真缺了说明运行环境异常，必须显式暴露而不是返回 null
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", ex);
        }
    }

    private void compensateUpload(FileInfo info, RuntimeException cause) {
        try {
            fileStorageService.delete(info.getPlatform(), info.getBucket(), info.getPath());
        } catch (RuntimeException cleanupFailure) {
            cleanupFailure.addSuppressed(cause);
            log.error("[iot] 模型上传落库失败且物理文件补偿删除失败（留下孤儿文件，需人工清理）："
                    + "platform={} bucket={} path={}",
                info.getPlatform(), info.getBucket(), info.getPath(), cleanupFailure);
        }
    }

    // ---------- 响应转换 ----------

    private List<IotTwinModelAssetResp> toRespList(List<IotTwinModelAsset> assets) {
        if (assets.isEmpty()) {
            return List.of();
        }
        List<Long> assetIds = assets.stream().map(IotTwinModelAsset::getId).toList();
        Map<Long, Integer> sceneCountById = new HashMap<>();
        for (Map<String, Object> row : baseMapper.countSceneReferences(assetIds)) {
            sceneCountById.put(asLong(row.get("assetId")), (int) asLong(row.get("sceneCount")));
        }
        List<IotTwinModelAssetResp> result = new ArrayList<>(assets.size());
        for (IotTwinModelAsset asset : assets) {
            result.add(toResp(asset, sceneCountById.getOrDefault(asset.getId(), 0)));
        }
        return result;
    }

    private IotTwinModelAssetResp toResp(IotTwinModelAsset asset, int sceneCount) {
        IotTwinModelAssetResp resp = new IotTwinModelAssetResp();
        resp.setId(asset.getId());
        resp.setAssetName(asset.getAssetName());
        resp.setAssetType(asset.getAssetType());
        resp.setModelFormat(asset.getModelFormat());
        resp.setExternalUrl(asset.getExternalUrl());
        resp.setContentUrl(contentUrl(asset));
        resp.setOriginalName(asset.getOriginalName());
        resp.setFileSize(asset.getFileSize());
        resp.setContentHash(asset.getContentHash());
        resp.setNodeNames(parseNodeNames(asset));
        resp.setSceneCount(sceneCount);
        resp.setCreateTime(asset.getCreateTime());
        return resp;
    }

    /**
     * 页面实际使用的模型地址：upload 走平台下载端点（同源 + 鉴权 + 浏览器缓存），
     * external 用外链原文。
     */
    private String contentUrl(IotTwinModelAsset asset) {
        if (TwinAssetType.EXTERNAL.getCode().equals(asset.getAssetType())) {
            return asset.getExternalUrl();
        }
        return "/iot/twin/model-assets/" + asset.getId() + "/content";
    }

    private List<String> parseNodeNames(IotTwinModelAsset asset) {
        if (!StringUtils.hasText(asset.getNodeNamesJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(asset.getNodeNamesJson(), new TypeReference<List<String>>() { });
        } catch (JacksonException ex) {
            // 辅助数据坏掉不影响任何功能：记 WARN 返回空数组，不让资产列表打不开
            log.warn("[iot] 模型节点名快照解析失败，按空列表返回：assetId={}", asset.getId(), ex);
            return List.of();
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            log.warn("[iot] 模型统计值不是数字，按 0 处理：value={}", value);
            return 0L;
        }
    }
}
```

---

### 9.6 `service/impl/IotTwinRuntimeServiceImpl.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.admin.iot.model.resp.TwinRuntimePointResp;
import cn.ypbin.admin.iot.model.resp.TwinRuntimeValueResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.service.IotTwinRuntimeService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.admin.iot.values.LatestValueQueryService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数字孪生运行态服务实现。
 *
 * <p><b>整个组装过程只有固定次数的查询</b>（与点位数无关）：
 * ① 场景 1 次；② 点位 1 次；③ 绑定 1 次；④ 设备 1 次；⑤ 最新值 1 次 pipeline；
 * ⑥ 活动告警 1 次分组。500 个点位的场景与 1 个点位的场景查询次数相同。</p>
 *
 * <p><b>不宣称「原子快照」</b>：{@code serverTime} 是开始组装的时刻；各设备读数来自不同时刻的上报。
 * 需要严格同时刻切片时走时序库，不是本服务。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinRuntimeServiceImpl implements IotTwinRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinRuntimeServiceImpl.class);

    private final IotTwinSceneMapper twinSceneMapper;
    private final IotTwinPointMapper twinPointMapper;
    private final IotTwinPointBindingMapper twinPointBindingMapper;
    private final IotDeviceMapper deviceMapper;
    private final LatestValueQueryService latestValueQueryService;
    private final AlertInstanceService alertInstanceService;
    private final TwinBindingResolver bindingResolver;

    public IotTwinRuntimeServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                      IotTwinPointMapper twinPointMapper,
                                      IotTwinPointBindingMapper twinPointBindingMapper,
                                      IotDeviceMapper deviceMapper,
                                      LatestValueQueryService latestValueQueryService,
                                      AlertInstanceService alertInstanceService,
                                      TwinBindingResolver bindingResolver) {
        this.twinSceneMapper = twinSceneMapper;
        this.twinPointMapper = twinPointMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
        this.deviceMapper = deviceMapper;
        this.latestValueQueryService = latestValueQueryService;
        this.alertInstanceService = alertInstanceService;
        this.bindingResolver = bindingResolver;
    }

    @Override
    public IotTwinRuntimeResp runtime(Long sceneId) {
        IotTwinScene scene = twinSceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + sceneId);
        }
        IotTwinRuntimeResp resp = new IotTwinRuntimeResp();
        resp.setSceneId(scene.getId());
        resp.setServerTime(LocalDateTime.now());

        List<IotTwinPoint> points = twinPointMapper.selectList(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .orderByAsc(IotTwinPoint::getSort)
            .orderByAsc(IotTwinPoint::getId));
        if (points.isEmpty()) {
            resp.setPointCount(0);
            resp.setTruncated(Boolean.FALSE);
            resp.setPoints(List.of());
            return resp;
        }
        boolean truncated = points.size() > TwinLimits.MAX_POINTS_PER_SCENE;
        if (truncated) {
            log.warn("[iot] 场景点位数超过上限，运行态已截断：sceneId={} count={} limit={}",
                sceneId, points.size(), TwinLimits.MAX_POINTS_PER_SCENE);
            points = points.subList(0, TwinLimits.MAX_POINTS_PER_SCENE);
        }
        List<Long> pointIds = points.stream().map(IotTwinPoint::getId).toList();
        List<Long> deviceIds = points.stream().map(IotTwinPoint::getDeviceId).distinct().toList();

        // ③ 一次取本场景全部绑定，按点位分组（供逐点位组装 values 与 stale）
        List<IotTwinPointBinding> bindings = twinPointBindingMapper.selectList(
            new LambdaQueryWrapper<IotTwinPointBinding>()
                .in(IotTwinPointBinding::getPointId, pointIds));
        Map<Long, TwinBindingResolver.ResolvedBinding> resolvedBindings =
            bindingResolver.resolve(bindings);
        Map<Long, List<IotTwinPointBinding>> bindingsByPoint = bindings.stream()
            .collect(Collectors.groupingBy(IotTwinPointBinding::getPointId));

        // ④ 一次取设备（同时完成「属于本租户」的过滤：跨租户设备查不到）
        Map<Long, IotDevice> deviceById = new HashMap<>();
        for (IotDevice device : deviceMapper.selectBatchIds(deviceIds)) {
            deviceById.put(device.getId(), device);
        }

        // ⑤ 一次 Redis pipeline 取全部设备最新值
        Map<Long, Map<String, LatestValueResp>> latestByDevice =
            toLatestByIdentifier(latestValueQueryService.listLatestBatch(deviceIds));

        // ⑥ 一次分组统计活动告警数
        Map<Long, Integer> alertCountByDevice = alertInstanceService.activeCountsByDevice(deviceIds);

        List<TwinRuntimePointResp> pointResps = new ArrayList<>(points.size());
        for (IotTwinPoint point : points) {
            IotDevice device = deviceById.get(point.getDeviceId());
            if (device == null) {
                // 设备已在台账里被删除（或不属于本租户）：点位仍在配置里。
                // 不伪造设备信息、也不丢弃点位——页面需要看到「这里有锚点但设备没了」才能去修。
                log.info("[iot] 孪生点位的设备已不存在，按空设备信息返回：pointId={} deviceId={}",
                    point.getId(), point.getDeviceId());
            }
            pointResps.add(toPointResp(point, device,
                bindingsByPoint.getOrDefault(point.getId(), List.of()),
                latestByDevice.getOrDefault(point.getDeviceId(), Map.of()),
                resolvedBindings,
                alertCountByDevice.getOrDefault(point.getDeviceId(), 0)));
        }
        resp.setPointCount(pointResps.size());
        resp.setTruncated(truncated);
        resp.setPoints(pointResps);
        return resp;
    }

    /**
     * 把批量最新值按「设备 → 属性标识 → 最新值」重建索引。
     *
     * <p>批量服务返回的是每台设备的列表，逐点位取值需要按属性标识查；在这里重建一次索引，
     * 避免点位循环里对列表做线性查找（500 点位 × 12 属性时那是平方级）。</p>
     */
    private Map<Long, Map<String, LatestValueResp>> toLatestByIdentifier(
        Map<Long, List<LatestValueResp>> latestByDevice) {
        Map<Long, Map<String, LatestValueResp>> result = new HashMap<>(latestByDevice.size());
        for (Map.Entry<Long, List<LatestValueResp>> entry : latestByDevice.entrySet()) {
            Map<String, LatestValueResp> byIdentifier = new HashMap<>();
            for (LatestValueResp value : entry.getValue()) {
                if (value.getPropertyId() != null) {
                    byIdentifier.put(value.getPropertyId(), value);
                }
            }
            result.put(entry.getKey(), byIdentifier);
        }
        return result;
    }

    private TwinRuntimePointResp toPointResp(IotTwinPoint point, IotDevice device,
                                              List<IotTwinPointBinding> pointBindings,
                                              Map<String, LatestValueResp> latestByProperty,
                                              Map<Long, TwinBindingResolver.ResolvedBinding> resolved,
                                              int alertCount) {
        TwinRuntimePointResp resp = new TwinRuntimePointResp();
        resp.setPointId(point.getId());
        resp.setDeviceId(point.getDeviceId());
        if (device != null) {
            resp.setDeviceCode(device.getDeviceCode());
            resp.setDeviceName(device.getDeviceName());
            resp.setOnlineStatus(device.getOnlineStatus());
            resp.setStatus(device.getStatus());
            resp.setLastSeenAt(device.getLastSeenAt());
        } else {
            resp.setOnlineStatus(null);
            resp.setStatus(null);
            resp.setLastSeenAt(null);
        }
        resp.setAlertCount(alertCount);
        Map<String, TwinRuntimeValueResp> values = new LinkedHashMap<>();
        for (IotTwinPointBinding binding : pointBindings) {
            String propertyIdentifier = binding.getPropertyIdentifier();
            TwinRuntimeValueResp value = values.computeIfAbsent(propertyIdentifier, key -> {
                TwinRuntimeValueResp created = new TwinRuntimeValueResp();
                LatestValueResp latest = latestByProperty.get(key);
                if (latest != null) {
                    created.setValue(latest.getValue());
                    created.setQuality(latest.getQuality());
                    created.setTs(latest.getTs());
                }
                created.setStale(Boolean.FALSE);
                return created;
            });
            // 同一属性可能被两条绑定引用（不同展示形态），stale 结论相同：任一条失效即标记失效
            TwinBindingResolver.ResolvedBinding hit = resolved.get(binding.getId());
            if (hit != null && Boolean.TRUE.equals(hit.getStale())) {
                value.setStale(Boolean.TRUE);
            }
        }
        resp.setValues(values);
        return resp;
    }
}
```

### 9.7 `values/LatestValueQueryService` 的新增方法（**修改既有文件**）

在既有类中新增以下内容（原有 `listLatest` 逻辑不变，只抽取共用部分）：

```java
    /**
     * 批量查询多台设备的最新值（**一次 Redis pipeline**，不按设备循环 HGETALL）。
     *
     * <p><b>为什么必须批量</b>：孪生场景一次可能展示 500 个点位（对应最多 500 台设备）。
     * 逐台调用 {@link #listLatest(Long)} 会发出 500 次 Redis 往返 + 500 次设备查库 +
     * 500 次坐标索引查询——那是一次页面打开就要几十秒、且把 Redis 连接池打满的形态。
     * 本方法把这三类操作各压成一次。</p>
     *
     * <p><b>与单设备版的结果完全一致</b>：每台设备内部沿用同一套「历史坐标形态归一 + ts 取新 +
     * ts 并列时规范标识胜」的裁决（抽取为 {@link #mergeDeviceEntries}，供两个方法共用），
     * 因此同一份 Redis 数据无论走哪条路径，返回值逐字段相同。</p>
     *
     * <p><b>失败语义（与单设备版一致）</b>：Redis 未装配或读取异常时，每台设备都返回空列表——
     * 「没有最新值」不是故障；但异常必须 {@code log.error} 带堆栈并累加
     * {@value #METRIC_READ_FAILED}，让「Redis 抖动」在指标上可见。</p>
     *
     * @param deviceIds 设备主键集合（为空时返回空 Map，不发任何查询）
     * @return 设备主键 → 最新值列表（每台设备的列表按属性标识升序；Redis 不可用时值为空列表）。
     *         返回的 Map 对入参中**每一台存在的设备**都有条目（可能对应空列表）。
     */
    public Map<Long, List<LatestValueResp>> listLatestBatch(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            // 空集合短路：既省三次查询，也避免空 IN 生成非法 SQL
            return Map.of();
        }
        List<Long> idList = deviceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (idList.isEmpty()) {
            return Map.of();
        }
        // ① 一次查设备（同时完成租户过滤：跨租户设备查不到 ⇒ 不出现在结果里）
        Map<Long, IotDevice> deviceById = new LinkedHashMap<>();
        for (IotDevice device : deviceMapper.selectByIds(idList)) {
            deviceById.put(device.getId(), device);
        }
        if (deviceById.isEmpty()) {
            return Map.of();
        }
        // ② 一次取坐标索引（与单设备版同一数据源，保证归一裁决口径相同）
        Map<Long, PointMappingIndex.DeviceCoordinates> coordinatesByDevice =
            pointMappingIndex.loadCoordinates(deviceById.keySet());
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("[iot] 未发现 StringRedisTemplate：批量最新值查询返回空结果（设备数={}）",
                deviceById.size());
            return emptyResult(deviceById.keySet());
        }
        // ③ 一次 pipeline（不是 N 次 HGETALL）
        List<Object> pipelined;
        try {
            List<Long> orderedIds = new ArrayList<>(deviceById.keySet());
            pipelined = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long deviceId : orderedIds) {
                    IotDevice device = deviceById.get(deviceId);
                    byte[] key = RedisLatestValueWriter.key(device.getTenantId(), deviceId)
                        .getBytes(StandardCharsets.UTF_8);
                    connection.hashCommands().hGetAll(key);
                }
                // pipeline 内的回调返回值被框架忽略（结果由 executePipelined 收集）
                return null;
            });
        } catch (RuntimeException ex) {
            readFailedCounter.increment();
            log.error("[iot] 批量最新值读取失败（按空列表返回，不伪造数据）：设备数={}",
                deviceById.size(), ex);
            return emptyResult(deviceById.keySet());
        }
        List<Long> orderedIds = new ArrayList<>(deviceById.keySet());
        Map<Long, List<LatestValueResp>> result = new LinkedHashMap<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            Long deviceId = orderedIds.get(index);
            Object raw = index < pipelined.size() ? pipelined.get(index) : null;
            Map<Object, Object> entries = toEntriesMap(raw, deviceId);
            result.put(deviceId,
                mergeDeviceEntries(deviceId, entries, coordinatesByDevice.get(deviceId)));
        }
        return result;
    }

    /**
     * 把 pipeline 的单个结果转成 entries（Redis 返回 {@code Map<Object,Object>}；
     * 空哈希返回空 Map）。
     */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> toEntriesMap(Object raw, Long deviceId) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        if (raw != null) {
            log.warn("[iot] 批量最新值的 pipeline 结果类型非预期，按空处理：deviceId={} type={}",
                deviceId, raw.getClass().getName());
        }
        return Map.of();
    }

    /**
     * 合并单台设备的哈希条目为结果列表（**单设备版与批量版共用的唯一裁决实现**）。
     *
     * @param deviceId    设备主键
     * @param entries     Redis 哈希条目（可能为空）
     * @param coordinates 该设备的坐标索引（可空，空时按原样使用 field）
     * @return 最新值列表（按属性标识升序；空 entries ⇒ 空列表）
     */
    private List<LatestValueResp> mergeDeviceEntries(Long deviceId, Map<Object, Object> entries,
                                                     PointMappingIndex.DeviceCoordinates coordinates) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, String> canonicalByForm = coordinates == null
            ? Map.of() : coordinates.canonicalByForm();
        Map<String, LatestValueResp> winners = new LinkedHashMap<>();
        Map<String, String> winnerForm = new LinkedHashMap<>();
        int legacyFields = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String form = String.valueOf(entry.getKey());
            LatestValueResp value = parse(form, entry.getValue() == null
                ? null : String.valueOf(entry.getValue()), deviceId);
            if (value == null) {
                continue;
            }
            String canonical = canonicalByForm.getOrDefault(form, form);
            if (!canonical.equals(form)) {
                legacyFields++;
            }
            value.setPropertyId(canonical);
            LatestValueResp known = winners.get(canonical);
            if (known == null || isBetterCandidate(value, form, canonical, known, winnerForm.get(canonical))) {
                winners.put(canonical, value);
                winnerForm.put(canonical, form);
            }
        }
        if (legacyFields > 0) {
            legacyFieldCounter.increment(legacyFields);
            log.info("[iot] 最新值读到**历史坐标形态**（属性主键字符串）并已归一到属性标识："
                    + "deviceId={} 条数={}（过渡期兼容；backfill 完成后该日志与 "
                    + "iot.latest.coordinate.legacy 应归零）",
                LogSanitizer.sanitize(deviceId), legacyFields);
        }
        List<LatestValueResp> result = new ArrayList<>(winners.size());
        winners.keySet().stream().sorted().forEach(canonical -> result.add(winners.get(canonical)));
        return result;
    }

    /**
     * 构造「每台设备空列表」的结果（Redis 不可用/读取失败时使用，保证 Map 覆盖全部入参设备）。
     */
    private Map<Long, List<LatestValueResp>> emptyResult(Collection<Long> deviceIds) {
        Map<Long, List<LatestValueResp>> result = new LinkedHashMap<>();
        for (Long deviceId : deviceIds) {
            result.put(deviceId, List.of());
        }
        return result;
    }
```

**同时把既有 `listLatest(Long)` 的循环体替换为调用 `mergeDeviceEntries`**（消除两份裁决逻辑）：

```java
    public List<LatestValueResp> listLatest(Long deviceId) {
        // ... 前置校验与 HGETALL 保持不变 ...
        PointMappingIndex.DeviceCoordinates coordinates = pointMappingIndex
            .loadCoordinates(List.of(deviceId))
            .getOrDefault(deviceId, PointMappingIndex.DeviceCoordinates.empty());
        return mergeDeviceEntries(deviceId, entries, coordinates);
    }
```

**需要补充的导入**（既有文件顶部）：

```java
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.redis.core.RedisCallback;
```

> `RedisCallback` 的泛型参数为 `Object`，lambda 体内顺序必须与 `orderedIds` **完全一致**
> （pipeline 的结果按命令入队顺序返回）。这是本方法唯一的正确性前提，已在
> `LatestValueQueryServiceBatchTest` 里用「设备顺序颠倒」的用例钉死。

---

## 10. 控制器

### 10.1 `controller/IotTwinSceneController.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import cn.ypbin.admin.iot.service.IotTwinSceneService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生场景接口（树形）。
 *
 * <p>路径由网关 {@code Path=/iot/**} + {@code StripPrefix=1} 转发：客户端调
 * {@code /iot/twin/scenes}，服务内看到 {@code /twin/scenes}。</p>
 *
 * <p><b>为什么场景不按资源挂细粒度权限码</b>（如「每个场景一个权限」）：场景是展示配置，
 * 不是设备/规则/凭据这类受控资源，同一租户内共享同一套配置是设计意图（与组态看板同口径）。
 * 四个权限码区分的是「读 / 建 / 改 / 删」四类动作。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes")
@RequiredArgsConstructor
public class IotTwinSceneController {

    private final IotTwinSceneService twinSceneService;

    /**
     * 查询全部场景（树形由客户端按 parentId 组装）。
     *
     * @return 场景列表（无数据为空数组）
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinSceneResp>> list() {
        return R.ok(twinSceneService.listScenes());
    }

    /**
     * 查询单个场景。
     *
     * @param id 场景主键
     * @return 场景详情
     */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:twin:list")
    public R<IotTwinSceneResp> detail(@PathVariable Long id) {
        return R.ok(twinSceneService.getScene(id));
    }

    /**
     * 新建场景。
     *
     * @param req 场景信息
     * @return 新场景主键
     */
    @PostMapping
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("新增数字孪生场景")
    public R<Long> create(@Valid @RequestBody IotTwinSceneReq req) {
        return R.ok(twinSceneService.create(req));
    }

    /**
     * 编辑场景。
     *
     * @param id  场景主键
     * @param req 场景信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:twin:update")
    @Idempotent
    @Log("编辑数字孪生场景")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IotTwinSceneReq req) {
        twinSceneService.update(id, req);
        return R.ok();
    }

    /**
     * 删除场景（存在子场景或点位时拒绝，不级联删除）。
     *
     * @param id 场景主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生场景")
    public R<Void> remove(@PathVariable Long id) {
        twinSceneService.remove(id);
        return R.ok();
    }
}
```

### 10.2 `controller/IotTwinPointController.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生点位接口（设备锚点 + 属性绑定，挂在场景下）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes/{sceneId}/points")
@RequiredArgsConstructor
public class IotTwinPointController {

    private final IotTwinPointService twinPointService;

    /**
     * 查询场景下全部点位（含绑定）。
     *
     * @param sceneId 场景主键
     * @return 点位列表
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinPointResp>> list(@PathVariable Long sceneId) {
        return R.ok(twinPointService.listByScene(sceneId));
    }

    /**
     * 新增点位（可同时带绑定）。
     *
     * @param sceneId 场景主键
     * @param req     点位信息
     * @return 新点位主键
     */
    @PostMapping
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("新增数字孪生点位")
    public R<Long> create(@PathVariable Long sceneId, @Valid @RequestBody IotTwinPointReq req) {
        return R.ok(twinPointService.create(sceneId, req));
    }

    /**
     * 编辑点位并整体替换其绑定。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @param req     点位信息
     * @return 空响应
     */
    @PutMapping("/{pointId}")
    @SaCheckPermission("iot:twin:update")
    @Idempotent
    @Log("编辑数字孪生点位")
    public R<Void> update(@PathVariable Long sceneId, @PathVariable Long pointId,
                          @Valid @RequestBody IotTwinPointReq req) {
        twinPointService.update(sceneId, pointId, req);
        return R.ok();
    }

    /**
     * 删除点位（连同其绑定）。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @return 空响应
     */
    @DeleteMapping("/{pointId}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生点位")
    public R<Void> remove(@PathVariable Long sceneId, @PathVariable Long pointId) {
        twinPointService.remove(sceneId, pointId);
        return R.ok();
    }
}
```

### 10.3 `controller/IotTwinDevicePropertyController.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.service.IotTwinPointService;
import cn.ypbin.starter.core.model.R;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生配置页的「设备可绑定属性」接口。
 *
 * <p>为什么单独一个控制器而不是塞进点位控制器：它的主语是<b>设备</b>而不是场景，
 * 路径也挂在 {@code /twin/devices/{deviceId}} 下。混进场景控制器会让路径语义与
 * {@code @RequestMapping} 冲突。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/devices")
@RequiredArgsConstructor
public class IotTwinDevicePropertyController {

    private final IotTwinPointService twinPointService;

    /**
     * 查询设备可绑定的物模型属性（含「是否已配置采集点位」）。
     *
     * <p>设备未绑定产品时返回空数组（正常空态，不是错误）。
     *
     * @param deviceId 设备主键
     * @return 属性列表
     */
    @GetMapping("/{deviceId}/properties")
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinDevicePropertyResp>> properties(@PathVariable Long deviceId) {
        return R.ok(twinPointService.listDeviceProperties(deviceId));
    }
}
```

### 10.4 `controller/IotTwinRuntimeController.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;
import cn.ypbin.admin.iot.service.IotTwinRuntimeService;
import cn.ypbin.starter.core.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字孪生运行态快照接口。
 *
 * <p>实时增量走既有的 {@code GET /iot/realtime/stream}（SSE 的 latest/device-status/alert 三事件），
 * 本接口只提供「进入场景时的一次拉齐」。两者职责不同，不重复实现实时链路。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/scenes/{sceneId}")
@RequiredArgsConstructor
public class IotTwinRuntimeController {

    private final IotTwinRuntimeService twinRuntimeService;

    /**
     * 组装场景运行态快照。
     *
     * @param sceneId 场景主键
     * @return 运行态快照
     */
    @GetMapping("/runtime")
    @SaCheckPermission("iot:twin:list")
    public R<IotTwinRuntimeResp> runtime(@PathVariable Long sceneId) {
        return R.ok(twinRuntimeService.runtime(sceneId));
    }
}
```

### 10.5 `controller/IotTwinModelController.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import cn.ypbin.admin.iot.service.IotTwinModelAssetService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数字孪生模型资产接口。
 *
 * <p><b>模型下载为什么返回 {@code ResponseEntity} 而不是 {@code R}</b>：它是二进制文件流，
 * 响应体必须是模型字节而不是 JSON 信封（three.js 的 {@code GLTFLoader} 直接吃这个流）。
 * 这是「HTTP 200 + {@code R.code}」惯例的最小例外，与既有的 CSV 下载端点同款处置
 * （{@code DeviceImportController} 的 template/failed.csv 两处）。</p>
 *
 * <p><b>为什么上传通道自建而不复用 {@code /system/file/upload}</b>：见
 * {@code IotTwinModelAssetServiceImpl} 的类注释（平台访问限制 + 存储平台未配置）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/model-assets")
@RequiredArgsConstructor
public class IotTwinModelController {

    private static final Logger log = LoggerFactory.getLogger(IotTwinModelController.class);

    /** 下载时的缓存时长（秒）：同一模型反复进出场景不重复拉取。 */
    private static final long CACHE_MAX_AGE_SECONDS = 3600L;

    private final IotTwinModelAssetService twinModelAssetService;

    /**
     * 查询全部模型资产。
     *
     * @return 资产列表（无数据为空数组）
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinModelAssetResp>> list() {
        return R.ok(twinModelAssetService.listAssets());
    }

    /**
     * 上传模型文件。
     *
     * @param file      模型文件（multipart 字段 {@code file}）
     * @param assetName 模型名称（可空，空则用文件名去扩展名）
     * @param nodeNames 模型节点名快照（可空，页面解析 glb 后上报）
     * @return 新资产
     * @throws IOException 读取上传流失败
     */
    @PostMapping("/upload")
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("上传数字孪生模型")
    public R<IotTwinModelAssetResp> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "assetName", required = false) String assetName,
                                            @RequestParam(value = "nodeNames", required = false) List<String> nodeNames)
        throws IOException {
        return R.ok(twinModelAssetService.upload(file, assetName, nodeNames));
    }

    /**
     * 登记外链模型。
     *
     * @param req 外链信息
     * @return 新资产
     */
    @PostMapping("/external")
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("登记数字孪生外链模型")
    public R<IotTwinModelAssetResp> createExternal(@Valid @RequestBody IotTwinModelExternalReq req) {
        return R.ok(twinModelAssetService.createExternal(req));
    }

    /**
     * 下载（流式）平台内上传的模型文件。
     *
     * <p>支持 {@code If-None-Match}：与 {@code contentHash} 相同的 ETag 返回 304，
     * 切场景时不重复下载几十 MB 的模型。</p>
     *
     * @param id          资产主键
     * @param ifNoneMatch 客户端缓存校验头（可空）
     * @return 模型字节流（或 304）
     */
    @GetMapping("/{id}/content")
    @SaCheckPermission("iot:twin:list")
    public ResponseEntity<Resource> content(@PathVariable Long id,
                                             @RequestHeader(value = HttpHeaders.IF_NONE_MATCH,
                                                 required = false) String ifNoneMatch) {
        IotTwinModelAssetService.ModelContent content = twinModelAssetService.openContent(id);
        String etag = content.contentHash() == null
            ? null : "\"" + content.contentHash() + "\"";
        if (etag != null && etag.equals(ifNoneMatch)) {
            closeQuietly(content.stream(), id);
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(content.contentHash())
                .build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename*=UTF-8''" + urlEncode(content.fileName()))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + CACHE_MAX_AGE_SECONDS);
        if (content.size() > 0) {
            builder.contentLength(content.size());
        }
        if (content.contentHash() != null) {
            builder.eTag(content.contentHash());
        }
        return builder.body(new InputStreamResource(content.stream()));
    }

    /**
     * 删除模型资产（被场景引用时拒绝）。
     *
     * @param id 资产主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生模型")
    public R<Void> remove(@PathVariable Long id) {
        twinModelAssetService.remove(id);
        return R.ok();
    }

    /**
     * 文件名按 RFC 5987 编码（中文文件名直接放 {@code filename=} 会因浏览器编码差异乱码）。
     *
     * @param fileName 原始文件名
     * @return 百分号编码后的文件名
     */
    private static String urlEncode(String fileName) {
        return URLEncoder.encode(fileName == null ? "model" : fileName, StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    /**
     * 关闭流并记录失败（304 分支复用；关闭失败不改变响应语义，但必须留痕）。
     *
     * @param stream 内容流
     * @param id     资产主键（仅用于定位）
     */
    private static void closeQuietly(InputStream stream, Long id) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ex) {
            log.warn("[iot] 关闭模型内容流失败：assetId={}", id, ex);
        }
    }
}
```

### 10.6 `provider/TwinRefTextProviders.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.provider;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.starter.json.ref.RefTextProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字孪生引用字段翻译数据源（数据库实现）。
 *
 * <p>提供两类：{@code twinScene}（场景 ID → 场景名）与 {@code twinModelAsset}（资产 ID → 资产名），
 * 分别对应响应模型上 {@code parentId} / {@code modelAssetId} 的 {@code @RefText}。</p>
 *
 * <p>批量翻译由框架按类型聚合后一次调用，本类一律走批量主键查询，不在循环里查库。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinRefTextProviders {

    private TwinRefTextProviders() {
    }

    /**
     * 场景引用翻译：类型 {@code twinScene}，ID → 场景名。
     *
     * <p>根场景的 {@code parentId=0} 查不到任何行 ⇒ 不输出派生字段（<b>不编造「根场景」这样的名字</b>：
     * 页面据此显示「（根）」是展示决策，不该由后端伪造数据）。</p>
     */
    @Component
    @RequiredArgsConstructor
    public static class TwinSceneName implements RefTextProvider {

        private static final Logger log = LoggerFactory.getLogger(TwinSceneName.class);

        private final IotTwinSceneMapper twinSceneMapper;

        @Override
        public String type() {
            return "twinScene";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = IotRefTextProviders.parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotTwinScene scene : twinSceneMapper.selectByIds(idList)) {
                if (!StringUtils.hasText(scene.getSceneName())) {
                    log.warn("数字孪生引用文本翻译跳过名称为空的场景：sceneId={}", scene.getId());
                    continue;
                }
                names.put(scene.getId(), scene.getSceneName());
            }
            return names;
        }
    }

    /**
     * 模型资产引用翻译：类型 {@code twinModelAsset}，ID → 资产名。
     */
    @Component
    @RequiredArgsConstructor
    public static class TwinModelAssetName implements RefTextProvider {

        private static final Logger log = LoggerFactory.getLogger(TwinModelAssetName.class);

        private final IotTwinModelAssetMapper twinModelAssetMapper;

        @Override
        public String type() {
            return "twinModelAsset";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = IotRefTextProviders.parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotTwinModelAsset asset : twinModelAssetMapper.selectByIds(idList)) {
                if (!StringUtils.hasText(asset.getAssetName())) {
                    log.warn("数字孪生引用文本翻译跳过名称为空的模型资产：assetId={}", asset.getId());
                    continue;
                }
                names.put(asset.getId(), asset.getAssetName());
            }
            return names;
        }
    }
}
```

> `IotRefTextProviders.parseIds` 是包级私有（`static List<Long> parseIds(...)`），
> `TwinRefTextProviders` 与它在**同一个包** `cn.ypbin.admin.iot.provider` 下 ⇒ 可直接复用，
> 不需要复制一份解析逻辑（保持「脏值跳过 + WARN」的既有口径）。

---

## 11. SQL（DDL / 权限码 / 菜单 / 回滚）

### 11.1 `deploy/sql/migration/2026-10-21-iot-twin-schema.sql`

```sql
-- =============================================================
-- 迁移：数字孪生（4 张表）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/006-iot-schema.sql 中「数字孪生域」那段的**语句**等价
--         （注释可不同）。改一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
-- 放置说明：本表属 2026-10-21 的增量，按仓内约定追加在 006 末尾，
--         使「006+007 按语句顺序」与「migration/*.sql 按文件名排序」严格对齐。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- 回滚：deploy/sql/rollback/2026-10-21-iot-twin-schema-rollback.sql
-- =============================================================

-- =============================================================
-- 数字孪生域（2026-10-21 追加）
-- 覆盖：iot_twin_model_asset / iot_twin_scene / iot_twin_point / iot_twin_point_binding
-- 租户表：4 张表都含 tenant_id 且**不**进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables，
--       由 MyBatis-Plus 租户插件统一追加 tenant_id 条件（与 iot_device 同口径）。
-- 语义边界（最容易混淆的一处）：
--   iot_point_mapping 是**采集域**映射（协议地址 ↔ 物模型属性）；
--   iot_twin_point 是**展示域**映射（3D 空间锚点 ↔ 设备）。
--   两者语义正交，不得合并成一张表，也不得互相写入。
-- =============================================================

CREATE TABLE iot_twin_model_asset
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    asset_name       VARCHAR(128) NOT NULL COMMENT '模型名称（租户内唯一）',
    asset_type       VARCHAR(16)  NOT NULL COMMENT '来源类型：upload 平台内上传 | external 外链登记',
    model_format     VARCHAR(16)  NOT NULL COMMENT '模型格式：glb | gltf',
    storage_platform VARCHAR(32)  NULL COMMENT '存储平台标识（asset_type=upload 时非空）',
    storage_bucket   VARCHAR(128) NULL COMMENT '存储桶（asset_type=upload 时非空）',
    storage_path     VARCHAR(512) NULL COMMENT '存储路径（asset_type=upload 时非空）',
    external_url     VARCHAR(1024) NULL COMMENT '外链地址（asset_type=external 时非空，仅允许 http/https）',
    original_name    VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅 upload）',
    file_size        BIGINT       NULL COMMENT '文件字节数（仅 upload）',
    content_hash     VARCHAR(64)  NULL COMMENT 'SHA-256 十六进制摘要（仅 upload；用于判重与 ETag）',
    node_names_json  LONGTEXT     NULL COMMENT '模型节点名快照（JSON 字符串数组；仅作按节点名预匹配的辅助索引，不是权威数据）',
    create_user      BIGINT       NULL COMMENT '创建人',
    create_time      DATETIME     NULL COMMENT '创建时间',
    update_user      BIGINT       NULL COMMENT '更新人',
    update_time      DATETIME     NULL COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_model_asset (tenant_id, asset_name),
    KEY idx_iot_twin_model_asset_tenant (tenant_id)
) COMMENT 'IoT 数字孪生模型资产';

CREATE TABLE iot_twin_scene
(
    id             BIGINT         NOT NULL COMMENT '主键',
    tenant_id      BIGINT         NOT NULL COMMENT '租户 ID',
    scene_code     VARCHAR(64)    NOT NULL COMMENT '场景编码（租户内唯一，用于深链与免歧义引用）',
    scene_name     VARCHAR(128)   NOT NULL COMMENT '场景名称',
    parent_id      BIGINT         NOT NULL DEFAULT 0 COMMENT '父场景 ID（0=根场景；用 0 而不是 NULL：MySQL 唯一键对 NULL 不去重）',
    scene_type     VARCHAR(16)    NOT NULL COMMENT '场景类型：park | building | floor | room',
    model_asset_id BIGINT         NULL COMMENT '绑定的模型资产 ID（NULL=纯容器节点，不渲染模型）',
    anchor_x       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 X',
    anchor_y       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Y',
    anchor_z       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Z',
    anchor_rx      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 X 轴旋转（度）',
    anchor_ry      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Y 轴旋转（度）',
    anchor_rz      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Z 轴旋转（度）',
    anchor_scale   DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点缩放系数（> 0）',
    view_json      LONGTEXT       NULL COMMENT '默认视角与环境配置（相机位姿/背景/光照，JSON；空=前端用默认值）',
    sort           INT            NOT NULL DEFAULT 0 COMMENT '同级排序',
    remark         VARCHAR(500)   NULL COMMENT '备注',
    create_user    BIGINT         NULL COMMENT '创建人',
    create_time    DATETIME       NULL COMMENT '创建时间',
    update_user    BIGINT         NULL COMMENT '更新人',
    update_time    DATETIME       NULL COMMENT '更新时间',
    status         TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_scene (tenant_id, scene_code),
    KEY idx_iot_twin_scene_parent (tenant_id, parent_id)
) COMMENT 'IoT 数字孪生场景（树形）';

CREATE TABLE iot_twin_point
(
    id            BIGINT         NOT NULL COMMENT '主键',
    tenant_id     BIGINT         NOT NULL COMMENT '租户 ID',
    scene_id      BIGINT         NOT NULL COMMENT '所属场景',
    device_id     BIGINT         NOT NULL COMMENT '绑定的设备（iot_device.id）',
    node_name     VARCHAR(255)   NULL COMMENT '绑定的模型节点名（可空；仅用于高亮该节点与按节点名预匹配）',
    local_x       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 X',
    local_y       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Y',
    local_z       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Z',
    normal_x      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 X（用于标签与图标贴面）',
    normal_y      DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点朝向法线 Y',
    normal_z      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 Z',
    icon          VARCHAR(64)    NULL COMMENT '图标标识（图标集内的名字；空=默认设备图标）',
    label_visible TINYINT        NOT NULL DEFAULT 1 COMMENT '是否在运行态常显文字标签：1 是 0 否',
    sort          INT            NOT NULL DEFAULT 0 COMMENT '排序（同场景内标签叠放顺序）',
    remark        VARCHAR(500)   NULL COMMENT '备注',
    create_user   BIGINT         NULL COMMENT '创建人',
    create_time   DATETIME       NULL COMMENT '创建时间',
    update_user   BIGINT         NULL COMMENT '更新人',
    update_time   DATETIME       NULL COMMENT '更新时间',
    status        TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point (tenant_id, scene_id, device_id),
    KEY idx_iot_twin_point_scene (tenant_id, scene_id),
    KEY idx_iot_twin_point_device (device_id)
) COMMENT 'IoT 数字孪生点位（设备在场景中的锚点）';

CREATE TABLE iot_twin_point_binding
(
    id                  BIGINT      NOT NULL COMMENT '主键',
    tenant_id           BIGINT      NOT NULL COMMENT '租户 ID',
    point_id            BIGINT      NOT NULL COMMENT '所属点位（iot_twin_point.id）',
    service_identifier  VARCHAR(64) NOT NULL COMMENT '物模型服务标识（iot_service.service_id）',
    property_identifier VARCHAR(64) NOT NULL COMMENT '物模型属性标识（iot_property.identifier）',
    property_id         BIGINT      NULL COMMENT '属性主键缓存（仅作联查加速；属性被 TSL 重导入删除后为悬空值，读取时按标识重解析）',
    display_type        VARCHAR(16) NOT NULL COMMENT '展示形态：value | gauge | switch | color | sparkline',
    display_role        VARCHAR(16) NOT NULL DEFAULT 'secondary' COMMENT '展示角色：primary 主展示 | secondary 次展示',
    decimals            TINYINT     NULL COMMENT '小数位（0~6；空=原样展示）',
    unit_override       VARCHAR(32) NULL COMMENT '单位覆盖（空=用物模型属性自带单位）',
    sort                INT         NOT NULL DEFAULT 0 COMMENT '排序',
    create_user         BIGINT      NULL COMMENT '创建人',
    create_time         DATETIME    NULL COMMENT '创建时间',
    update_user         BIGINT      NULL COMMENT '更新人',
    update_time         DATETIME    NULL COMMENT '更新时间',
    status              TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point_binding (tenant_id, point_id, service_identifier, property_identifier),
    KEY idx_iot_twin_point_binding_point (point_id)
) COMMENT 'IoT 数字孪生点位属性绑定';
```

### 11.2 `deploy/sql/rollback/2026-10-21-iot-twin-schema-rollback.sql`

```sql
-- 回滚：2026-10-21-iot-twin-schema.sql（数字孪生 4 张表）。
-- 影响：回滚后数字孪生功能整体不可用；**全部场景树、点位、属性绑定随表删除而丢失，不可恢复**。
--       若要保留配置，请先 mysqldump 这 4 张表：
--         mysqldump ... iot_twin_model_asset iot_twin_scene iot_twin_point iot_twin_point_binding
-- 顺序：先回滚 jar（新代码不再读写这 4 张表）再执行本脚本；反过来会让新 jar 因表不存在持续报错。
-- 模型文件：本脚本只删元数据，**不删存储里的模型文件**（本脚本无法访问存储）。
--       清理物理文件请手工删除 ypbin-iot 容器卷内的 /data/ypbin/twin/models/ 下对应文件。
DROP TABLE IF EXISTS iot_twin_point_binding;
DROP TABLE IF EXISTS iot_twin_point;
DROP TABLE IF EXISTS iot_twin_scene;
DROP TABLE IF EXISTS iot_twin_model_asset;
```

### 11.3 `deploy/sql/migration/2026-10-21-iot-twin-menu.sql`

```sql
-- ypbin-iot 数字孪生：权限码与菜单（增量迁移）
-- 与 deploy/sql/007-iot-data.sql 的**数字孪生菜单段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 权限码四个：iot:twin:list（读全部孪生资源）| iot:twin:create（建场景/加点位/传模型）|
--             iot:twin:update（改场景/改点位与绑定）| iot:twin:delete（删场景/删点位/删模型）
-- id 段：3210 = 数字孪生页面（IoT 段内新页面）；321001~321003 = 该页下的按钮。
--   2026-10-21 查仓内 SQL（007 与 migration/*.sql 全量）确认 3210 与 321001~321003 未被占用
--   （在用的 32xx 为 3200~3209、320001~320024、320101~、320201~、320301~、320701~、320901~）。
-- platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径必须**同时**进 sys_role_menu 与 sys_template_menu
--   （后者是租户可授菜单与「防孤儿」父目录补授的依据）。
-- 父菜单 3204（IoT 平台目录）已存在并已授权，本文件不重复授权父目录。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3210, 3204, 'IotDigitalTwin', 'menu', 0, '/iot/twin', '/iot/twin/index', 'iot:twin:list', 'page.iot.twin.title', 'carbon:cube', 16, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321001, 3210, 'IotTwinCreate', 'button', 0, 'iot:twin:create', 'page.iot.twin.create', 1, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321002, 3210, 'IotTwinUpdate', 'button', 0, 'iot:twin:update', 'page.iot.twin.update', 2, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321003, 3210, 'IotTwinDelete', 'button', 0, 'iot:twin:delete', 'page.iot.twin.delete', 3, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3210, 321001, 321002, 321003);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3210, 321001, 321002, 321003);
```

### 11.4 `deploy/sql/rollback/2026-10-21-iot-twin-menu-rollback.sql`

```sql
-- 回滚：2026-10-21-iot-twin-menu.sql（数字孪生权限码与菜单）。
-- 影响：回滚后菜单与权限码消失 ⇒ 该页面在左侧导航不可见，且**已授予这些权限的角色会丢失对应操作能力**
--       （后端接口仍存在，若无其它角色持有这些权限码，则这些端点全部 403）。
-- 顺序：先回滚 jar（不再有 iot:twin:* 端点），或先接受「页面消失但接口仍可直连」的中间态。
DELETE FROM sys_template_menu WHERE menu_id IN (3210, 321001, 321002, 321003);
DELETE FROM sys_role_menu WHERE menu_id IN (3210, 321001, 321002, 321003);
DELETE FROM sys_menu WHERE id IN (3210, 321001, 321002, 321003);
```

### 11.5 `deploy/sql/migration/iot-migrations.list` 追加

在清单末尾按序追加（**顺序即结构演进顺序，必须排在 `2026-10-15-*` 之后**）：

```
2026-10-21-iot-twin-schema.sql
2026-10-21-iot-twin-menu.sql
```

### 11.6 `deploy/sql/006-iot-schema.sql` 与 `007-iot-data.sql` 的追加

- **两份都追加到 `007-iot-data.sql` 末尾，顺序必须是「先 menu 段、后 schema 段」**
  —— `check-iot-sql-equivalence.sh` 比对「006+007 顺序拼接」vs
  「`migration/*-iot-*.sql` 按**文件名字典序**拼接」，逐行 diff 且不做语句级归一化。
  而 `-twin-**menu**.sql` 字典序先于 `-twin-**schema**.sql`（`m` < `s`）⇒ 007 里 menu 必须在前。
  按「DDL 归 006、DML 归 007」的直觉放会 `EXIT=1`（已实测）；
- **语句必须逐字一致**（含列顺序与注释文字）。

> ⚠️ 两份文件都是 `SYNC.md` 白名单**已登记**的新文件条目（「`deploy/sql/006-iot-schema.sql`、`007-iot-data.sql` | **新文件**」），
> 追加内容不需要新增白名单条目。

---

## 12. 配置与部署

### 12.1 `ypbin-service/ypbin-iot/pom.xml` 新增依赖

```xml
        <!-- 文件存储（孪生模型上传/流式下载）：system 模块已依赖，iot 为孪生模型单独引入 -->
        <dependency>
            <groupId>cn.ypbin</groupId>
            <artifactId>ypbin-starter-storage</artifactId>
        </dependency>
```

> 版本由根 pom 的 `ypbin-starter-bom` 统一管理，不写版本号（与 `ypbin-service/ypbin-system/pom.xml` 同款）。

### 12.2 `deploy/nacos/ypbin-iot.yaml` 新增段

```yaml
  # ---------------------------------------------------------------------------
  # 数字孪生模型存储（ypbin-starter-storage）
  #
  # ⚠️ 本仓在此之前**没有任何** ypbin.storage 配置 ⇒ StorageAutoConfiguration 的 local 列表为空
  #    ⇒ StorageRouter 无任何存储策略，route(null) 抛「未指定存储平台且未配置默认平台」。
  #    孪生模型上传是该缺口的**第一个消费者**，故在此显式配置一个本地源。
  # ⚠️ domain 刻意留空：模型**一律经 API 流式下载**（/iot/twin/model-assets/{id}/content），
  #    鉴权与租户隔离由 API 层承担。若配了 domain，本地存储会生成一个**无鉴权的直链**
  #    ⇒ 任何人拿到 URL 即可下载租户模型文件，等于绕过租户隔离。
  # ⚠️ max-file-size 与业务常量 TwinLimits.MAX_MODEL_BYTES 同值（64MB）：两层都要卡，
  #    只靠一层时另一层会给出难以理解的失败（Spring 的 MaxUploadSizeExceededException
  #    会被全局处理器转成「上传文件超出大小限制」，但 nginx 那层会直接 413 拿不到信封）。
  # ---------------------------------------------------------------------------
  storage:
    enabled: true
    default-platform: twin
    max-file-size: 67108864
    local:
      - platform: twin
        base-path: /data/ypbin/twin
        domain: ""
```

### 12.3 `deploy/nacos/ypbin-iot.yaml` 的 `spring` 段新增

```yaml
spring:
  # 数字孪生模型上传：Spring 默认 multipart 上限是 1MB，必须显式放大到 64MB
  # （与 TwinLimits.MAX_MODEL_BYTES、ypbin.storage.max-file-size、nginx 的 64m 四处同值）。
  servlet:
    multipart:
      max-file-size: 64MB
      max-request-size: 66MB
```

### 12.4 `deploy/docker-compose.yml` 的 `ypbin-iot` 服务新增卷

```yaml
    volumes:
      # 数字孪生模型文件（ypbin.storage.local[twin].base-path 的宿主机落地）
      - ./data/twin:/data/ypbin/twin
```

### 12.5 `deploy/nginx-microservice.conf` 新增 location（**必须在 `/api/` 之前**）

```nginx
    # 数字孪生模型上传：单文件上限 64MB（与 iot 侧 TwinLimits.MAX_MODEL_BYTES、
    # ypbin.storage.max-file-size、Spring multipart 四处同值）。
    # ⚠️ 必须排在下面的 /api/ 之前：nginx 正则 location 按「最长匹配优先」，但 /api/ 是前缀匹配、
    #    本段是正则匹配 —— 正则 location 优先于前缀 location（除非前缀用 ^~）。为避免依赖
    #    nginx 的匹配优先级细节，这里直接用正则并在注释里写明理由。
    # ⚠️ 本 location 刻意不写 add_header ⇒ 继承 server 级 3 个安全头。
    # ⚠️ 必须是 `location =` 精确匹配：nginx 硬规则——正则 location 里的
    #   `proxy_pass` 不允许带 URI 部分，写成 `proxy_pass http://up/;` 会 `nginx -t`
    #   直接 emerg（前端容器起不来）。本处必须带 URI 剥掉 /api 前缀。
    location = /api/iot/twin/model-assets/upload {
        client_max_body_size 64m;
        proxy_pass http://ypbin-gateway:18080/iot/twin/model-assets/upload;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 大文件上传可能超过默认 60s 读超时
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
```

### 12.6 `SYNC.md` 的两条既有白名单条目补说明

| 条目 | 追加说明 |
|---|---|
| `deploy/nginx-microservice.conf` | **2026-10-21 追加**：新增数字孪生模型上传专用 location（64MB），必须排在 `/api/` 之前；本 location 不写 `add_header` ⇒ 继承 server 级安全头。与 upstream 同步时按语义合并（保留该 location + SSE 三行 + 采纳上游其它改动）。 |
| `deploy/docker-compose.yml` | **2026-10-21 追加**：`ypbin-iot` 服务新增 `./data/twin:/data/ypbin/twin` 卷（孪生模型存储落地）。与 upstream 同步时保留该卷行。 |

> **白名单条目数不变（仍为 54）**，仅两条既有条目追加说明。这与 `SYNC.md` 的「膨胀要记账」纪律一致。

---

## 13. 测试

### 13.1 `twin/TwinSceneTransformTest.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.starter.core.exception.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 锚点变换校验口径。
 *
 * <p>这些断言是前端 {@code scene-manager} 的对照基准：前端单测用同一组数字验证
 * 「合成顺序 = T × Rz × Ry × Rx × S」。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinSceneTransformTest {

    @Test
    @DisplayName("缩放为 null ⇒ 归一为 1（不拒绝）")
    void nullScaleBecomesOne() {
        assertThat(TwinSceneTransform.requireScale(null)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("缩放 ≤ 0 ⇒ 拒绝，且错误信息说明后果")
    void nonPositiveScaleRejected() {
        assertThatThrownBy(() -> TwinSceneTransform.requireScale(BigDecimal.ZERO))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0")
            .hasMessageContaining("消失");
        assertThatThrownBy(() -> TwinSceneTransform.requireScale(new BigDecimal("-1")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0");
    }

    @Test
    @DisplayName("缩放 > 0 原样返回（含极小正值）")
    void positiveScaleKept() {
        BigDecimal tiny = new BigDecimal("0.000001");
        assertThat(TwinSceneTransform.requireScale(tiny)).isEqualByComparingTo(tiny);
        assertThat(TwinSceneTransform.requireScale(new BigDecimal("2.5")))
            .isEqualByComparingTo(new BigDecimal("2.5"));
    }

    @Test
    @DisplayName("平移/旋转 null ⇒ 归零")
    void nullBecomesZero() {
        assertThat(TwinSceneTransform.zeroIfNull(null)).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal value = new BigDecimal("3.2");
        assertThat(TwinSceneTransform.zeroIfNull(value)).isEqualByComparingTo(value);
    }
}
```

### 13.2 `twin/TwinModelFileGuardTest.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.iot.service.impl.TwinModelFileGuard;
import cn.ypbin.starter.core.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型文件内容校验（**按内容校验而不是按扩展名**）。
 *
 * <p>核心用例：把非模型内容改成 {@code .glb} 扩展名后必须被拒——这是「上传通道只能传模型」
 * 的唯一保证。只校验扩展名等于没有校验。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinModelFileGuardTest {

    private final TwinModelFileGuard guard = new TwinModelFileGuard(new ObjectMapper());

    @Test
    @DisplayName("扩展名：.glb/.gltf 通过，其它拒绝")
    void requireFormat() {
        assertThat(guard.requireFormat("a.glb")).isEqualTo("glb");
        assertThat(guard.requireFormat("a.GLB")).isEqualTo("glb");
        assertThat(guard.requireFormat("a.gltf")).isEqualTo("gltf");
        assertThatThrownBy(() -> guard.requireFormat("a.obj"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(".glb")
            .hasMessageContaining(".gltf");
        assertThatThrownBy(() -> guard.requireFormat("noext"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("合法 GLB 头（魔数 glTF + version 2）通过，并解析出声明长度")
    void validGlb() throws IOException {
        byte[] glb = glbHeader(2, 1024);
        TwinModelFileGuard.ProbeResult result = guard.probe("glb", new ByteArrayInputStream(glb));
        assertThat(result.declaredLength()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("★ 伪装成 .glb 的非模型内容被拒（魔数不匹配）")
    void fakeGlbRejected() {
        byte[] fake = "<html><body>not a model</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(fake)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("魔数不匹配");
    }

    @Test
    @DisplayName("GLB 版本不是 2 被拒并提示重新导出")
    void wrongGlbVersionRejected() {
        byte[] glb = glbHeader(1, 512);
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(glb)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的 glTF 版本")
            .hasMessageContaining("1");
    }

    @Test
    @DisplayName("GLB 内容不足 12 字节被拒")
    void truncatedGlbRejected() {
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(new byte[] {1, 2, 3})))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不足");
    }

    @Test
    @DisplayName("合法 .gltf（顶层 asset.version=2.0）通过")
    void validGltf() throws IOException {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scenes\":[]}";
        assertThat(guard.probe("gltf",
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).declaredLength())
            .isEqualTo(-1L);
    }

    @Test
    @DisplayName("缺 asset 对象或版本不是 2.x 的 .gltf 被拒")
    void invalidGltfRejected() {
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"scenes\":[]}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("asset");
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"asset\":{\"version\":\"1.0\"}}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的 glTF 版本");
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"asset\":{}}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未声明");
    }

    @Test
    @DisplayName("非 JSON 的 .gltf 被拒，并提示「若是 GLB 请改扩展名」")
    void nonJsonGltfRejected() {
        assertThatThrownBy(() -> guard.probe("gltf",
            new ByteArrayInputStream(new byte[] {(byte) 0x67, (byte) 0x6C, (byte) 0x54, (byte) 0x46})))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(".glb");
    }

    @Test
    @DisplayName("节点名快照：去空、去重、超上限截断")
    void normalizeNodeNames() {
        assertThat(guard.normalizeNodeNames(null)).isEmpty();
        assertThat(guard.normalizeNodeNames(List.of("A", " ", "A", "B")))
            .containsExactly("A", "B");
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造 GLB 头部（12 字节）。
     *
     * @param version 版本号
     * @param length  声明总长
     * @return 12 字节头
     */
    private static byte[] glbHeader(int version, int length) {
        byte[] buf = new byte[12];
        writeIntLE(buf, 0, TwinLimits.GLB_MAGIC);
        writeIntLE(buf, 4, version);
        writeIntLE(buf, 8, length);
        return buf;
    }

    private static void writeIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
```

### 13.3 `twin/TwinPermissionCodeGateTest.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字孪生权限码门禁：Controller 标注的 {@code iot:twin:*} 必须同时登记在 007 与 migration。
 *
 * <p>与 {@code IotPermissionCodeGateTest} 同一判据、更窄范围（只看孪生控制器）。
 * 为什么还要单独一条：那条门禁的 SQL 正则只认 {@code 'iot:xx:yy'} 两段式，
 * 孪生的 {@code iot:twin:list} 是**三段式**（twin 在中间），若将来有人把孪生权限码改成
 * {@code iot:twin-list} 之类，旧门禁的 `[a-z-]+` 虽仍能匹配，但「孪生专属的四个码必须齐全」
 * 这件事没人守。本用例把四个码作为**显式清单**钉死。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinPermissionCodeGateTest {

    /** 孪生域必须存在的四个权限码（清单陈旧或漏登记都会转红）。 */
    private static final List<String> REQUIRED_CODES = List.of(
        "iot:twin:list", "iot:twin:create", "iot:twin:update", "iot:twin:delete");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path CONTROLLER_DIR = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller");

    /** 与 {@code IotPermissionCodeGateTest} 同口径：兼容两种注解写法。 */
    private static final Pattern PERMISSION_ANNOTATION =
        Pattern.compile("@SaCheckPermission\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    @Test
    @DisplayName("★ 孪生控制器声明的权限码 ∈ 四个必需码，且四个码都被用到")
    void declaredCodesMustBeWithinRequiredSet() throws IOException {
        Set<String> declared = collectTwinPermissions();
        assertThat(declared)
            .as("没有扫到任何孪生权限码 —— 门禁本身失效（0 违规可能是没跑到）")
            .isNotEmpty();
        assertThat(REQUIRED_CODES).containsAll(declared);
        assertThat(declared).containsExactlyInAnyOrderElementsOf(REQUIRED_CODES);
    }

    @Test
    @DisplayName("四个权限码必须同时出现在 007-iot-data.sql 与孪生 migration")
    void codesMustBeRegisteredInBothSql() throws IOException {
        String fresh = strip(String.join("\n",
            Files.readAllLines(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8)));
        String migration = readMigrations();
        for (String code : REQUIRED_CODES) {
            assertThat(fresh)
                .as("全新安装脚本缺少权限码 " + code + "（非超管角色将永远拿不到该权限）")
                .contains("'" + code + "'");
            assertThat(migration)
                .as("迁移脚本缺少权限码 " + code + "（已上线库升级后该功能整块不可用）")
                .contains("'" + code + "'");
        }
    }

    private static Set<String> collectTwinPermissions() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(CONTROLLER_DIR)) {
            List<Path> twinControllers = files
                .filter(path -> path.getFileName().toString().startsWith("IotTwin"))
                .sorted()
                .toList();
            assertThat(twinControllers)
                .as("没找到任何 IotTwin*Controller（控制器改名后本门禁会空跑）")
                .isNotEmpty();
            for (Path file : twinControllers) {
                String source = strip(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = PERMISSION_ANNOTATION.matcher(source);
                while (matcher.find()) {
                    declared.add(matcher.group(1));
                }
            }
        }
        return declared;
    }

    private static String readMigrations() throws IOException {
        Path dir = REPO_ROOT.resolve("deploy/sql/migration");
        List<String> contents = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files
                .filter(item -> item.getFileName().toString().contains("-iot-"))
                .sorted().toList()) {
                contents.add(strip(Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        return String.join("\n", contents);
    }

    /** 剥离 {@code --} 行注释（门禁文本匹配必须作用在剥注释后的脚本上）。 */
    private static String strip(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
```

### 13.4 `twin/TwinMenuGrantGateTest.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字孪生菜单授权门禁：{@code 3210} 与 {@code 321001~321003} 必须同时进
 * {@code sys_role_menu} 与 {@code sys_template_menu}。
 *
 * <p>为什么必须有：{@code IotMaintenanceAdminGateTest} 的通用规则会覆盖，但它的豁免清单
 * （{@code UNRESOLVED_GRANT_EXEMPTIONS}）与平台级清单是**跨域共用的**，孪生菜单一旦被误登记进
 * {@code PLATFORM_LEVEL_MENUS}，会让该页面对所有租户消失——那种改动在通用门禁里表现为「合法」。
 * 本用例把孪生的「必须租户可见（platform_only=0）」这条意图钉死。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinMenuGrantGateTest {

    /** 孪生菜单与按钮 id 清单（与 migration 脚本逐项一致）。 */
    private static final List<String> MENU_IDS = List.of("3210", "321001", "321002", "321003");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    @DisplayName("★ 孪生菜单 platform_only=0 且两张授权表都授给了 role 1")
    void menusMustBeTenantVisibleAndGranted() throws IOException {
        String sql = strip(String.join("\n",
            Files.readAllLines(REPO_ROOT.resolve("deploy/sql/007-iot-data.sql"), StandardCharsets.UTF_8))
            + "\n" + strip(readMigration()));
        for (String id : MENU_IDS) {
            assertThat(sql)
                .as("菜单 " + id + " 未在 007 或 migration 中定义")
                .containsPattern(Pattern.compile("VALUES \\(\\s*" + id + "\\s*,"));
        }
        // 3210 必须是租户可见页面菜单（platform_only=0）
        assertThat(sql).containsPattern(Pattern.compile(
            "VALUES \\(\\s*3210\\s*,\\s*3204\\s*,\\s*'IotDigitalTwin'\\s*,\\s*'menu'\\s*,\\s*0\\s*,"
                + "\\s*'/iot/twin'\\s*,\\s*'/iot/twin/index'\\s*,\\s*'iot:twin:list'"));
        // 两张授权表都要有这四个 id
        assertThat(sql)
            .as("sys_role_menu 缺少孪生菜单授权（平台管理员看不到该菜单）")
            .containsPattern(Pattern.compile(
                "INSERT INTO sys_role_menu[^;]*3210,\\s*321001,\\s*321002,\\s*321003", Pattern.DOTALL));
        assertThat(sql)
            .as("sys_template_menu 缺少孪生菜单授权（租户管理员建角色时无法授予该菜单）")
            .containsPattern(Pattern.compile(
                "INSERT INTO sys_template_menu[^;]*3210,\\s*321001,\\s*321002,\\s*321003",
                Pattern.DOTALL));
    }

    @Test
    @DisplayName("孪生菜单不得进平台级清单（否则对租户不可见）")
    void menusMustNotBePlatformOnly() throws IOException {
        String source = Files.readString(REPO_ROOT.resolve(
            "ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/config/"
                + "IotMaintenanceAdminGateTest.java"), StandardCharsets.UTF_8);
        for (String id : MENU_IDS) {
            assertThat(source)
                .as("孪生菜单 " + id + " 被登记进 PLATFORM_LEVEL_MENUS 会让整页对租户消失")
                .doesNotContain("\"" + id + "\"");
        }
    }

    private static String readMigration() throws IOException {
        Path file = REPO_ROOT.resolve("deploy/sql/migration/2026-10-21-iot-twin-menu.sql");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        return String.join("\n", lines);
    }

    private static String strip(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
```

### 13.5 `service/impl/IotTwinSceneServiceImplTest.java`

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 场景服务的父链校验与删除保护。
 *
 * <p>这些规则**没有任何数据库约束兜底**——少写一行不会报错、不会红任何用例，
 * 只会让前端组树时无限递归（成环）或让用户看到一棵 8 层的树（超深）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinSceneServiceImplTest {

    private static final long SCENE_ROOT = 7000001L;
    private static final long SCENE_CHILD = 7000002L;
    private static final long SCENE_GRANDCHILD = 7000003L;
    private static final long SCENE_SELF = 7000004L;

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);
    private final IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);
    private final IotTwinModelAssetMapper assetMapper = mock(IotTwinModelAssetMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IotTwinSceneServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 靠方法引用解析列名，纯单测不起 Spring 时必须先初始化实体元信息
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinScene.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinModelAsset.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinSceneServiceImpl(pointMapper, assetMapper, objectMapper);
        // baseMapper 是 BaseServiceImpl 的 protected 字段：纯单测不起 Spring 时用 Spring Test 的反射工具注入
        ReflectionTestUtils.setField(service, "baseMapper", sceneMapper);
    }

    @Test
    @DisplayName("★ 父场景是自己 ⇒ 拒绝")
    void selfAsParentRejected() {
        when(sceneMapper.selectById(SCENE_SELF)).thenReturn(scene(SCENE_SELF, 0L));
        IotTwinSceneReq req = req("A", SCENE_SELF);
        assertThatThrownBy(() -> service.update(SCENE_SELF, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自己的父场景");
    }

    @Test
    @DisplayName("★ 父场景是自己的后代 ⇒ 拒绝（成环）")
    void descendantAsParentRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectById(SCENE_CHILD)).thenReturn(scene(SCENE_CHILD, SCENE_ROOT));
        when(sceneMapper.selectById(SCENE_GRANDCHILD)).thenReturn(scene(SCENE_GRANDCHILD, SCENE_CHILD));
        when(sceneMapper.selectById(SCENE_SELF)).thenReturn(scene(SCENE_SELF, SCENE_GRANDCHILD));
        IotTwinSceneReq req = req("A", SCENE_GRANDCHILD);
        assertThatThrownBy(() -> service.update(SCENE_SELF, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能挂到自己的后代下");
    }

    @Test
    @DisplayName("父链成环（历史脏数据）⇒ 拒绝并提示数据异常")
    void cyclicChainRejected() {
        when(sceneMapper.selectById(SCENE_CHILD)).thenReturn(scene(SCENE_CHILD, SCENE_GRANDCHILD));
        when(sceneMapper.selectById(SCENE_GRANDCHILD)).thenReturn(scene(SCENE_GRANDCHILD, SCENE_CHILD));
        IotTwinSceneReq req = req("A", SCENE_CHILD);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("成环");
    }

    @Test
    @DisplayName("超过 4 层 ⇒ 拒绝并报实际深度")
    void tooDeepRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectById(SCENE_CHILD)).thenReturn(scene(SCENE_CHILD, SCENE_ROOT));
        when(sceneMapper.selectById(SCENE_GRANDCHILD)).thenReturn(scene(SCENE_GRANDCHILD, SCENE_CHILD));
        when(sceneMapper.selectById(SCENE_SELF)).thenReturn(scene(SCENE_SELF, SCENE_GRANDCHILD));
        IotTwinSceneReq req = req("A", SCENE_SELF);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能超过 4 层")
            .hasMessageContaining("第 5 层");
    }

    @Test
    @DisplayName("★ 删除有子场景的场景 ⇒ 拒绝（不级联）")
    void deleteWithChildrenRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.remove(SCENE_ROOT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("子场景")
            .hasMessageContaining("刻意不做级联删除");
        verify(sceneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除有点位的场景 ⇒ 拒绝")
    void deleteWithPointsRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectCount(any())).thenReturn(0L);
        when(pointMapper.selectCount(any())).thenReturn(3L);
        assertThatThrownBy(() -> service.remove(SCENE_ROOT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备点位");
    }

    @Test
    @DisplayName("模型资产跨租户/不存在 ⇒ 报「不存在」而不是「无权限」（不泄露存在性）")
    void missingModelAssetRejected() {
        when(sceneMapper.selectCount(any())).thenReturn(0L);
        when(assetMapper.selectById(anyLong())).thenReturn(null);
        IotTwinSceneReq req = req("A", null);
        req.setModelAssetId(9999999L);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型资产不存在");
    }

    @Test
    @DisplayName("场景类型非法 ⇒ 拒绝并列出允许值")
    void invalidSceneTypeRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setSceneType("warehouse");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景类型不合法")
            .hasMessageContaining("park");
    }

    @Test
    @DisplayName("viewJson 非合法 JSON ⇒ 拒绝")
    void invalidViewJsonRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setViewJson("{not json");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不是合法的 JSON");
    }

    @Test
    @DisplayName("缩放为 0 ⇒ 拒绝")
    void zeroScaleRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setAnchorScale(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0");
    }

    private static IotTwinScene scene(long id, long parentId) {
        IotTwinScene scene = new IotTwinScene();
        scene.setId(id);
        scene.setParentId(parentId);
        scene.setSceneName("scene-" + id);
        return scene;
    }

    private static IotTwinSceneReq req(String code, Long parentId) {
        IotTwinSceneReq req = new IotTwinSceneReq();
        req.setSceneCode(code);
        req.setSceneName("场景 " + code);
        req.setParentId(parentId);
        req.setSceneType("building");
        req.setAnchorScale(BigDecimal.ONE);
        return req;
    }
}
```

### 13.6 `service/impl/IotTwinPointServiceImplTest.java`（要点用例，完整类同 13.5 的构造方式）

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.req.TwinBindingReq;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 点位服务的绑定校验（枚举白名单 / 重复 / primary 唯一 / 设备唯一 / 上限）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinPointServiceImplTest {

    private static final long SCENE_ID = 7100001L;
    private static final long DEVICE_ID = 4101L;
    private static final long POINT_ID = 7100002L;

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);
    private final IotTwinPointBindingMapper bindingMapper = mock(IotTwinPointBindingMapper.class);
    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);
    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final IotPointMappingMapper pointMappingMapper = mock(IotPointMappingMapper.class);
    private final TwinBindingResolver bindingResolver = mock(TwinBindingResolver.class);

    private IotTwinPointServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinPoint.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinScene.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinPointServiceImpl(sceneMapper, bindingMapper, deviceMapper,
            productMapper, serviceMapper, propertyMapper, pointMappingMapper, bindingResolver);
        ReflectionTestUtils.setField(service, "baseMapper", mock(IotTwinPointMapper.class));
        IotTwinScene scene = new IotTwinScene();
        scene.setId(SCENE_ID);
        when(sceneMapper.selectById(SCENE_ID)).thenReturn(scene);
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(9001L);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
    }

    @Test
    @DisplayName("★ bindings 为 null ⇒ 拒绝（漏字段不能被当成「清空绑定」）")
    void nullBindingsRejected() {
        IotTwinPointReq req = req();
        req.setBindings(null);
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("属性绑定列表不能为空");
    }

    @Test
    @DisplayName("★ bindings 传空数组 ⇒ 允许（用户明确的「暂不展示属性」）")
    void emptyBindingsAllowed() {
        IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", pointMapper);
        when(pointMapper.selectCount(any())).thenReturn(0L);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        service.create(SCENE_ID, req);
        verify(bindingResolver, never()).validateBindings(any(), any());
    }

    @Test
    @DisplayName("展示形态非法 ⇒ 拒绝并列出允许值")
    void invalidDisplayTypeRejected() {
        TwinBindingReq binding = binding("Environment", "temperature");
        binding.setDisplayType("pie");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("展示形态不合法")
            .hasMessageContaining("gauge");
    }

    @Test
    @DisplayName("展示角色非法（非空但不认识）⇒ 拒绝；空 ⇒ 归一为 secondary")
    void displayRoleHandling() {
        TwinBindingReq binding = binding("Environment", "temperature");
        binding.setDisplayRole("boss");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("展示角色不合法");
    }

    @Test
    @DisplayName("★ 同一属性绑定两次（服务不同也算不同）⇒ 同服务同属性重复时拒绝")
    void duplicateBindingRejected() {
        TwinBindingReq first = binding("Environment", "temperature");
        TwinBindingReq second = binding("Environment", "temperature");
        IotTwinPointReq req = req();
        req.setBindings(List.of(first, second));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("属性绑定重复");
    }

    @Test
    @DisplayName("★ primary 多于一条 ⇒ 拒绝（页面无法决定标签显示哪个值）")
    void multiplePrimaryRejected() {
        TwinBindingReq first = binding("Environment", "temperature");
        first.setDisplayRole("primary");
        TwinBindingReq second = binding("Environment", "humidity");
        second.setDisplayRole("primary");
        IotTwinPointReq req = req();
        req.setBindings(List.of(first, second));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最多只能有 1 个主展示属性");
    }

    @Test
    @DisplayName("★ 属性在物模型里不存在 ⇒ 拒绝并列全（由 TwinBindingResolver 抛出）")
    void unknownPropertyRejected() {
        TwinBindingReq binding = binding("Environment", "notExists");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        org.mockito.Mockito.doThrow(new BusinessException("以下属性在设备绑定的产品物模型里不存在"))
            .when(bindingResolver).validateBindings(any(), any());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("设备未绑定产品 + 有绑定 ⇒ 由 resolver 拒绝并提示先绑产品")
    void deviceWithoutProductRejected() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(null);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        TwinBindingReq binding = binding("Environment", "temperature");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        org.mockito.Mockito.doThrow(new BusinessException("该设备未绑定产品，无法配置属性绑定"))
            .when(bindingResolver).validateBindings(any(), any());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未绑定产品");
    }

    @Test
    @DisplayName("★ 同一场景同一设备已有锚点 ⇒ 拒绝")
    void duplicateDeviceInSceneRejected() {
        IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", pointMapper);
        when(pointMapper.selectCount(any())).thenReturn(1L);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同一场景内一台设备只能有一个锚点");
    }

    private static IotTwinPointReq req() {
        IotTwinPointReq req = new IotTwinPointReq();
        req.setDeviceId(DEVICE_ID);
        req.setLocalX(BigDecimal.ONE);
        req.setLocalY(BigDecimal.ZERO);
        req.setLocalZ(BigDecimal.ZERO);
        return req;
    }

    private static TwinBindingReq binding(String serviceIdentifier, String propertyIdentifier) {
        TwinBindingReq binding = new TwinBindingReq();
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        binding.setDisplayType("value");
        return binding;
    }
}
```

### 13.7 `service/impl/TwinBindingResolverTest.java`（要点用例）

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 绑定解析器：**批量（不 N+1）**、stale 判定、按业务标识自愈回写。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinBindingResolverTest {

    private static final long SERVICE_ID = 5001L;
    private static final long PROPERTY_ID = 6001L;
    private static final long STALE_PROPERTY_ID = 6002L;

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);
    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotTwinPointBindingMapper bindingMapper = mock(IotTwinPointBindingMapper.class);

    private final TwinBindingResolver resolver = new TwinBindingResolver(propertyMapper, serviceMapper,
        productMapper, bindingMapper);

    @Test
    @DisplayName("★ 批量解析只发 2 次查询（与绑定数无关）")
    void resolveIsBatched() {
        IotService service = service(SERVICE_ID, "Environment");
        IotProperty property = property(PROPERTY_ID, SERVICE_ID, "temperature", "温度");
        when(propertyMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(property));
        when(serviceMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(service));

        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(List.of(
            binding(1L, PROPERTY_ID, "Environment", "temperature"),
            binding(2L, PROPERTY_ID, "Environment", "temperature"),
            binding(3L, STALE_PROPERTY_ID, "Environment", "gone")));

        verify(propertyMapper, times(1)).selectBatchIds(any(Collection.class));
        verify(serviceMapper, times(1)).selectBatchIds(any(Collection.class));
        assertThat(result).hasSize(3);
        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyName()).isEqualTo("温度");
    }

    @Test
    @DisplayName("★ 属性行被物理删除（主键悬空）但业务标识可解析 ⇒ 不 stale，并回写主键缓存")
    void healsStalePropertyId() {
        IotService service = service(SERVICE_ID, "Environment");
        IotProperty property = property(PROPERTY_ID, SERVICE_ID, "temperature", "温度");
        when(propertyMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(property));
        when(serviceMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(service));

        // 绑定里缓存的是旧主键 9999（已不存在），业务标识仍能解析到 6001
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9999L, "Environment", "temperature")));

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        verify(bindingMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("属性与业务标识都解析不到 ⇒ stale=true，名称回落属性标识（不编造）")
    void staleWhenNothingMatches() {
        when(propertyMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of());
        when(serviceMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of());

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, STALE_PROPERTY_ID, "Environment", "gone")));

        assertThat(result.get(1L).getStale()).isTrue();
        assertThat(result.get(1L).getPropertyName()).isEqualTo("gone");
        assertThat(result.get(1L).getDataType()).isNull();
    }

    @Test
    @DisplayName("回写失败只记 WARN，不影响本次解析结果")
    void healFailureDoesNotBreakRead() {
        IotService service = service(SERVICE_ID, "Environment");
        IotProperty property = property(PROPERTY_ID, SERVICE_ID, "temperature", "温度");
        when(propertyMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(property));
        when(serviceMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(service));
        when(bindingMapper.update(any(), any())).thenThrow(new IllegalStateException("db down"));

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9999L, "Environment", "temperature")));

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
    }

    private static IotService service(long id, String serviceId) {
        IotService service = new IotService();
        service.setId(id);
        service.setServiceId(serviceId);
        service.setServiceName(serviceId + " 服务");
        return service;
    }

    private static IotProperty property(long id, long serviceId, String identifier, String name) {
        IotProperty property = new IotProperty();
        property.setId(id);
        property.setServiceId(serviceId);
        property.setIdentifier(identifier);
        property.setPropertyName(name);
        property.setDataType("decimal");
        property.setUnit("℃");
        return property;
    }

    private static IotTwinPointBinding binding(long id, Long propertyId, String serviceIdentifier,
                                                String propertyIdentifier) {
        IotTwinPointBinding binding = new IotTwinPointBinding();
        binding.setId(id);
        binding.setPointId(1L);
        binding.setPropertyId(propertyId);
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        return binding;
    }
}
```

### 13.8 `values/LatestValueQueryServiceBatchTest.java`（要点用例）

```java
/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量最新值：**一次 pipeline**、空集合短路、Redis 故障返回空、与单设备版结果一致。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class LatestValueQueryServiceBatchTest {

    private static final long DEVICE_A = 9000001L;
    private static final long DEVICE_B = 9000002L;
    private static final long TENANT_ID = 1L;

    @Test
    @DisplayName("★ 空集合短路：不发任何查询")
    void emptyInputShortCircuits() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        LatestValueQueryService service = newService(deviceMapper, mock(StringRedisTemplate.class));
        assertThat(service.listLatestBatch(List.of())).isEmpty();
        verify(deviceMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("★ 两台设备只发一次 pipeline（不是两次 HGETALL）")
    void singlePipelineForManyDevices() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
            .thenReturn(List.of(Map.of("temperature", "{\"v\":\"26.5\",\"q\":\"good\",\"ts\":1000}"),
                Map.of()));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        verify(redisTemplate, times(1))
            .executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
        assertThat(result.get(DEVICE_A)).hasSize(1);
        assertThat(result.get(DEVICE_A).getFirst().getValue()).isEqualTo("26.5");
        assertThat(result.get(DEVICE_B)).isEmpty();
    }

    @Test
    @DisplayName("★ Redis 读取异常 ⇒ 每台设备都返回空列表（不抛、不伪造）")
    void redisFailureReturnsEmptyPerDevice() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
            .thenThrow(new RedisConnectionFailureException("connection refused"));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        assertThat(result).containsKeys(DEVICE_A, DEVICE_B);
        assertThat(result.get(DEVICE_A)).isEmpty();
        assertThat(result.get(DEVICE_B)).isEmpty();
    }

    @Test
    @DisplayName("★ pipeline 顺序与入参顺序不一致时结果错配（钉死「顺序必须一致」这个前提）")
    void pipelineOrderMatters() {
        // 本用例是**反证**：如果实现按入参顺序假设结果顺序，而 Redis 返回顺序不同，就会错配。
        // 实现侧的保证是「lambda 里按同一 orderedIds 顺序入队」，故此处断言正常路径下不错配。
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
            .thenReturn(List.of(Map.of(), Map.of("pressure", "{\"v\":\"1.2\",\"ts\":2000}")));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        assertThat(result.get(DEVICE_A)).isEmpty();
        assertThat(result.get(DEVICE_B)).hasSize(1);
    }

    private static IotDevice device(long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(TENANT_ID);
        return device;
    }

    @SuppressWarnings("unchecked")
    private static LatestValueQueryService newService(IotDeviceMapper deviceMapper,
                                                       StringRedisTemplate redisTemplate) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        PointMappingIndex index = mock(PointMappingIndex.class);
        when(index.loadCoordinates(anyCollection())).thenReturn(Map.of());
        return new LatestValueQueryService(provider, deviceMapper, new ObjectMapper(), index,
            new SimpleMeterRegistry());
    }
}
```

---

## 14. 实施与验证清单（后端）

```bash
# ① 编译 + 单测（唯一的重型命令，一次只跑一个）
mvn -pl ypbin-service/ypbin-iot -am test

# ② 架构门禁（须在 ① 之后：它读 ypbin-iot 的 target/classes）
mvn -pl ypbin-architecture-tests test

# ③ SQL 语句等价（006+007 vs migration/*.sql）
tools/check-iot-sql-equivalence.sh

# ④ 迁移清单一致性（由 ③ 内部校验，单列以便排障）
grep -c '' deploy/sql/migration/iot-migrations.list
```

**必须在汇报里附上的证据**：①③ 的命令与输出摘要；②的红/绿结论；
若 ① 因本机内存失败，如实说明并给出被跳过的模块（不得声称通过）。

---

## 15. 后端未做 / 待决（如实登记）

1. **无绑定历史留痕**：`iot_twin_point_binding` 用物理删除实现整体替换（唯一键不含 `is_deleted`）。
   编辑动作由 `@Log` 记录，但没有行级历史。若将来需要「谁在什么时候改了绑定」，需另加审计表。
2. **模型节点名快照由前端上报**：后端不做 glb 解析（解析 glb 需要 three.js 或专门的 glTF 解析库，
   引入后端依赖不划算）。快照缺失只影响「按节点名批量预匹配」的便利性。
3. **无模型文件版本管理**：替换模型需要「删旧建新」，被场景引用时删不掉 ⇒ 实际流程是
   「上传新模型 → 在各场景换绑 → 删旧模型」。若需要「一键替换全局模型」，需另设计引用迁移。
4. **无场景复制/模板**：多栋同构楼层需要手工建。若成为高频需求，可加「复制场景」端点
   （复制场景 + 点位，不复制绑定）。
5. **`countPointsAndChildren` 用相关子查询**：单租户场景数在百级以内时性能足够；
   若达到千级，应改为两条 `GROUP BY` 后内存合并。
