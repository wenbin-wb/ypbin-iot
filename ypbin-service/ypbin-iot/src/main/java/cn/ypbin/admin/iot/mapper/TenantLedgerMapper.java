/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.TenantLedger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户台账 Mapper。
 *
 * @author wenbin
 * @since 2026-09-21
 */
public interface TenantLedgerMapper extends BaseMapper<TenantLedger> {

    /**
     * 按租户查台账行，**包含逻辑删除行**。
     *
     * <p>为什么必须绕过逻辑删除：{@code uk_tenant_ledger(tenant_id)} 不包含删除标记，软删后同一 tenant_id
     * 再 insert 会撞唯一键。因此写入口必须先看到被软删的行并「复活」它，而不是盲目 insert。</p>
     *
     * @param tenantId 租户 ID
     * @return 台账行（含已删除）；不存在返回 {@code null}
     */
    @Select("SELECT * FROM tenant_ledger WHERE tenant_id = #{tenantId} LIMIT 1")
    TenantLedger selectIncludingDeleted(@Param("tenantId") Long tenantId);

    /**
     * 复活（或更新）台账行并**在同一语句内**把配置版本号 +1。
     *
     * <p>放在一条 UPDATE 里是刻意的：版本号必须与台账变更**同一事务、同一条语句**生效，
     * 否则会出现「台账变了但版本没变」⇒ 接入侧对账漏拉。</p>
     *
     * @param tenantId   租户 ID
     * @param assignable 是否可分配
     * @return 受影响行数
     */
    @Update("UPDATE tenant_ledger SET assignable = #{assignable}, config_epoch = config_epoch + 1, "
        + "is_deleted = 0, update_time = NOW() WHERE tenant_id = #{tenantId}")
    int reviveAndBump(@Param("tenantId") Long tenantId, @Param("assignable") boolean assignable);
}
