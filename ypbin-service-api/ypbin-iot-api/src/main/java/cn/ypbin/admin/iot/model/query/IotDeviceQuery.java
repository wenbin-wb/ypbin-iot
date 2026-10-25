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
 * IoT 设备分页查询条件。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class IotDeviceQuery extends PageQuery {

    /** 设备名称/编码模糊匹配（可空）。 */
    private String keyword;

    /** 协议过滤（可空）。 */
    private String protocol;

    /** 绑定产品 ID 过滤（可空；按产品筛选该产品下的设备）。 */
    private Long productId;

    /**
     * 空间筛选（可空）：按空间**含全部后代**筛选设备。
     *
     * <p><b>为什么含后代</b>：设备通常挂在末级房间（A栋/1F/101室）。用户选「A栋」时
     * 期望看到其下所有设备；只匹配 `space_id = A栋` 会得到空列表。</p>
     *
     * <p>实现方式：调 system 的 {@code /space-subtree-ids} 取子树，再用其 targetIds
     * 反查本表——IoT 不持有空间表。</p>
     */
    private Long spaceId;
}
