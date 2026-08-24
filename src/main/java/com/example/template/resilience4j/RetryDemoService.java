package com.example.template.resilience4j;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

/**
 * 重试（Retry）用法示例。
 * <p>
 * 演示 resilience4j 的 {@link Retry} 注解如何在方法抛出异常后，按 {@code application.yml} 中
 * {@code resilience4j.retry.instances.demo} 配置的最大尝试次数和重试间隔自动重试；提供两个方法
 * 分别演示"重试后成功"和"重试次数耗尽后走降级"两条路径。
 */
@Service
public class RetryDemoService {

    // 记录recoverAfterRetry方法被实际调用的次数，用于模拟"前几次失败、之后成功"
    private final AtomicInteger recoverAttempt = new AtomicInteger(0);

    /**
     * 模拟一个"前两次调用失败，第三次调用成功"的下游服务。
     * <p>
     * 配合 {@code resilience4j.retry.instances.demo.max-attempts=3}，resilience4j 会在前两次
     * 调用失败后自动重试，直到第三次调用成功返回，调用方感知不到中间失败重试的过程。
     *
     * @return 调用成功后的结果
     */
    @Retry(name = "demo", fallbackMethod = "recoverAfterRetryFallback")
    public String recoverAfterRetry() {
        int attempt = recoverAttempt.incrementAndGet();
        if (attempt < 3) {
            throw new IllegalStateException("模拟下游服务调用失败，第 " + attempt + " 次调用");
        }
        return "重试后调用成功，第 " + attempt + " 次调用";
    }

    /**
     * {@link #recoverAfterRetry()} 的降级方法；由于该方法最多在第三次调用就会成功，正常情况下不会
     * 触发到这里，仅作为 {@code @Retry} 注解要求的完整用法演示保留。
     *
     * @param t 触发降级的异常
     * @return 降级结果
     */
    public String recoverAfterRetryFallback(Throwable t) {
        return "【重试耗尽降级】" + t.getMessage();
    }

    /**
     * 模拟一个永远失败的下游服务，用于演示"配置的最大重试次数全部用尽后触发 fallback"这条路径。
     *
     * @return 不会正常返回，方法体必定抛出异常
     */
    @Retry(name = "demo", fallbackMethod = "alwaysFailFallback")
    public String alwaysFail() {
        throw new IllegalStateException("模拟下游服务持续不可用");
    }

    /**
     * {@link #alwaysFail()} 的降级方法：配置的最大重试次数全部用尽仍然失败后，最终会走到这里。
     *
     * @param t 触发降级的异常
     * @return 降级结果
     */
    public String alwaysFailFallback(Throwable t) {
        return "【重试耗尽降级】未能调用成功，原因: " + t.getMessage();
    }

}
