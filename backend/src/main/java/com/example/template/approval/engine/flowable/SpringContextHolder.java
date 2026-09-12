package com.example.template.approval.engine.flowable;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Flowable 的 {@code TaskListener}/{@code ExecutionListener} 实现类由引擎按
 * {@code flowable:class} 属性反射创建，不经过 Spring 容器，不能用 {@code @Autowired} 注入业务
 * Bean。本类作为桥接：以 Spring Bean 的形式在应用启动时被容器实例化并注入
 * {@link ApplicationContext}，静态持有该上下文，供监听器通过
 * {@link #getBean(Class)} 主动获取所需的 Spring Bean（如 {@code ApplicationEventPublisher}）。
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        SpringContextHolder.context = applicationContext;
    }

    /**
     * 获取指定类型的 Spring Bean。
     *
     * @throws IllegalStateException 若 {@link ApplicationContext} 尚未初始化（正常运行时不会出现，
     *                                只会在应用启动完成、开始处理业务请求之后才会调用本方法）
     */
    public static <T> T getBean(Class<T> type) {
        return getApplicationContext().getBean(type);
    }

    /**
     * 获取持有的 {@link ApplicationContext} 本身：某些类型（如 {@link org.springframework.context.ApplicationEventPublisher}）
     * 由 {@code ApplicationContext} 直接实现，并未作为独立 Bean 注册到容器中，无法通过
     * {@link #getBean(Class)} 取到，需要直接拿到 {@code ApplicationContext} 使用。
     *
     * @throws IllegalStateException 若 {@link ApplicationContext} 尚未初始化
     */
    public static ApplicationContext getApplicationContext() {
        if (context == null) {
            throw new IllegalStateException("ApplicationContext 尚未初始化，无法获取 Bean");
        }
        return context;
    }
}
