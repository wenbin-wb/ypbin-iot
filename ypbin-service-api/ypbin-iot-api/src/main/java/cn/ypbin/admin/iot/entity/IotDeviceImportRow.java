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
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备批量导入**逐行明细**（一个批次下的每一行数据的结果）。
 *
 * <p><b>为什么逐行落库</b>：批量导入最常见的用户诉求不是「成功了多少」而是
 * <b>「哪几行没进去、为什么、怎么改」</b>。把失败行原样存下来，才能做到
 * 「一键下载失败行 CSV → 改完直接重传」这个闭环（对标阿里云「非法列表下载重传」）。</p>
 *
 * <p><b>{@code rawLine} 的长度是一个必须显式处理的点</b>：CSV 单行最长可达
 * {@code DeviceImportLimits.MAX_LINE_CHARS}（列宽之和 × 列数，远大于任何单列宽度），
 * 用 {@code VARCHAR(500)} 会在用户传了一个超长备注时把「落库报错」变成一次
 * <b>整个导入失败</b>——而那一行本来只是「某一列太长」这一条业务错误。
 * 因此这里用 {@code TEXT}（64KB）并在解析层就把单行长度卡在上限内。</p>
 *
 * <p><b>为什么不存 {@code deviceId} 作为外键约束</b>：批次明细是**审计留痕**，
 * 设备后续被删除后这条记录仍然要能解释「当时发生了什么事」；存 ID 值而不加外键正是这个意思。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
@TableName("iot_device_import_row")
public class IotDeviceImportRow extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属批次 ID（{@code iot_device_import_batch.id}）。 */
    private Long batchId;

    /**
     * 行号（**数据行从 1 开始**，不含表头与说明行）。
     *
     * <p>刻意用「数据行序号」而不是「文件物理行号」：用户拿到的失败行 CSV 与模板同构，
     * 里面的行号直接对应「第几台设备」，比「文件第 27 行」好定位。</p>
     */
    private Integer rowNo;

    /** 原始行内容（原样存，便于用户对照；超长时由解析层先行拒绝，不会撑爆本列）。 */
    private String rawLine;

    /** 结果码（{@link cn.ypbin.admin.iot.enums.DeviceImportRowResult} 的 {@code code}）。 */
    private String rowResult;

    /**
     * 错误码（{@link cn.ypbin.admin.iot.enums.DeviceImportErrorCode} 的 {@code code}）。
     *
     * <p>成功行为 {@code null}；失败行必有错误码（页面与失败行 CSV 都按码分类统计）。</p>
     */
    private String errorCode;

    /** 面向用户的错误信息（含具体是哪一列、哪个取值，可直接照改）。 */
    private String errorMessage;

    /** 成功创建出的设备 ID；失败行为 {@code null}。 */
    private Long deviceId;

    /** 该行的设备编码（成功/失败都存：失败行要靠它让用户认出是哪台设备）。 */
    private String deviceCode;
}
