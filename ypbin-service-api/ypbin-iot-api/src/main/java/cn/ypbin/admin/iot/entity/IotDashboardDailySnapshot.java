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
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 仪表盘每日在线快照（在线趋势的历史数据源）。
 *
 * <p>每租户每天一行：唯一键 {@code uk_iot_dashboard_daily_snapshot(tenant_id, snapshot_date)} 保证
 * 「当天只有一条」。总览请求先落当天快照（已有则更新计数），近 14 天趋势直接读这张表；没有快照的
 * 历史日期不补造、不插值。</p>
 *
 * <p>继承 {@link TenantBaseEntity} ⇒ 落库自动带 {@code tenant_id}，查询由租户插件自动追加条件，
 * 业务代码不手写租户过滤。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
@TableName("iot_dashboard_daily_snapshot")
public class IotDashboardDailySnapshot extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 快照日期（租户内每天一条）。 */
    private LocalDate snapshotDate;

    /** 快照时刻的设备总数（含停用设备）。 */
    private Integer deviceTotal;

    /** 快照时刻的在线设备数（{@code online_status = online}）。 */
    private Integer deviceOnline;

    /** 快照时刻的离线设备数（{@code online_status = offline}）。 */
    private Integer deviceOffline;

    /** 快照时刻的停用（禁用采集）设备数（{@code status = 0}）。 */
    private Integer deviceDisabled;
}
