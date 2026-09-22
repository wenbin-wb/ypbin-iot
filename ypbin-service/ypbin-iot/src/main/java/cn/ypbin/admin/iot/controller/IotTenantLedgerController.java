/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.model.req.TenantLedgerAssignableReq;
import cn.ypbin.admin.iot.model.resp.TenantLedgerResp;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户台账运维端点（M-2 / P5）：把「哪些租户可被接入」从改配置重启变成可运维的数据。
 *
 * <p><b>为什么是平台级接口</b>：台账决定 access 节点去采集<b>哪些租户</b>，跨租户生效。
 * 因此权限码 {@code iot:ledger:*} 只授给平台管理员角色，**不**进 {@code sys_template_menu}
 * （否则任一租户管理员都能改别人的租户是否被采集）。</p>
 *
 * <p>写操作会推进 {@code config_epoch}：接入侧据此在下个租约周期重新对账设备清单
 * （撤销可分配<b>不会</b>回收已分配的租约，那是租约回收路径的职责，见 ROADMAP 的 P7 登记）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@RestController
@RequestMapping("/tenant-ledger")
@RequiredArgsConstructor
public class IotTenantLedgerController {

    private final TenantLedgerService tenantLedgerService;

    /**
     * 台账全量（谁可被分配、版本号到哪了）。
     *
     * @return 台账行
     */
    @GetMapping
    @SaCheckPermission("iot:ledger:list")
    public R<List<TenantLedgerResp>> list() {
        return R.ok(tenantLedgerService.listAll());
    }

    /**
     * 设置某租户是否可被分配。
     *
     * @param tenantId 租户 ID
     * @param req      可分配标记
     * @return 落库后的台账状态（含变更类型与最新版本号）
     */
    @PutMapping("/{tenantId}/assignable")
    @SaCheckPermission("iot:ledger:update")
    @Idempotent
    @Log("修改 IoT 租户台账可分配标记")
    public R<TenantLedgerResp> setAssignable(@PathVariable Long tenantId,
                                             @Valid @RequestBody TenantLedgerAssignableReq req) {
        return R.ok(tenantLedgerService.setAssignableAndGet(tenantId,
            Boolean.TRUE.equals(req.getAssignable())));
    }
}
