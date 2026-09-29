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
 * IoT 设备批量导入**逐行明细**响应。
 *
 * <p>字段与实体/DB 同名。页面三处消费它：明细表格、「只看失败」筛选、
 * 以及失败行下载（下载走后端端点，不靠前端拼——前端拼会在分页场景下丢掉没加载到的行）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
public class DeviceImportRowResp {

    /** 明细主键。 */
    private Long id;

    /** 行号（数据行从 1 开始）。 */
    private Integer rowNo;

    /** 原始行内容。 */
    private String rawLine;

    /** 结果码（{@code DeviceImportRowResult} 的 code）。 */
    private String rowResult;

    /** 错误码（成功行为 null）。 */
    private String errorCode;

    /** 面向用户的错误信息（成功行为 null）。 */
    private String errorMessage;

    /** 成功创建出的设备 ID（失败行为 null）。 */
    private Long deviceId;

    /** 设备编码。 */
    private String deviceCode;
}
