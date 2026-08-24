package com.example.template.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 统一封装的 Spring {@link ApplicationContext} 静态访问入口。
 * <p>
 * 通过实现 {@link ApplicationContextAware}，在容器启动完成时拿到 {@link ApplicationContext} 并
 * 保存为静态字段，使得非 Spring 管理的静态上下文（静态工具方法、静态初始化块等）也能按名称/
 * 类型获取 Bean，或读取 {@link Environment} 中的配置项，而不必各自持有
 * {@link ApplicationContext} 引用、做法不统一。
 * <p>
 * 本类只做只读访问，不封装 Bean 注册、容器刷新等能力；对 Spring 原生异常
 * （{@code NoSuchBeanDefinitionException}、{@code NoUniqueBeanDefinitionException}、
 * {@code BeanNotOfRequiredTypeException}、配置项类型转换失败时的
 * {@code org.springframework.core.convert.ConversionFailedException} 等）不做二次包装或吞掉，
 * 语义与直接使用 {@link ApplicationContext}/{@link Environment} 保持一致。
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    /**
     * 进程内唯一的 {@link ApplicationContext} 引用，容器启动完成后由 Spring 回调注入；
     * 用 volatile 保证写入（容器启动线程）对后续读取（业务调用可能发生在任意线程）可见。
     */
    private static volatile ApplicationContext applicationContext;

    /**
     * Spring 容器启动过程中的回调，把 {@link ApplicationContext} 保存为静态字段。
     *
     * @param applicationContext 当前 Spring 容器上下文
     * @throws BeansException 本实现不会抛出，仅为满足接口签名
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextHolder.applicationContext = applicationContext;
    }

    /**
     * 按 Bean 名称获取 Bean，返回类型由调用处的泛型赋值目标推断。
     *
     * @param name Bean 名称
     * @param <T>  返回值类型，由调用处推断，需与实际 Bean 类型一致，否则会在使用返回值时抛出
     *             {@code ClassCastException}
     * @return 该名称对应的 Bean 实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getBean(String name) {
        return (T) getContext().getBean(name);
    }

    /**
     * 按 Bean 类型获取 Bean，语义与 {@link ApplicationContext#getBean(Class)} 一致：容器中不存在
     * 匹配 Bean 或存在多个候选时，分别抛出 Spring 原生的
     * {@code NoSuchBeanDefinitionException}/{@code NoUniqueBeanDefinitionException}。
     *
     * @param requiredType 期望的 Bean 类型
     * @param <T>          Bean 类型
     * @return 该类型对应的唯一 Bean 实例
     */
    public static <T> T getBean(Class<T> requiredType) {
        return getContext().getBean(requiredType);
    }

    /**
     * 按 Bean 名称 + 类型获取 Bean，语义与 {@link ApplicationContext#getBean(String, Class)}
     * 一致：名称对应的 Bean 与 requiredType 不匹配时，抛出 Spring 原生的
     * {@code BeanNotOfRequiredTypeException}。
     *
     * @param name         Bean 名称
     * @param requiredType 期望的 Bean 类型
     * @param <T>          Bean 类型
     * @return 该名称对应、已按 requiredType 转换的 Bean 实例
     */
    public static <T> T getBean(String name, Class<T> requiredType) {
        return getContext().getBean(name, requiredType);
    }

    /**
     * 读取 {@link Environment} 中的配置项，不做类型转换，直接返回字符串。
     *
     * @param key 配置项 key
     * @return 配置值；key 不存在时返回 {@code null}
     */
    public static String getProperty(String key) {
        return getEnvironment().getProperty(key);
    }

    /**
     * 读取 {@link Environment} 中的配置项，key 不存在时返回默认值。
     *
     * @param key          配置项 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 配置值；key 不存在时返回 defaultValue
     */
    public static String getProperty(String key, String defaultValue) {
        return getEnvironment().getProperty(key, defaultValue);
    }

    /**
     * 按指定类型读取 {@link Environment} 中的配置项。配置值无法转换为 targetType 时，
     * 抛出 Spring 原生的
     * {@code org.springframework.core.convert.ConversionFailedException}，不做额外包装或吞掉。
     *
     * @param key        配置项 key
     * @param targetType 期望的目标类型
     * @param <T>        目标类型
     * @return 转换为 targetType 后的配置值；key 不存在时返回 {@code null}
     */
    public static <T> T getProperty(String key, Class<T> targetType) {
        return getEnvironment().getProperty(key, targetType);
    }

    /**
     * 按指定类型读取 {@link Environment} 中的配置项，key 不存在时返回默认值；配置值存在但无法
     * 转换为 targetType 时，抛出 Spring 原生的
     * {@code org.springframework.core.convert.ConversionFailedException}。
     *
     * @param key          配置项 key
     * @param targetType   期望的目标类型
     * @param defaultValue key 不存在时返回的默认值
     * @param <T>          目标类型
     * @return 转换为 targetType 后的配置值；key 不存在时返回 defaultValue
     */
    public static <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        return getEnvironment().getProperty(key, targetType, defaultValue);
    }

    /**
     * 获取已就绪的 {@link ApplicationContext}；容器尚未完成初始化（尚未收到
     * {@link #setApplicationContext} 回调）时快速失败，避免调用方拿到 {@code null} 后在别处才
     * 抛出更难定位的 {@code NullPointerException}。
     *
     * @return 当前 {@link ApplicationContext}
     * @throws IllegalStateException {@link ApplicationContext} 尚未初始化时抛出
     */
    private static ApplicationContext getContext() {
        ApplicationContext context = applicationContext;
        if (context == null) {
            throw new IllegalStateException(
                    "ApplicationContext 尚未初始化，SpringContextHolder 暂不可用，请确认调用时机在 Spring 容器启动完成之后");
        }
        return context;
    }

    /**
     * 获取已就绪的 {@link Environment}，判空逻辑复用 {@link #getContext()}。
     *
     * @return 当前 {@link Environment}
     */
    private static Environment getEnvironment() {
        return getContext().getEnvironment();
    }

}
