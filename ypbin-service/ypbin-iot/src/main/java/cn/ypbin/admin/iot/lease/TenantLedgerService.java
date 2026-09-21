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
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
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
