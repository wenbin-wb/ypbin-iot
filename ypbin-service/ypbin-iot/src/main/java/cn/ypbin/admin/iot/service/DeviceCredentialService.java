/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyReq;
import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyResp;
import cn.ypbin.admin.iot.model.resp.DeviceConnectionResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialIssuedResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialResp;

/**
 * 设备凭据生命周期（签发 / 查看 / 重置 / 吊销）+ 接入信息 + 凭据校验。
 *
 * <p>语义口径（与 {@code docs/EMQX-INGRESS-DESIGN.md} §5.3 一致，差异见
 * {@code docs/DEVICE-CREDENTIAL.md}）：</p>
 * <ul>
 *   <li><b>用户名稳定、只轮换口令</b>：用户名恒为 {@code {tenantId}.{deviceId}}；</li>
 *   <li><b>明文一次性</b>：口令只在 {@link #issue} 的响应里出现一次，服务端只存哈希；</li>
 *   <li><b>重置 ≡ 重新签发</b>：版本号 +1、旧口令立即失效（判据见 {@link #verify}）；</li>
 *   <li><b>吊销即不可用</b>：置吊销时刻并清空秘密 ⇒ 该设备此后不得再认证成功。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public interface DeviceCredentialService {

    /**
     * 签发或轮换设备凭据（等价于「重置」）。
     *
     * @param deviceId 设备主键
     * @return 含**一次性明文**的签发结果
     */
    DeviceCredentialIssuedResp issue(Long deviceId);

    /**
     * 查看凭据元信息（**只返回元信息，绝不返回口令或哈希**）。
     *
     * @param deviceId 设备主键
     * @return 元信息
     */
    DeviceCredentialResp view(Long deviceId);

    /**
     * 吊销设备凭据（幂等：已吊销时重复调用不改变状态、不报错）。
     *
     * @param deviceId 设备主键
     */
    void revoke(Long deviceId);

    /**
     * 装配设备接入信息（broker 地址、用户名、clientId、上下行主题前缀；**不含口令**）。
     *
     * @param deviceId 设备主键
     * @return 接入信息
     */
    DeviceConnectionResp connection(Long deviceId);

    /**
     * 校验用户名 + 口令（内部端点与 broker 认证源共用；**不返回也不记录任何秘密**）。
     *
     * @param req 校验请求
     * @return 校验结果（allow/deny + 拒绝原因码）
     */
    DeviceCredentialVerifyResp verify(DeviceCredentialVerifyReq req);
}
