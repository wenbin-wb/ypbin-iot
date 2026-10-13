/*
 * Copyright (c) 2026 ypbin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.admin.iot.controller;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 控制器门禁测试的公共取方法入口：只取「业务方法」，剔除编译器/字节码增强生成的成员。
 *
 * <p><b>为什么必须有这个入口（一次真实踩坑）</b>：2026-10-13 给本模块接入 JaCoCo 覆盖率后，
 * {@code IotCommandControllerGateTest#browserEndpointsMustKeepTheEnvelope} 立刻转红 ——
 * 该用例遍历 {@code getDeclaredMethods()} 要求「每个方法都返回 {@code R} 信封」，
 * 而 JaCoCo 的 {@code prepare-agent} 在**运行时**给每个被插桩的类注入了合成方法
 * {@code $jacocoInit()}（返回 {@code boolean}）⇒ 门禁把"插桩产物"当成了"端点"。
 * 这不是覆盖率工具的问题：门禁本来就该区分「业务方法」与「编译器/增强生成的成员」。</p>
 *
 * <p><b>判据</b>：{@link Method#isSynthetic()}（发射器生成，如 {@code $jacocoInit}、{@code lambda$...}）
 * 与 {@link Method#isBridge()}（泛型协变桥方法）。两者都不是人写的端点。</p>
 *
 * <p><b>自检</b>：{@link ControllerGateMethodsTest} 用真实夹具证明「合成/桥方法被剔除、
 * 普通方法被保留」，避免这个过滤器哪天变成"什么都不扫"的静默空跑。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
final class ControllerGateMethods {

    private ControllerGateMethods() {
    }

    /** 取指定类的业务方法（非合成、非桥方法）。 */
    static List<Method> businessMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> !method.isBridge())
            .toList();
    }

    /**
     * 取业务方法并要求非空：把"一个方法都没扫到 ⇒ 门禁空跑（假绿）"变成显式失败。
     *
     * @param type    被检查的控制器
     * @param purpose 门禁用途（用于失败信息，便于定位）
     */
    static List<Method> businessMethodsOrFail(Class<?> type, String purpose) {
        List<Method> methods = businessMethods(type);
        if (methods.isEmpty()) {
            throw new AssertionError(type.getSimpleName()
                + " 一个业务方法都没扫到 ⇒ " + purpose + " 是空跑（假绿），请检查取方法的方式");
        }
        return methods;
    }
}
