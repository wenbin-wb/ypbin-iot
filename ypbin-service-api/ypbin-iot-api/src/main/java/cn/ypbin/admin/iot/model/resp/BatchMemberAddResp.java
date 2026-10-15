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

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 分组批量加入设备的结果。
 *
 * <p>已存在的成员按幂等命中计入 {@link #skipped}，不重复插入、也不当失败；缺失设备则**整批拒绝**，
 * 由 {@code BusinessException} 的错误信息一次性列出全部缺失 ID（不静默、不部分写入）。因此正常
 * 成功响应中 {@link #missingDeviceIds} 恒为空列表。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class BatchMemberAddResp {

    /** 本次真正新增的成员数。 */
    private Integer added;

    /** 因已在分组中而跳过的设备数（幂等命中，不含缺失设备）。 */
    private Integer skipped;

    /**
     * 缺失设备 ID 列表。
     *
     * <p>契约显式字段：存在缺失设备时本接口整体拒绝，错误信息会列出全部缺失 ID；成功时为空列表。
     * 调用方可直接遍历该字段，无需判断 {@code null}。</p>
     */
    private List<Long> missingDeviceIds = List.of();
}
