package com.example.template.util;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpringContextHolder} 的单元测试。
 * <p>
 * 不依赖 {@code @SpringBootTest}（避免拉起项目里的 MySQL/Redis/Nacos 等真实基础设施），而是为每
 * 个测试方法手动构造一个轻量的 {@link AnnotationConfigApplicationContext}，验证
 * {@link SpringContextHolder} 收到 {@code setApplicationContext} 回调后的行为；每个测试结束后
 * 都会关闭该容器并清空 {@link SpringContextHolder} 持有的静态引用，保证测试之间互不影响，
 * 也让"容器未就绪"场景在任意测试之后都能被正确复现。
 */
class SpringContextHolderTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) {
            context.close();
            context = null;
        }
        // 每个测试结束后都清空静态引用，避免上一个测试构造的容器实例串扰下一个测试
        // （尤其是验证"容器未就绪"场景时，需要确保静态字段确实是 null）。
        Field field = SpringContextHolder.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void getBeanByName_returnsRegisteredBean() {
        context = newContext(TestConfig.class);
        Greeter greeter = SpringContextHolder.getBean("greeter");
        assertThat(greeter).isNotNull();
    }

    @Test
    void getBeanByName_throwsWhenNotRegistered() {
        context = newContext(TestConfig.class);
        assertThatThrownBy(() -> SpringContextHolder.getBean("notExists"))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void getBeanByType_returnsUniqueBean() {
        context = newContext(TestConfig.class);
        Greeter greeter = SpringContextHolder.getBean(Greeter.class);
        assertThat(greeter).isNotNull();
    }

    @Test
    void getBeanByType_throwsWhenNotExists() {
        context = newContext(TestConfig.class);
        assertThatThrownBy(() -> SpringContextHolder.getBean(Stranger.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void getBeanByType_throwsWhenMultipleCandidates() {
        context = newContext(MultiCandidateConfig.class);
        assertThatThrownBy(() -> SpringContextHolder.getBean(Greeter.class))
                .isInstanceOf(NoUniqueBeanDefinitionException.class);
    }

    @Test
    void getBeanByNameAndType_returnsMatchedBean() {
        context = newContext(TestConfig.class);
        Greeter greeter = SpringContextHolder.getBean("greeter", Greeter.class);
        assertThat(greeter).isNotNull();
    }

    @Test
    void getBeanByNameAndType_throwsWhenTypeMismatch() {
        context = newContext(TestConfig.class);
        assertThatThrownBy(() -> SpringContextHolder.getBean("greeter", Stranger.class))
                .isInstanceOf(BeanNotOfRequiredTypeException.class);
    }

    @Test
    void getProperty_returnsExistingValue() {
        context = newContext(TestConfig.class, "demo.key", "demo-value");
        assertThat(SpringContextHolder.getProperty("demo.key")).isEqualTo("demo-value");
    }

    @Test
    void getProperty_returnsNullWhenMissingAndNoDefault() {
        context = newContext(TestConfig.class);
        assertThat(SpringContextHolder.getProperty("missing.key")).isNull();
    }

    @Test
    void getProperty_returnsDefaultWhenMissing() {
        context = newContext(TestConfig.class);
        assertThat(SpringContextHolder.getProperty("missing.key", "default-value")).isEqualTo("default-value");
    }

    @Test
    void getPropertyWithType_returnsConvertedValue() {
        context = newContext(TestConfig.class, "demo.int", "42");
        assertThat(SpringContextHolder.getProperty("demo.int", Integer.class)).isEqualTo(42);
    }

    @Test
    void getPropertyWithType_returnsNullWhenMissingAndNoDefault() {
        context = newContext(TestConfig.class);
        assertThat(SpringContextHolder.getProperty("missing.int", Integer.class)).isNull();
    }

    @Test
    void getPropertyWithType_returnsDefaultWhenMissing() {
        context = newContext(TestConfig.class);
        assertThat(SpringContextHolder.getProperty("missing.int", Integer.class, 7)).isEqualTo(7);
    }

    @Test
    void getPropertyWithType_throwsWhenValueNotConvertible() {
        context = newContext(TestConfig.class, "demo.int", "not-a-number");
        assertThatThrownBy(() -> SpringContextHolder.getProperty("demo.int", Integer.class))
                .isInstanceOf(ConversionFailedException.class);
    }

    @Test
    void getBean_throwsWhenContextNotReady() {
        assertThatThrownBy(() -> SpringContextHolder.getBean(Greeter.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getProperty_throwsWhenContextNotReady() {
        assertThatThrownBy(() -> SpringContextHolder.getProperty("any.key"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 构造一个只注册了 {@code configClass} 与 {@link SpringContextHolder} 的最小
     * {@link AnnotationConfigApplicationContext} 并刷新，刷新过程中 Spring 会回调
     * {@link SpringContextHolder#setApplicationContext}，使被测的静态方法可用；
     * 传入的 key/value 对会作为最高优先级的属性源写入容器的 {@code Environment}。
     */
    private AnnotationConfigApplicationContext newContext(Class<?> configClass, String... keyValuePairs) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (keyValuePairs.length > 0) {
            Map<String, Object> properties = new HashMap<>();
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                properties.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-properties", properties));
        }
        ctx.register(configClass, SpringContextHolder.class);
        ctx.refresh();
        return ctx;
    }

    /** 测试用的无状态 Bean，用于验证按名称/类型获取 Bean 的各种场景。 */
    static class Greeter {
    }

    /** 测试用类型，不会被注册为任何 Bean，用于验证"类型不存在"与"类型不匹配"场景。 */
    static class Stranger {
    }

    @Configuration
    static class TestConfig {

        @Bean
        Greeter greeter() {
            return new Greeter();
        }

    }

    @Configuration
    static class MultiCandidateConfig {

        @Bean
        Greeter greeter1() {
            return new Greeter();
        }

        @Bean
        Greeter greeter2() {
            return new Greeter();
        }

    }

}
