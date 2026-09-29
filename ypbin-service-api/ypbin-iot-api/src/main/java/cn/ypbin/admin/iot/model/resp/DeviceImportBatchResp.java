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

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备批量导入**批次**响应。
 *
 * <p>字段与实体/DB 同名（不做改名映射）。计数直接回传库里的列而不是当场重算：
 * 页面的进度、成功/失败数与失败行明细必须**同源**，否则会出现「表头说失败 3 行、
 * 明细筛出来 4 行」这种用户绝对会追问的不一致。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceImportBatchResp {

    /** 批次主键。 */
    private Long id;

    /** 原始文件名。 */
    private String fileName;

    /** 数据行总数。 */
    private Integer totalRows;

    /** 成功行数。 */
    private Integer successRows;

    /** 失败行数。 */
    private Integer failedRows;

    /** 批次状态码（{@code DeviceImportStatus} 的 code）。 */
    private String batchStatus;

    /** 错误摘要（面向用户；全部成功时为 null）。 */
    private String errorSummary;

    /** 开始时刻。 */
    private LocalDateTime startTime;

    /** 结束时刻（进行中为 null）。 */
    private LocalDateTime endTime;

    /** 操作人用户 ID。 */
    private Long operatorUserId;

    /** 创建时刻。 */
    private LocalDateTime createTime;
}
