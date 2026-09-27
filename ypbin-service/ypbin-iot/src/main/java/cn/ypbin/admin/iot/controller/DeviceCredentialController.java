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

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.resp.DeviceConnectionResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialIssuedResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialResp;
import cn.ypbin.admin.iot.service.DeviceCredentialService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.log.enums.Include;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备凭据与接入信息接口（设计 §5.3 的 4 个端点）。
 *
 * <p>路径与网关口径：客户端调 {@code /iot/devices/{id}/credential}，网关 {@code StripPrefix=1}
 * 后落到本控制器的 {@code /devices/{id}/credential}（与 {@code IotDeviceController} 同构）。</p>
 *
 * <p><b>凭据纪律（本类最要紧的三条）</b>：</p>
 * <ol>
 *   <li><b>明文只出现一次</b>：只有 {@code POST /credential}（签发/轮换）的响应里有 {@code password}，
 *       且服务端只存哈希；重复调用即轮换（版本 +1，旧口令立即失效）。</li>
 *   <li><b>查看只回元信息</b>：{@code GET /credential} 的响应模型里**结构上不存在**秘密字段；
 *       {@code GET /connection} 同理（接入信息弹窗与二维码都不需要口令）。</li>
 *   <li><b>秘决不进日志</b>：签发端点显式从操作日志里排除请求体与响应体——尽管 starter 的默认采集集合
 *       （{@code REQUEST_PARAM/IP/CLIENT}）本来就不含响应体，这里仍显式声明，让「口令不得进日志」
 *       成为**读代码就能看到**的约束，而不是「依赖某个默认值别被改」。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceCredentialController {

    private final DeviceCredentialService deviceCredentialService;

    /**
     * 签发或轮换设备凭据（响应含**一次性明文**口令；旧口令随即失效）。
     *
     * @param deviceId 设备主键
     * @return 含一次性明文的签发结果
     */
    /**
     * 签发或轮换设备凭据（响应含**一次性明文**口令；旧口令随即失效）。
     *
     * <p>{@code @Idempotent} 在这里是**刻意保留**的：连点两次会让调用方先看到口令 A、而库里生效的是口令 B
     * ⇒ 设备按 A 配置必然连不上，现象是「口令明明抄对了却认证失败」。代价写清楚：
     * **窗口内（默认 5 秒）无法连续轮换两次**，确需立刻再换请等过一个窗口。</p>
     *
     * @param deviceId 设备主键
     * @return 含一次性明文的签发结果
     */
    @PostMapping("/{deviceId}/credential")
    @SaCheckPermission("iot:credential:issue")
    @Idempotent(message = "上一次签发/轮换刚完成，请稍后重试（本次未变更任何凭据）")
    @Log(value = "签发/轮换 IoT 设备凭据", excludes = {Include.REQUEST_BODY, Include.RESPONSE_BODY})
    public R<DeviceCredentialIssuedResp> issue(@PathVariable Long deviceId) {
        return R.ok(deviceCredentialService.issue(deviceId));
    }

    /**
     * 查看凭据元信息（版本/签发时刻/吊销时刻/是否可用；**绝不含口令或哈希**）。
     *
     * @param deviceId 设备主键
     * @return 凭据元信息
     */
    @GetMapping("/{deviceId}/credential")
    @SaCheckPermission("iot:credential:get")
    public R<DeviceCredentialResp> view(@PathVariable Long deviceId) {
        return R.ok(deviceCredentialService.view(deviceId));
    }

    /**
     * 吊销设备凭据（**幂等**：重复调用返回成功且不改变状态；此后该设备不得再认证成功）。
     *
     * <p><b>为什么这里不加 {@code @Idempotent}</b>：设计（<code>EMQX-INGRESS-DESIGN.md</code> P0-3 验收口径）
     * 明确要求「**重复 DELETE 返回 200**（幂等）」；而 {@code @Idempotent} 会把窗口内的重复调用判成
     * {@code R.code=409}（HTTP 200 信封），与那条契约直接冲突。吊销的幂等性已由服务层实现
     * （已吊销 ⇒ 直接返回、不改写首次吊销时刻，见 {@code DeviceCredentialServiceImpl#revoke}），
     * 这里再加一层只会把「无害的重复请求」变成「报错」。</p>
     *
     * @param deviceId 设备主键
     * @return 空响应
     */
    @DeleteMapping("/{deviceId}/credential")
    @SaCheckPermission("iot:credential:revoke")
    @Log("吊销 IoT 设备凭据")
    public R<Void> revoke(@PathVariable Long deviceId) {
        deviceCredentialService.revoke(deviceId);
        return R.ok();
    }

    /**
     * 装配设备接入信息（broker 地址、用户名、clientId、上下行主题前缀；**不含口令**）。
     *
     * @param deviceId 设备主键
     * @return 接入信息
     */
    @GetMapping("/{deviceId}/connection")
    @SaCheckPermission("iot:credential:get")
    public R<DeviceConnectionResp> connection(@PathVariable Long deviceId) {
        return R.ok(deviceCredentialService.connection(deviceId));
    }
}
