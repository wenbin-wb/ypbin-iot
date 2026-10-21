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
 * 模型资产重命名请求。
 *
 * <p><b>只改名称</b>：格式、存储路径、内容哈希、节点名快照都描述「文件本身」，
 * 改名不该动它们（否则会出现「名字与内容不符」的语义漂移）。</p>
 *
 * <p><b>为什么用独立端点而不是复用上传/外链登记的 PUT</b>：
 * 那两者的请求体都携带「文件/地址」这类必填语义，改成「全字段 PUT」会让
 * 「只想改名」的调用方不得不回传存储信息（既泄露内部字段，也容易误改）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinModelRenameReq {

    /**
     * 新模型名称（租户内唯一）。
     *
     * <p>唯一性由 DDL 的 {@code uk_iot_twin_model_asset (tenant_id, asset_name)} 保证，
     * 服务侧另做一次前置查重以便给出可读文案（而不是把 {@code DuplicateKeyException}
     * 抛给用户）。</p>
     */
    @NotBlank(message = "模型名称不能为空")
    @Size(max = TwinLimits.MAX_ASSET_NAME_LENGTH, message = "模型名称长度不能超过 128")
    private String assetName;
}
