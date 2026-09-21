package cn.nihility.gw.config;

import cn.nihility.gw.trace.TraceFilter;
import cn.nihility.gw.trace.TraceIdThreadLocalAccessor;
import cn.nihility.gw.trace.TraceTaskDecorator;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import reactor.core.publisher.Hooks;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 链路追踪相关的 Bean 装配。
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfiguration {

    /** Reactor 的钩子是 JVM 全局的，用它保证只初始化一次(测试中会创建多个应用上下文)。 */
    private static final AtomicBoolean PROPAGATION_INITIALIZED = new AtomicBoolean(false);

    /**
     * 注册链路追踪过滤器。
     *
     * <p>顺序由 TraceFilter 自身实现的 Ordered 决定(HIGHEST_PRECEDENCE)，
     * 保证 traceId 在其它 WebFilter 和网关转发过滤器执行之前就已就绪。</p>
     */
    @Bean
    public TraceFilter traceFilter() {
        return new TraceFilter();
    }

    /** 交给 Spring Boot 自动装配到 applicationTaskExecutor，使 @Async 任务也能打印 traceId。 */
    @Bean
    public TaskDecorator traceTaskDecorator() {
        return new TraceTaskDecorator();
    }

    /**
     * 开启 Reactor 的自动上下文传播，并注册 MDC 桥接器。
     *
     * <p>少了这一步，traceId 只会待在 Reactor Context 里，业务代码的 log.info 打不出来；
     * WebFlux 一个请求会在多个线程间流转，靠 ThreadLocal 是接不上的。</p>
     */
    @PostConstruct
    public void enableReactorContextPropagation() {
        if (PROPAGATION_INITIALIZED.compareAndSet(false, true)) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(new TraceIdThreadLocalAccessor());
            Hooks.enableAutomaticContextPropagation();
        }
    }

}
