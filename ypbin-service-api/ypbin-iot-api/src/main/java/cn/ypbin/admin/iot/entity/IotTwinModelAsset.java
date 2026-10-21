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
 * <p>{@code nodeNamesJson} 是**辅助索引不是权威数据**：它由页面解析模型后上报，仅用于
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

    /** SHA-256 十六进制摘要（仅 upload；用于判重与 ETag）。 */
    private String contentHash;

    /** 模型节点名快照 JSON（字符串数组；仅作预匹配辅助）。 */
    private String nodeNamesJson;
}
