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
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行态单个点位的快照。
 *
 * <p>{@code values} 的键是<b>属性标识</b>（与最新值的规范坐标一致）。值为 null 或不存在的键
 * 都表示「没有最新值」，两者的区别只在诊断时有用（不存在=从未上报过；null=上报过但值为空）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinRuntimePointResp {

    /** 点位主键。 */
    private Long pointId;

    /** 设备主键。 */
    private Long deviceId;

    /** 设备编码。 */
    private String deviceCode;

    /** 设备名称。 */
    private String deviceName;

    /** 在线状态：online | offline | unknown。 */
    private String onlineStatus;

    /** 启停位：1 启用 0 停用。 */
    private Integer status;

    /** 最后上报时刻。 */
    private LocalDateTime lastSeenAt;

    /** 活动告警数。 */
    private Integer alertCount;

    /** 各属性最新值（键=属性标识；无数据时为空 Map，永不为 null）。 */
    private Map<String, TwinRuntimeValueResp> values;
}
