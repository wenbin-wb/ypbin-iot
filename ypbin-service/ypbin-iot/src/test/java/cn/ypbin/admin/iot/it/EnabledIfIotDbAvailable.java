/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仅在 IoTDB 可用（外部实例或 Docker 容器）时执行集成测试；不可用时**跳过并打印原因**。
 *
 * <p>与 {@code @EnabledIfMySqlAvailable} 同一取向，但「跳过必须看得见」：除了把原因写进 JUnit 的
 * disabled 结果（会进 CI 报告），这里再显式打一条 WARN——本仓踩过「静默跳过 == 没测 == 假绿」的坑，
 * workflow 里另有一步断言报告存在且无跳过。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(EnabledIfIotDbAvailable.IotDbCondition.class)
public @interface EnabledIfIotDbAvailable {

    /**
     * IoTDB 可用性判断条件。
     */
    class IotDbCondition implements ExecutionCondition {

        private static final Logger log = LoggerFactory.getLogger(IotDbCondition.class);

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (IotDbIntegrationTestSupport.available()) {
                return ConditionEvaluationResult.enabled("IoTDB 可用（外部实例或 Docker 容器）");
            }
            String reason = IotDbIntegrationTestSupport.skipReason();
            log.warn("[iot-it] 跳过 IoTDB 集成测试：{}", reason);
            return ConditionEvaluationResult.disabled(reason);
        }
    }
}
