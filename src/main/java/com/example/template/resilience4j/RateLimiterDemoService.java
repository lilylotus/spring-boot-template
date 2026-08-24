package com.example.template.resilience4j;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.stereotype.Service;

/**
 * 限流器（Rate Limiter）用法示例。
 * <p>
 * 演示 resilience4j 的 {@link RateLimiter} 注解如何限制方法在单位时间内允许被调用的次数：超出
 * {@code application.yml} 中 {@code resilience4j.ratelimiter.instances.demo} 配置的许可数的请求
 * 会被立即拒绝，转而执行 {@link #limitedOperationFallback} 降级方法。
 * <p>
 * 注意：限流阈值配置得较小是为了便于演示，单次串行调用观察不到拒绝效果，需要在配置的刷新周期内
 * 并发发起多于许可数的请求（例如短时间内并发调用对应 HTTP 端点数次）才能触发限流。
 */
@Service
public class RateLimiterDemoService {

    /**
     * 模拟一个受限流器保护的操作。
     *
     * @return 处理结果
     */
    @RateLimiter(name = "demo", fallbackMethod = "limitedOperationFallback")
    public String limitedOperation() {
        return "请求已放行，正常处理完成";
    }

    /**
     * {@link #limitedOperation()} 的降级方法：单位时间内的请求数超出限流许可数时会走到这里。
     *
     * @param t 触发降级的异常，通常是 {@link RequestNotPermitted}
     * @return 降级结果
     */
    public String limitedOperationFallback(Throwable t) {
        return "【限流降级】请求被限流拒绝，原因: " + t.getMessage();
    }

}
