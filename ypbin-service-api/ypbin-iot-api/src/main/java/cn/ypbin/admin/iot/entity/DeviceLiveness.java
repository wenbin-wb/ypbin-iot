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
 * 设备活性状态（M-2 断档判定的状态机）：每台设备一行。
 *
 * <p><b>为什么需要它</b>：断档的定义是「连续 &gt; K × 采集周期 无有效数据」，而**静默设备**（不再上报）
 * 不会带来任何请求——只靠上报事件永远发现不了它。所以把「最近有效数据时刻」落库，由周期扫描按时间判定；
 * 落库同时让判定在服务重启、多副本下不丢状态（内存态一重启就会把长断档判错）。</p>
 *
 * <p>租户表：含 {@code tenant_id} 且**不**进 {@code ypbin.tenant.ignore-tables}，由租户插件统一追加条件。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
@TableName("device_liveness")
public class DeviceLiveness extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID（iot_device.id）。 */
    private Long deviceId;

    /** 采集周期（毫秒；0=未提供，按配置的兜底周期判定）。 */
    private Integer pollIntervalMs;

    /** 最近一次有效数据（quality=GOOD）的时刻；{@code null}=从未有过有效数据。 */
    private LocalDateTime lastGoodAt;

    /** 首次收到任何读数的时刻（从未有有效数据时断档起点的兜底）。 */
    private LocalDateTime firstObservedAt;

    /** 最近一次收到任何读数的时刻。 */
    private LocalDateTime lastObservedAt;

    /** 进行中的断档事件 ID；{@code null}=当前无断档。 */
    private Long openOutageId;
}
