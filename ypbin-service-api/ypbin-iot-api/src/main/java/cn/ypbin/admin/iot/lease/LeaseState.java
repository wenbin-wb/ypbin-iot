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

import lombok.Getter;

/**
 * 租约状态。
 *
 * <p>状态迁移（与契约文档一致）：{@code ACTIVE --到期/释放--> PENDING_TAKEOVER|RELEASED}，
 * {@code PENDING_TAKEOVER|RELEASED --接管/重新分配--> ACTIVE}。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
public enum LeaseState {

    /** 租约有效，节点正在采集。 */
    ACTIVE("active", "租约有效"),
    /** 租约已过期或节点失联，尚未被新节点接管（失效扫描置为该状态）。 */
    PENDING_TAKEOVER("pending_takeover", "待接管"),
    /** 节点主动释放（正常下线），可被重新分配。 */
    RELEASED("released", "已释放");

    /** 落库与对外传输用的稳定码（**严禁用 ordinal**）。 */
    private final String code;

    /** 中文说明。 */
    private final String desc;

    LeaseState(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按稳定码解析状态。
     *
     * @param code 稳定码
     * @return 状态；未知码返回 {@code null}（调用方决定如何处理，不静默兜底）
     */
    public static LeaseState ofCode(String code) {
        for (LeaseState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return null;
    }
}
