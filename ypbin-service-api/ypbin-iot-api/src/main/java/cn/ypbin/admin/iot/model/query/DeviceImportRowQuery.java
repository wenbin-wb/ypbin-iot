/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.query;

import cn.ypbin.starter.crud.model.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备批量导入明细查询条件（批次详情里的明细分页）。
 *
 * <p>只暴露一个筛选维度：**结果**（成功/失败）。刻意不做「按错误码筛选」「按关键字搜设备编码」
 * 这类看起来贴心但没人用的维度——用户拿到失败结果的下一步永远是「把失败行下载走」，
 * 而不是「在页面上翻失败行」。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceImportRowQuery extends PageQuery {

    /**
     * 结果筛选：{@code DeviceImportRowResult} 的 code（success/failed）。
     *
     * <p>非法取值由服务层报错而不是静默忽略：静默忽略会让用户以为「筛过了」，
     * 实际看到的却是全部行。</p>
     */
    private String rowResult;
}
