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

import cn.ypbin.admin.iot.entity.TenantLedger;
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.admin.iot.model.resp.TenantLedgerResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户台账服务（M0b-2 分配来源 + M0b-3 配置版本号）。
 *
 * <p><b>为什么需要写入口</b>：台账的「可分配」此前只能靠改配置重启生效。有了写入口，运维/业务侧可以动态
 * 增删可分配租户，且每次变更都会推进 {@code config_epoch}——接入侧据此做「不一致才拉全量」的对账。</p>
 *
 * <p><b>复活而非插入</b>：{@code uk_tenant_ledger(tenant_id)} 不包含逻辑删除标记，而实体是逻辑删除。
 * 因此本类必须先查（含已删除行）再决定 insert 还是「复活 + 递增」；否则对同一个租户第二次置为可分配时
 * 会直接撞唯一键（M-1 的 {@code iot_service} 踩过同类问题）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Service
public class TenantLedgerService {

    private static final Logger log = LoggerFactory.getLogger(TenantLedgerService.class);

    /** 配置版本号起点（新建台账行时为 1）。 */
    private static final long INITIAL_CONFIG_EPOCH = 1L;

    private final TenantLedgerMapper ledgerMapper;

    public TenantLedgerService(TenantLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 设置某租户是否可被分配（台账变更与版本号递增在同一事务内）。
     *
     * @param tenantId   租户 ID
     * @param assignable 是否可分配
     * @return 本次是「新建/复活/更新」中的哪一种（供调用方记审计）
     */
    @Transactional(rollbackFor = Exception.class)
    public LedgerChange setAssignable(Long tenantId, boolean assignable) {
        return doSetAssignable(tenantId, assignable);
    }

    /**
     * 设置某租户是否可被分配，并**回读**落库后的真实状态（运维端点用它一次拿到结果与版本号）。
     *
     * <p>回读是刻意的：返回值必须来自数据库，而不是「我刚写进去的意图」——
     * 「声称改了、产物没落地」在本仓有历史教训。</p>
     *
     * @param tenantId   租户 ID
     * @param assignable 是否可分配
     * @return 落库后的台账状态（含本次变更类型与最新版本号）
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantLedgerResp setAssignableAndGet(Long tenantId, boolean assignable) {
        LedgerChange change = doSetAssignable(tenantId, assignable);
        TenantLedger row = ledgerMapper.selectIncludingDeleted(tenantId);
        if (row == null) {
            // 刚写完却读不到：属异常状态（并发物理删除等），暴露而不是返回 null/半份状态
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "台账写入后回读失败（并发冲突？）：" + tenantId);
        }
        TenantLedgerResp resp = toResp(row);
        resp.setChange(change.getCode());
        return resp;
    }

    /**
     * 台账「可分配」写入实现（公开入口共用；直接调用它可避免类内自调用绕过事务代理）。
     *
     * @param tenantId   租户 ID
     * @param assignable 是否可分配
     * @return 本次是「新建/复活/更新」中的哪一种
     */
    private LedgerChange doSetAssignable(Long tenantId, boolean assignable) {
        if (tenantId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "租户 ID 不能为空");
        }
        TenantLedger existing = ledgerMapper.selectIncludingDeleted(tenantId);
        if (existing == null) {
            TenantLedger row = new TenantLedger();
            row.setTenantId(tenantId);
            row.setAssignable(assignable);
            row.setConfigEpoch(INITIAL_CONFIG_EPOCH);
            try {
                ledgerMapper.insert(row);
                log.info("[iot] 台账新增：tenantId={} assignable={} configEpoch={}",
                    tenantId, assignable, INITIAL_CONFIG_EPOCH);
                return LedgerChange.CREATED;
            } catch (DuplicateKeyException ex) {
                // 并发首次写入：另一线程已插入 ⇒ 退化为「复活 + 递增」（异常仍入 DEBUG 日志）
                log.debug("[iot] 台账并发首次写入，转为复活：tenantId={}", tenantId, ex);
                ledgerMapper.reviveAndBump(tenantId, assignable);
                return LedgerChange.REVIVED;
            }
        }
        // 注意：BaseEntity 的 isDeleted 是 Integer（tinyint），不是 Boolean——
        // 用 Boolean.TRUE.equals(...) 会恒假，把「复活」误判成「更新」（真库用例抓到过）。
        boolean wasDeleted = existing.getIsDeleted() != null && existing.getIsDeleted() != 0;
        int updated = ledgerMapper.reviveAndBump(tenantId, assignable);
        if (updated == 0) {
            // 极窄竞态（行在两次语句之间被物理删除）：暴露而不是静默当作成功
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "台账更新失败（并发冲突且无法更新）：" + tenantId);
        }
        log.info("[iot] 台账更新：tenantId={} assignable={} {}",
            LogSanitizer.sanitize(tenantId), assignable,
            wasDeleted ? "（复活了此前被删除的台账行）" : "");
        return wasDeleted ? LedgerChange.REVIVED : LedgerChange.UPDATED;
    }

    /**
     * 设备/点位映射等**采集配置**变更后推进配置版本号（M-2：让接入侧发现变更，修 G7）。
     *
     * <p>为什么需要：接入侧只在台账版本号变化时才重取设备清单；若上游增删设备、改点位映射不推进版本号，
     * 变更就永远不会被接入侧发现（只能靠链路重建）。本方法把「改了配置」变成可对账的信号。</p>
     *
     * <p><b>台账没有该租户时是 no-op（返回 false）</b>：不 insert、不改 assignable——
     * 否则会把「可分配来源」从配置兜底静默切成台账。此时接入侧收不到信号，属**已登记的已知限制**
     * （见 docs/IOT-ROADMAP.md），调用方只需 DEBUG 记录，不得吞掉配置变更本身。</p>
     *
     * @param tenantId 租户 ID；为 {@code null} 时直接返回 false（无租户上下文，例如未开租户插件）
     * @return 是否真的推进了版本号（台账无该租户/租户为 null 时返回 {@code false}）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean bumpConfigEpoch(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        int rows = ledgerMapper.bumpConfigEpoch(tenantId);
        if (rows == 0) {
            log.debug("[iot] 台账无该租户，配置版本号未推进（接入侧不会收到本次变更信号）：tenantId={}",
                LogSanitizer.sanitize(tenantId));
            return false;
        }
        log.info("[iot] 采集配置变更，台账版本号已推进：tenantId={}", LogSanitizer.sanitize(tenantId));
        return true;
    }

    /**
     * 采集配置变更后按**当前租户上下文**推进版本号（设备/点位映射写入口统一走这里）。
     *
     * <p>租户上下文缺失时是 no-op（返回 {@code false}）：例如未开租户插件的单租户部署。
     * 该情形下接入侧收不到变更信号，属已登记的已知限制，调用方不得因此中断业务写入。</p>
     *
     * @return 是否真的推进了版本号
     */
    public boolean bumpConfigEpochOfCurrentTenant() {
        return bumpConfigEpoch(TenantContext.getTenantId().orElse(null));
    }

    /**
     * 台账全量（供运维端点查看「谁可被分配、版本号到哪了」）。
     *
     * @return 台账行（按租户排序；可能为空集合，绝不返回 null）
     */
    public List<TenantLedgerResp> listAll() {
        List<TenantLedger> rows = ledgerMapper.selectList(Wrappers.<TenantLedger>lambdaQuery()
            .orderByAsc(TenantLedger::getTenantId));
        List<TenantLedgerResp> result = new ArrayList<>(rows.size());
        for (TenantLedger row : rows) {
            result.add(toResp(row));
        }
        return result;
    }

    /** 实体 → 契约模型。 */
    private TenantLedgerResp toResp(TenantLedger row) {
        TenantLedgerResp resp = new TenantLedgerResp();
        resp.setTenantId(row.getTenantId());
        resp.setAssignable(row.getAssignable());
        resp.setConfigEpoch(row.getConfigEpoch());
        resp.setUpdateTime(row.getUpdateTime());
        return resp;
    }

    /**
     * 当前可分配租户（M0b-2 的分配来源）。
     *
     * @return 可分配租户（可能为空集合，绝不返回 null）
     */
    public List<Long> listAssignableTenantIds() {
        List<TenantLedger> rows = ledgerMapper.selectList(Wrappers.<TenantLedger>lambdaQuery()
            .select(TenantLedger::getTenantId)
            .eq(TenantLedger::getAssignable, Boolean.TRUE));
        List<Long> ids = new ArrayList<>(rows.size());
        for (TenantLedger row : rows) {
            ids.add(row.getTenantId());
        }
        return ids;
    }

    /**
     * 台账「可分配」标记。（枚举按本仓规范带 code/desc，不使用 ordinal。）
     *
     * @author wenbin
     * @since 2026-09-21
     */
    public enum LedgerChange {

        /** 新建台账行。 */
        CREATED("created", "新建"),
        /** 复活此前被逻辑删除的台账行。 */
        REVIVED("revived", "复活"),
        /** 更新已有台账行。 */
        UPDATED("updated", "更新");

        private final String code;

        private final String desc;

        LedgerChange(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        /** 稳定码（落库/传输用）。 */
        public String getCode() {
            return code;
        }

        /** 中文说明。 */
        public String getDesc() {
            return desc;
        }
    }
}
