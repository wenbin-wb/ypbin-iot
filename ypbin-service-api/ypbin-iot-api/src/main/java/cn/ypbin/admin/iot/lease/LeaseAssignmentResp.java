/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import java.time.LocalDateTime;

/**
 * 租户接入台账的**租约归属行**（平台运维页用：谁在采哪个租户、什么时候到期）。
 *
 * <p>为什么单列而不是塞进 {@link cn.ypbin.admin.iot.model.resp.TenantLedgerResp}：台账回答
 * 「哪些租户可被分配」，归属回答「现由哪个节点在采、租约什么时候到期」——两者生命周期不同
 * （一个总是可查询，一个可能为空行），混在一个返回里会让前端到处判空。</p>
 *
 * @param tenantId    租户 ID
 * @param accessNode  采集该租户的接入节点标识（**未分配时为 `null`**）
 * @param state       租约状态（`active` / `pending_takeover` / `released`）
 * @param epoch       归属版本号（归属每次转移 +1）
 * @param leaseExpireAt 租约到期时刻（到点未续约 ⇒ 可能被别的节点接管）
 * @param updateTime  最近一次归属变更时刻
 * @author wenbin
 * @since 2026-10-11
 */
public record LeaseAssignmentResp(
    Long tenantId,
    String accessNode,
    String state,
    Long epoch,
    LocalDateTime leaseExpireAt,
    LocalDateTime updateTime) {
}
