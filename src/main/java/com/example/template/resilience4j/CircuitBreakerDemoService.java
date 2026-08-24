package com.example.template.resilience4j;

import java.util.concurrent.atomic.AtomicLong;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

/**
 * 断路器（Circuit Breaker）用法示例。
 * <p>
 * 演示 resilience4j 的 {@link CircuitBreaker} 注解如何保护一个"不稳定"的下游调用：当失败率达到
 * {@code application.yml} 中 {@code resilience4j.circuitbreaker.instances.demo} 配置的阈值时，
 * 断路器会从 CLOSED 切换到 OPEN，此后一段时间内的调用不会再真正执行被保护方法，而是直接走
 * {@link #callUnstableServiceFallback} 降级方法，避免持续调用一个大概率会失败的下游、白白耗费
 * 调用方自身的线程和连接资源。
 * <p>
 * 演示方式：连续多次调用 {@link #callUnstableService()}（对应 HTTP 端点连续请求十几次），观察
 * 返回结果从"真实调用成功/失败降级"逐渐变为"熔断降级"，等待配置的 OPEN 状态等待时间过后再次
 * 调用，又能观察到断路器尝试恢复（HALF_OPEN）的过程。
 */
@Service
public class CircuitBreakerDemoService {

    // 用于模拟下游服务"每隔一次调用失败一次"，构造出约50%的失败率来触发熔断统计
    private final AtomicLong callCount = new AtomicLong(0);

    /**
     * 模拟调用一个不稳定的下游服务。
     * <p>
     * 通过 {@code @CircuitBreaker} 注解声明该方法受名为 {@code demo} 的断路器保护；断路器处于
     * OPEN 状态期间，方法体不会被执行，直接进入 {@code fallbackMethod} 指定的降级方法。
     *
     * @return 模拟的下游调用结果
     */
    @CircuitBreaker(name = "demo", fallbackMethod = "callUnstableServiceFallback")
    public String callUnstableService() {
        long count = callCount.incrementAndGet();
        // 每隔一次调用模拟一次下游异常，制造出足够的失败率触发熔断
        if (count % 2 == 0) {
            throw new IllegalStateException("模拟下游服务调用失败，第 " + count + " 次调用");
        }
        return "下游服务调用成功，第 " + count + " 次调用";
    }

    /**
     * {@link #callUnstableService()} 的降级方法。
     * <p>
     * resilience4j 要求 fallback 方法的参数列表为"原方法参数 + Throwable"，返回类型与原方法一致；
     * 断路器处于 OPEN 状态直接拒绝调用，以及被保护方法本身抛出异常，都会落到这里。
     *
     * @param t 触发降级的异常；断路器处于 OPEN 状态直接拒绝调用时，异常类型为
     *          {@link CallNotPermittedException}
     * @return 降级结果
     */
    public String callUnstableServiceFallback(Throwable t) {
        return "【熔断降级】未真正调用下游服务，原因: " + t.getMessage();
    }

}
