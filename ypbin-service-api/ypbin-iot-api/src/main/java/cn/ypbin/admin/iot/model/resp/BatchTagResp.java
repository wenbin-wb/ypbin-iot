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
 * 设备标签批量操作结果。
 *
 * <p>打标签按「该设备该键是否已有行」分别计入 {@link #created}/{@link #updated}；移除只填
 * {@link #removed}。三个字段恒有值（未涉及的场景为 0），调用方无需判 {@code null}。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class BatchTagResp {

    /** 本次新增的标签行数。 */
    private Integer created;

    /** 本次更新值的已有标签行数。 */
    private Integer updated;

    /** 本次逻辑删除的标签行数。 */
    private Integer removed;
}
