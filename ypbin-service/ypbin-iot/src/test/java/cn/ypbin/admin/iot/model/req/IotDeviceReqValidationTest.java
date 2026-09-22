/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IotDeviceReq} 的校验约束单测。
 *
 * <p><b>为什么必须有用例</b>：注解不是文档。设备端点会在协议栈的**建链路径**上被
 * {@code Endpoint.of} 解析，而它会明确拒绝「没有 scheme」的取值（如 {@code 127.0.0.1:15002}）。
 * 写入侧不加校验，脏数据就会变成运行期「设备被跳过」或「整轮绑定被异常中断」的静默失效
 * （外委复核用探针实测过这条路径）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
class IotDeviceReqValidationTest {

    private static final Validator VALIDATOR =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("★ 端点漏 scheme 必须在写入侧被拒（协议栈侧会判非法，且它是建链路径）")
    void endpointWithoutSchemeMustBeRejected() {
        IotDeviceReq req = validReq();
        req.setEndpoint("127.0.0.1:15002");

        assertThat(violatedFields(req)).contains("endpoint");
    }

    @Test
    @DisplayName("端点含空白（如 `tcp://host:1 502`）同样拒绝")
    void endpointWithBlankMustBeRejected() {
        IotDeviceReq req = validReq();
        req.setEndpoint("tcp://host:1 502");

        assertThat(violatedFields(req)).contains("endpoint");
    }

    @Test
    @DisplayName("合法端点与协议码不得误报（tcp://127.0.0.1:15002 必须通过）")
    void validReqMustPass() {
        assertThat(violatedFields(validReq())).isEmpty();
    }

    @Test
    @DisplayName("协议码必须符合协议栈规则（小写字母开头，仅小写字母/数字/连字符）")
    void protocolMustFollowFrameworkRule() {
        IotDeviceReq req = validReq();
        req.setProtocol("TCP");

        assertThat(violatedFields(req)).contains("protocol");
    }

    private static Set<String> violatedFields(IotDeviceReq req) {
        Set<ConstraintViolation<IotDeviceReq>> violations = VALIDATOR.validate(req);
        return violations.stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }

    private static IotDeviceReq validReq() {
        IotDeviceReq req = new IotDeviceReq();
        req.setDeviceCode("DEV-001");
        req.setDeviceName("水表-001");
        req.setProtocol("tcp");
        req.setEndpoint("tcp://127.0.0.1:15002");
        return req;
    }
}
