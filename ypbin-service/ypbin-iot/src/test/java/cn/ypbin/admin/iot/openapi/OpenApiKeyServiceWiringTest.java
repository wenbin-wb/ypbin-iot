package cn.ypbin.admin.iot.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * OpenApiKeyService 装配门禁（#160 回归：多构造器无 {@code @Autowired} 时 Spring 起不来，
 * 而单测全是手 new 构造、CI 照样绿 ⇒ 启动失败只能到部署才暴露）。
 *
 * <p>锁一条：主构造器有且仅有一个 {@code @Autowired}（其余构造器供单测手 new）。</p>
 */
class OpenApiKeyServiceWiringTest {

    @Test
    @DisplayName("主构造器有且仅有一个 @Autowired（多构造器时 Spring 才能选对）")
    void exactlyOneAutowiredConstructor() {
        Constructor<?>[] ctors = OpenApiKeyService.class.getDeclaredConstructors();
        assertThat(ctors.length).isGreaterThan(1);
        long autowired = Arrays.stream(ctors)
            .filter(c -> c.isAnnotationPresent(Autowired.class))
            .count();
        assertThat(autowired).isEqualTo(1L);
    }
}
