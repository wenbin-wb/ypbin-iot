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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 物模型版本（§3.8）。
 *
 * <p>状态机 {@code draft → published}；发布时递增语义化版本 {@code v{major}.{minor}}；
 * 已发布版本同版本<b>不可变</b>，变更走新版本。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_product_version")
public class IotProductVersion extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属产品 ID。 */
    private Long productId;

    /** 版本号（语义化 v1.0）。 */
    private String versionNo;

    /** 版本状态：draft | published。 */
    private String modelStatus;

    /** 发布时间。 */
    private LocalDateTime publishedAt;

    /** 备注。 */
    private String remark;
}
