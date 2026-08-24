package com.example.template.resilience4j;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.springframework.stereotype.Service;

/**
 * 隔板（Bulkhead）用法示例。
 * <p>
 * 演示 resilience4j 的 {@link Bulkhead} 注解如何限制方法允许的最大并发调用数：当同时执行的调用数
 * 超过 {@code application.yml} 中 {@code resilience4j.bulkhead.instances.demo} 配置的最大并发数时，
 * 多出的请求会被立即拒绝，转而执行 {@link #limitedConcurrencyOperationFallback} 降级方法，避免某个
 * 慢方法被大量并发请求打满线程资源，拖累其他业务。
 * <p>
 * 注意：方法内部用 {@code Thread.sleep} 模拟耗时操作，是为了让并发请求之间有足够的时间窗口重叠，
 * 从而真正触发"同时执行数超限"这个条件；单次串行调用无法观察到拒绝效果，需要在短时间内并发发起
 * 多于配置的最大并发数的请求（例如并发调用对应 HTTP 端点数次）才能触发。
 */
@Service
public class BulkheadDemoService {

    /**
     * 模拟一个耗时操作，受隔板保护。
     *
     * @return 处理结果
     */
    @Bulkhead(name = "demo", fallbackMethod = "limitedConcurrencyOperationFallback")
    public String limitedConcurrencyOperation() {
        try {
            // 人为制造耗时，让并发请求有机会同时落在这个方法内部执行，从而触发隔板的并发数限制
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟操作被中断", e);
        }
        return "请求已放行，正常处理完成";
    }

    /**
     * {@link #limitedConcurrencyOperation()} 的降级方法：并发调用数超出隔板允许的最大并发数时会走
     * 到这里。
     *
     * @param t 触发降级的异常，通常是 {@link BulkheadFullException}
     * @return 降级结果
     */
    public String limitedConcurrencyOperationFallback(Throwable t) {
        return "【隔板降级】并发数超出限制，请求被拒绝，原因: " + t.getMessage();
    }

}
