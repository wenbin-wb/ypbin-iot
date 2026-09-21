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

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备影子写入请求（§3.10，写 desired）。
 *
 * <p>影子只写最新值；键为物模型属性标识，值为属性类型对应的值。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotShadowReq {

    /** 设备 ID（新增时必填；更新时可空）。 */
    private Long deviceId;

    /** 期望值（键=属性标识，值=属性类型对应值）。 */
    @NotNull(message = "期望值不能为空")
    private Map<String, Object> desired;
}
