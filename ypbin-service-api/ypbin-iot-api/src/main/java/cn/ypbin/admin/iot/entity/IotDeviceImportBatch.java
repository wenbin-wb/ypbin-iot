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
 * IoT 设备批量导入**批次**（CSV 批量注册的批次台账）。
 *
 * <p><b>为什么要批次表</b>：单条创建设备在量产/迁移场景不可用（一次要进几百上千台），
 * 而「一次性批量写库」的模式有三个致命问题：① 失败后无从知道哪几行没进去；
 * ② 用户拿到一句「导入失败」不知道下一步做什么；③ 同上一个文件重传两次会静默产生重复设备。
 * 批次表把「一次上传」变成一个**可查询、可下载失败行、可对账**的对象。</p>
 *
 * <p>字段与 DB 列**同名**（不做改名映射），计数与状态都由服务端在同一事务里算出并落库，
 * 页面直接读这些列而不再现算——避免「页面算出来成功 8 条、库里实际 7 条」这类对账分歧。</p>
 *
 * <p>继承 {@link TenantBaseEntity} ⇒ 落库自动带 {@code tenant_id}，查询由租户插件统一加条件
 * （不在业务代码里手写租户过滤，见多租户安全底线）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Getter
@Setter
@TableName("iot_device_import_batch")
public class IotDeviceImportBatch extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 上传的原始文件名（原样存，便于用户认出自己传的是哪个文件）。 */
    private String fileName;

    /** CSV 数据行总数（不含表头与说明行；等于成功数 + 失败数，落库时断言一致）。 */
    private Integer totalRows;

    /** 成功创建的行数。 */
    private Integer successRows;

    /** 失败的行数。 */
    private Integer failedRows;

    /**
     * 批次状态码（{@link cn.ypbin.admin.iot.enums.DeviceImportStatus} 的 {@code code}）。
     *
     * <p>落库为字符串枚举码而非 ordinal：加状态不改已有语义，且读库排障时一眼可懂。</p>
     */
    private String batchStatus;

    /**
     * 错误摘要（面向用户的一句话）。
     *
     * <p>成功批次为 {@code null}；部分失败/整体失败时给出「失败 N 行，最常见原因：xxx」这类
     * 可直接照着改的说明，而不是「导入失败」四个字。</p>
     */
    private String errorSummary;

    /** 导入开始时刻（服务端时钟；与结束时刻一起用于「这批跑了多久」）。 */
    private LocalDateTime startTime;

    /** 导入结束时刻；{@code null} 表示仍在进行中。 */
    private LocalDateTime endTime;

    /** 操作人用户 ID（审计：谁传的这批）。 */
    private Long operatorUserId;
}
