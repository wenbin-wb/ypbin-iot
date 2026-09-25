/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行期事件上报请求（access → iot 内部接口，G6）。
 *
 * <p>有界：单次最多 {@link #MAX_BATCH_SIZE} 条，超出由调用方分批——避免一个超大请求把服务端内存与
 * 事务拖垮（与读数上报同一口径）。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
public class EventIngestReq {

    /** 单批上限（与读数上报同量级）。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 事件清单（非空；元素逐条校验）。 */
    @NotEmpty(message = "事件清单不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单批事件不能超过 " + MAX_BATCH_SIZE + " 条")
    @Valid
    private List<EventIngestItemDto> items = new ArrayList<>();

    /**
     * 事件清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<EventIngestItemDto> getItems() {
        return items == null ? List.of() : items;
    }
}
