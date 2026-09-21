package cn.nihility.gw.trace;

import org.springframework.core.task.TaskDecorator;

/**
 * 让 Spring 托管的线程池(@Async、TaskExecutor 等)自动传递 traceId。
 *
 * <p>只要容器中存在一个 TaskDecorator Bean，Spring Boot 的 TaskExecutionAutoConfiguration
 * 就会把它装配到自动配置的 applicationTaskExecutor 上，无需再自定义线程池。</p>
 */
public class TraceTaskDecorator implements TaskDecorator {

    /** 在提交线程上抓取 MDC 快照，并在工作线程执行前后完成还原与回滚。 */
    @Override
    public Runnable decorate(Runnable runnable) {
        return TraceContext.wrap(runnable);
    }

}
