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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ControllerGateMethods} 的**有效性自检**：证明过滤器真的在剔除「非业务方法」，
 * 而不是把一切都放过或把一切都滤掉（后者会让依赖它的门禁变成静默空跑）。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ControllerGateMethodsTest {

    /** 夹具：含合成方法（lambda）、桥方法（Comparable 协变）与普通方法。 */
    static class Fixture implements Comparable<Fixture> {

        /** 普通方法：必须被保留。 */
        public int businessMethod() {
            return 1;
        }

        /** 触发编译器生成 {@code lambda$...} 合成方法。 */
        public Runnable lambdaHolder() {
            return () -> {
            };
        }

        @Override
        public int compareTo(Fixture other) {
            return 0;
        }
    }

    @Test
    @DisplayName("合成方法与桥方法必须被剔除，普通方法必须保留（过滤器不能恒真也不能恒假）")
    void mustDropGeneratedMembersButKeepBusinessMethods() {
        List<Method> methods = ControllerGateMethods.businessMethods(Fixture.class);
        List<String> names = methods.stream().map(Method::getName).toList();

        assertThat(names).as("普通方法必须被保留").contains("businessMethod", "lambdaHolder");
        assertThat(names).as("lambda 合成方法必须被剔除").noneMatch(n -> n.startsWith("lambda$"));

        // 桥方法判定必须看**签名**而不是名字：compareTo(Fixture) 是业务方法、compareTo(Object) 才是桥方法，
        // 两者同名 ⇒ 只按名字断言会同时把业务方法也算成"桥方法"（本用例初版就踩了这个坑）。
        List<Method> compareToMethods = methods.stream()
            .filter(m -> m.getName().equals("compareTo"))
            .toList();
        assertThat(compareToMethods)
            .as("只应保留 compareTo(Fixture) 这一个业务方法")
            .hasSize(1);
        assertThat(compareToMethods.get(0).getParameterTypes())
            .as("保留的必须是 compareTo(Fixture)，而不是编译器生成的 compareTo(Object) 桥方法")
            .containsExactly(Fixture.class);
        assertThat(methods).as("夹具里必须能扫到方法（否则规则空跑）").hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("扫不到任何业务方法时必须显式失败，不许静默空跑")
    void mustFailLoudlyWhenNothingScanned() {
        assertThatThrownBy(() -> ControllerGateMethods.businessMethodsOrFail(EmptyFixture.class, "示例门禁"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("空跑");
    }

    /** 夹具：编译后没有任何业务方法（只有构造器）。 */
    static class EmptyFixture {
    }
}
