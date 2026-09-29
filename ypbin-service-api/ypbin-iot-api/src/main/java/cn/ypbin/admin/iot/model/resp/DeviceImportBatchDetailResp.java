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

import cn.ypbin.admin.iot.model.query.DeviceImportRowQuery;
import cn.ypbin.starter.crud.model.PageResult;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备批量导入**批次详情**响应（批次头 + 该批次某一页明细）。
 *
 * <p><b>为什么把明细嵌在详情里而不是另开一个端点</b>：页面永远是「先要批次头（成功/失败数、
 * 状态），再要第一页明细」；分成两个请求会让首屏出现「头已经显示了、明细还是空的」的中间态，
 * 而这两个数据的**一致性**恰恰是用户关注的（表头说失败 3 行，明细必须能筛出这 3 行）。
 * 一个请求返回一份快照就没有这个问题。</p>
 *
 * <p>明细分页复用仓内既有 {@link PageResult}（`total/page/pageSize` 是 long ⇒ 全局序列化成字符串，
 * 前端统一过 {@code toBackendNumber}，口径与设备列表一致）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceImportBatchDetailResp {

    /** 批次头信息。 */
    private DeviceImportBatchResp batch;

    /** 明细分页（按行号升序；可按结果筛选，筛选条件见 {@link DeviceImportRowQuery}）。 */
    private PageResult<DeviceImportRowResp> rows;
}
