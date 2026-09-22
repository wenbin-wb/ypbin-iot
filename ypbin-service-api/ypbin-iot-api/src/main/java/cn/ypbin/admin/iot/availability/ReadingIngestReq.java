/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 读数上报请求（access → iot 内部接口，M-2）。
 *
 * <p>有界：单次最多 {@code MAX_BATCH_SIZE} 条，超出由调用方分批——避免一个超大请求把服务端内存与
 * 事务拖垮（access 侧本就有界队列 + 微批）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
public class ReadingIngestReq {

    /** 单批上限（与 access 侧微批大小同量级）。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 读数观察清单（非空；元素逐条校验）。 */
    @NotEmpty(message = "读数清单不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单批读数不能超过 " + MAX_BATCH_SIZE + " 条")
    @Valid
    private List<ReadingObservationDto> items = new ArrayList<>();

    /**
     * 读数清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<ReadingObservationDto> getItems() {
        return items == null ? List.of() : items;
    }
}
