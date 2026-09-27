/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

import java.util.Map;
import java.util.Optional;

/**
 * EMQX 管理面失败原因码（可区分，便于调用方决定「重试 / 报错 / 告警」）。
 *
 * <p><b>为什么必须可区分</b>：三类失败要三种处置——<b>凭据错</b>（API Key 不对）重试一万次也没用，
 * 要人去改配置；<b>不可达</b>（隧道断了/EMQX 挂了）重试有意义，要告警；<b>业务错</b>（用户名非法、
 * 密码哈希形态不对）是平台自身的 bug，要修代码。混成一个「EMQX 调用失败」会让值班只能靠猜。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public enum EmqxErrorCode {

    /** 管理面不可达（连接超时/IO 故障/隧道断）——可重试、需告警。 */
    UNREACHABLE("UNREACHABLE", "EMQX 管理面不可达"),

    /** 管理面鉴权失败（API Key/Secret 不对或权限不足）——不可重试、需人工修配置。 */
    AUTH_FAILED("AUTH_FAILED", "EMQX 管理面鉴权失败"),

    /** EMQX 返回了其它非 2xx——不可直接重试，需按响应码定位。 */
    REJECTED("REJECTED", "EMQX 拒绝了本次调用");

    /** EMQX 侧失败码 → 本枚举（只做「是不是鉴权/不可达」的分档）。 */
    private static final Map<Integer, EmqxErrorCode> BY_HTTP_STATUS = Map.of(401, AUTH_FAILED, 403, AUTH_FAILED);

    private final String code;

    private final String desc;

    EmqxErrorCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 取码。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 取说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 HTTP 状态码分档（401/403 ⇒ 鉴权失败，其余 ⇒ 被拒）。
     *
     * @param httpStatus HTTP 状态码
     * @return 原因码（不含「不可达」——那类没有 HTTP 状态码）
     */
    public static EmqxErrorCode fromHttpStatus(int httpStatus) {
        return Optional.ofNullable(BY_HTTP_STATUS.get(httpStatus)).orElse(REJECTED);
    }
}
