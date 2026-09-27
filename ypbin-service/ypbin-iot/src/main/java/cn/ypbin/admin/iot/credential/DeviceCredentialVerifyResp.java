/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.credential;

import lombok.Getter;
import lombok.Setter;

/**
 * 设备凭据校验响应（**绝不含口令或哈希**）。
 *
 * <p>{@code result} 是给机器（broker 认证源）的最小判据；{@code reason} 是给人（值班）的定位线索。
 * 两者分开是因为它们的稳定性要求不同：{@code result} 只有 allow/deny 两种取值（EMQX 侧只认它），
 * 而 {@code reason} 会随排障需要扩充。</p>
 *
 * <p>⚠️ 已知的信息暴露面（如实登记）：{@code reason} 会让持有内部 token 的调用方区分
 * 「设备不存在」与「口令错」。该端点位于 {@code /internal/**}（凭证守卫，fail-closed）
 * 且只绑回环；能到达它的主体本来就能读写平台内部面，故此处不做额外收敛。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class DeviceCredentialVerifyResp {

    /** 允许（{@code true}）/ 拒绝（{@code false}）。 */
    private Boolean allowed;

    /** 结果码：{@code allow} / {@code deny}（对齐 EMQX HTTP 认证源的 result 语义）。 */
    private String result;

    /** 拒绝原因码（仅拒绝时非空）；取值见 {@code DeviceCredentialDenyReason#getCode()}。 */
    private String reason;

    /** 设备主键（用户名解析成功时非空；设备不存在时为空）。 */
    private Long deviceId;

    /** 设备当前凭据版本号（未签发时为空）。 */
    private Integer credentialVersion;
}
