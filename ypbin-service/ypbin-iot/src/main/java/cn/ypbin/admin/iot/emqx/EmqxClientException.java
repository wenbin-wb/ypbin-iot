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

/**
 * EMQX 管理面调用失败（**不静默降级**：失败必须显式暴露，由调用方决定回滚或告警）。
 *
 * <p>异常消息里**只允许**出现原因码、HTTP 状态码、实体标识（username / topic）——
 * 绝不允许出现 API Key/Secret、口令明文或口令哈希（凭据纪律，见 {@code docs/EMQX-INTEGRATION.md}）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public class EmqxClientException extends RuntimeException {

    /** 可区分的原因码。 */
    private final transient EmqxErrorCode errorCode;

    /**
     * 构造。
     *
     * @param errorCode 原因码
     * @param message   说明（不含凭据）
     */
    public EmqxClientException(EmqxErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造（带底层异常，保留完整堆栈供日志使用）。
     *
     * @param errorCode 原因码
     * @param message   说明（不含凭据）
     * @param cause     底层异常
     */
    public EmqxClientException(EmqxErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 原因码。
     *
     * @return 原因码
     */
    public EmqxErrorCode getErrorCode() {
        return errorCode;
    }
}
