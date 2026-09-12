package com.example.template.approval.engine.flowable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link SpringContextHolder} 在 ApplicationContext 初始化前后的行为：
 * 未初始化时 {@code getBean} 直接抛出明确异常，初始化后可以正常取到 Bean。
 * <p>
 * 不使用 {@code @SpringBootTest}：本类的静态字段会被真实的 Spring 容器启动过程设置，
 * 为了让“未初始化”场景在任意执行顺序下都可确定性复现，测试内直接通过反射操作静态字段，
 * 并在每个测试结束后重置，避免影响同一 JVM 内其他基于真实 Spring 容器的测试。
 */
class SpringContextHolderTest {

    @AfterEach
    void resetStaticContext() throws Exception {
        setStaticContext(null);
    }

    @Test
    void getBean_throwsIllegalStateException_whenContextNotInitialized() throws Exception {
        setStaticContext(null);

        assertThatThrownBy(() -> SpringContextHolder.getBean(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ApplicationContext 尚未初始化");
    }

    @Test
    void getBean_returnsBean_afterContextInitialized() {
        ApplicationContext mockContext = mock(ApplicationContext.class);
        when(mockContext.getBean(String.class)).thenReturn("hello");

        new SpringContextHolder().setApplicationContext(mockContext);

        assertThat(SpringContextHolder.getBean(String.class)).isEqualTo("hello");
    }

    private void setStaticContext(ApplicationContext context) throws Exception {
        Field field = SpringContextHolder.class.getDeclaredField("context");
        field.setAccessible(true);
        field.set(null, context);
    }
}
