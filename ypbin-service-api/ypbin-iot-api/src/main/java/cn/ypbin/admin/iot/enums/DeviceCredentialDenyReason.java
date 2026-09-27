/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 设备凭据校验**不通过**的原因码（有界集合，落库/进日志/进指标都用 {@code code}）。
 *
 * <p>为什么枚举化：设备接入失败最常见的工单是「设备连不上」，而「连不上」至少有
 * 「从没签发」「签发过但被吊销」「口令不对」「库里的凭据行与设备版本号对不上」四种。
 * 不区分就只能靠人去读库比对；区分开之后，一次校验就能定位到是哪一层。</p>
 *
 * <p>取值集合有界，可直接做指标标签；新增原因码只会加一条时间线。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public enum DeviceCredentialDenyReason {

    /** 用户名不符合 {@code {tenantId}.{deviceId}}（两段纯数字），身份不可信。 */
    MALFORMED_USERNAME("malformed-username", "用户名不符合 {tenantId}.{deviceId}"),

    /** 设备不存在（**含跨租户**：跨租户一律按不存在处理，不区分）。 */
    DEVICE_NOT_FOUND("device-not-found", "设备不存在"),

    /** 设备从未签发过凭据。 */
    NOT_ISSUED("not-issued", "尚未签发凭据"),

    /** 凭据已吊销（此后不得再认证成功）。 */
    REVOKED("revoked", "凭据已吊销"),

    /** 凭据行的秘密列已被清空（吊销过，或数据不一致）⇒ 无法校验。 */
    SECRET_MISSING("secret-missing", "凭据秘密不可用（已清空或缺失）"),

    /** 凭据行版本号与设备当前版本号不一致（轮换中间态/脏数据）⇒ 不拿旧哈希放行。 */
    VERSION_STALE("version-stale", "凭据行版本与设备当前版本不一致"),

    /** 口令不匹配（设备存在、凭据有效，就是口令错）。 */
    BAD_PASSWORD("bad-password", "口令不匹配");

    private final String code;

    private final String desc;

    DeviceCredentialDenyReason(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码（数据库/接口/日志用；稳定标识，改文案不改它）。
     *
     * @return 原因码
     */
    public String getCode() {
        return code;
    }

    /**
     * 原因描述（面向运维的中文说明）。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }
}
